package com.retailmind.inventario;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Caso de uso: TRANSFERENCIA ENTRE BODEGAS (grp_bodega o admin).
 *
 * Nota de diseño: transferencia_bodega es SOLO cabecera (no tiene detalle en
 * el esquema); la variante y la cantidad quedan registradas en los DOS
 * movimientos del kardex (salida_transferencia en origen y
 * entrada_transferencia en destino) con referencia_tipo='transferencia_bodega'
 * y en la observación de la cabecera.
 */
@Service
public class InventarioService {

    private final JdbcTemplate pg;
    private final StockService stock;

    public InventarioService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg, StockService stock) {
        this.pg = pg;
        this.stock = stock;
    }

    @Transactional
    public Map<String, Object> transferir(long varianteId, long bodegaOrigenId,
                                          long bodegaDestinoId, int cantidad, String observacion) {
        if (bodegaOrigenId == bodegaDestinoId) {
            throw new IllegalArgumentException("La bodega origen y destino deben ser distintas");
        }
        String sku = pg.queryForObject(
                "SELECT sku FROM producto_variante WHERE id = ?", String.class, varianteId);

        Long transferenciaId = pg.queryForObject("""
                INSERT INTO transferencia_bodega
                    (bodega_origen_id, bodega_destino_id, usuario_solicita_id, estado,
                     fecha_envio, fecha_recepcion, observacion)
                VALUES (?, ?, ?, 'recibida', now(), now(), ?)
                RETURNING id""",
                Long.class, bodegaOrigenId, bodegaDestinoId, usuarioActualId(),
                "[" + sku + " x" + cantidad + "] " + (observacion != null ? observacion : ""));

        // Salida en origen (valida stock suficiente con FOR UPDATE) y entrada en destino
        int stockOrigen = stock.mover(varianteId, bodegaOrigenId, "salida_transferencia",
                cantidad, "transferencia_bodega", transferenciaId, null, usuarioActualId(), null);
        int stockDestino = stock.mover(varianteId, bodegaDestinoId, "entrada_transferencia",
                cantidad, "transferencia_bodega", transferenciaId, null, usuarioActualId(), null);

        return Map.of("id", transferenciaId, "sku", sku, "cantidad", cantidad,
                "stockOrigen", stockOrigen, "stockDestino", stockDestino, "estado", "recibida");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarTransferencias() {
        return pg.queryForList("""
                SELECT t.id, t.estado, t.fecha_envio, t.observacion,
                       bo.codigo AS bodega_origen, bd.codigo AS bodega_destino,
                       u.email AS solicitado_por
                FROM transferencia_bodega t
                JOIN bodega bo ON bo.id = t.bodega_origen_id
                JOIN bodega bd ON bd.id = t.bodega_destino_id
                LEFT JOIN usuario u ON u.id = t.usuario_solicita_id
                ORDER BY t.id DESC""");
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
