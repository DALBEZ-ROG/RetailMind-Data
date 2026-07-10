package com.retailmind.inventario;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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

    // ── Ajuste de inventario (CU-O-16) ───────────────────────────────────

    /**
     * Ajuste manual por conteo físico o merma (grp_bodega o admin).
     * ajuste_inventario es SOLO cabecera (como transferencia_bodega): la
     * variante y la cantidad quedan en el movimiento del kardex con
     * referencia_tipo='ajuste_inventario' y en el texto del motivo.
     * tipo de la cabecera: 'positivo' (entrada) / 'negativo' (salida),
     * según el check constraint de la tabla.
     */
    @Transactional
    public Map<String, Object> registrarAjuste(long varianteId, long bodegaId, String tipo,
                                               int cantidad, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo del ajuste es obligatorio");
        }
        boolean esEntrada = "entrada".equals(tipo);
        if (!esEntrada && !"salida".equals(tipo)) {
            throw new IllegalArgumentException(
                    "El tipo de ajuste debe ser 'entrada' o 'salida'");
        }
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        }
        String sku = pg.queryForObject(
                "SELECT sku FROM producto_variante WHERE id = ?", String.class, varianteId);

        Long ajusteId = pg.queryForObject("""
                INSERT INTO ajuste_inventario
                    (bodega_id, usuario_id, tipo, estado, motivo, fecha_aplicacion)
                VALUES (?, ?, ?, 'aplicado', ?, now())
                RETURNING id""",
                Long.class, bodegaId, usuarioActualId(),
                esEntrada ? "positivo" : "negativo",
                "[" + sku + " x" + cantidad + "] " + motivo.trim());

        // Kardex + stock (valida stock suficiente en salidas con FOR UPDATE)
        int stockNuevo = stock.mover(varianteId, bodegaId,
                esEntrada ? "entrada_ajuste" : "salida_ajuste", cantidad,
                "ajuste_inventario", ajusteId, null, usuarioActualId(), motivo.trim());

        return Map.of("id", ajusteId, "sku", sku, "tipo", tipo, "cantidad", cantidad,
                "stockResultante", stockNuevo, "estado", "aplicado");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarAjustes() {
        // Sin JOIN a usuario: grp_bodega no tiene SELECT sobre esa tabla
        return pg.queryForList("""
                SELECT a.id, a.tipo, a.estado, a.motivo, a.fecha_aplicacion,
                       b.nombre AS bodega
                FROM ajuste_inventario a
                JOIN bodega b ON b.id = a.bodega_id
                ORDER BY a.id DESC""");
    }

    /**
     * Anula un ajuste APLICADO revirtiendo su movimiento de kardex con un
     * contramovimiento (entrada⇄salida). El CHECK de la tabla ya contemplaba
     * 'anulado' pero no existía flujo. El estado 'borrador' sigue sin flujo:
     * la cabecera no persiste variante/cantidad (viven solo en el kardex),
     * así que aplicar un borrador exigiría una tabla de detalle que el
     * esquema no tiene — queda como mejora futura documentada.
     */
    @Transactional
    public Map<String, Object> anularAjuste(long ajusteId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo de la anulación es obligatorio");
        }
        List<Map<String, Object>> filas = pg.queryForList(
                "SELECT estado FROM ajuste_inventario WHERE id = ? FOR UPDATE", ajusteId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el ajuste " + ajusteId);
        }
        String estado = (String) filas.get(0).get("estado");
        if (!"aplicado".equals(estado)) {
            throw new IllegalStateException("Solo se puede anular un ajuste en estado "
                    + "'aplicado' (estado actual: '" + estado + "')");
        }
        // Contramovimiento de cada movimiento original del ajuste. El estado
        // 'anulado' (guardia de arriba) garantiza que esto corre UNA sola vez,
        // por lo que aquí solo existen los movimientos originales.
        List<Map<String, Object>> movimientos = pg.queryForList("""
                SELECT m.producto_variante_id, m.bodega_id, m.cantidad,
                       m.costo_unitario, tm.codigo
                FROM movimiento_inventario m
                JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
                WHERE m.referencia_tipo = 'ajuste_inventario' AND m.referencia_id = ?""",
                ajusteId);
        if (movimientos.isEmpty()) {
            throw new IllegalStateException(
                    "El ajuste " + ajusteId + " no tiene movimientos de kardex que revertir");
        }
        for (Map<String, Object> m : movimientos) {
            boolean fueEntrada = "entrada_ajuste".equals(m.get("codigo"));
            // La reversión de una entrada valida stock suficiente (FOR UPDATE)
            stock.mover(((Number) m.get("producto_variante_id")).longValue(),
                    ((Number) m.get("bodega_id")).longValue(),
                    fueEntrada ? "salida_ajuste" : "entrada_ajuste",
                    ((Number) m.get("cantidad")).intValue(),
                    "ajuste_inventario", ajusteId,
                    (java.math.BigDecimal) m.get("costo_unitario"),
                    usuarioActualId(), "Anulación del ajuste #" + ajusteId + ": " + motivo.trim());
        }
        pg.update("""
                UPDATE ajuste_inventario
                SET estado = 'anulado', motivo = motivo || ' · ANULADO: ' || ?
                WHERE id = ?""", motivo.trim(), ajusteId);
        return Map.of("id", ajusteId, "estado", "anulado",
                "movimientosRevertidos", movimientos.size());
    }

    // ── Kardex (CU-O-17) ─────────────────────────────────────────────────

    /**
     * Kardex de movimiento_inventario, filtrable por variante y/o bodega.
     * Solo tablas con SELECT para bodega/gerente/analista/admin: sin JOIN a
     * usuario ni subconsulta a devolucion (grp_bodega no las ve; un 42501
     * abortaria la transaccion).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> kardex(Long varianteId, Long bodegaId) {
        return pg.queryForList("""
                SELECT m.id, m.fecha_creacion, pv.sku, pr.nombre AS producto,
                       b.nombre AS bodega, tm.nombre AS tipo_movimiento, tm.naturaleza,
                       m.cantidad, m.stock_anterior, m.stock_nuevo, m.costo_unitario,
                       m.referencia_tipo, m.referencia_id,
                       CASE m.referencia_tipo
                           WHEN 'recepcion_mercancia' THEN 'Recepcion ' || COALESCE(
                               (SELECT r.numero FROM recepcion_mercancia r WHERE r.id = m.referencia_id),
                               '#' || m.referencia_id)
                           WHEN 'pedido' THEN 'Pedido ' || COALESCE(
                               (SELECT p2.numero FROM pedido p2 WHERE p2.id = m.referencia_id),
                               '#' || m.referencia_id)
                           WHEN 'devolucion' THEN 'Devolucion #' || m.referencia_id
                           WHEN 'transferencia_bodega' THEN 'Transferencia #' || m.referencia_id
                           WHEN 'ajuste_inventario' THEN 'Ajuste #' || m.referencia_id
                           ELSE COALESCE(m.referencia_tipo || ' #' || m.referencia_id, '—')
                       END AS referencia,
                       m.observacion
                FROM movimiento_inventario m
                JOIN producto_variante pv ON pv.id = m.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                JOIN bodega b ON b.id = m.bodega_id
                JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
                WHERE (?::bigint IS NULL OR m.producto_variante_id = ?)
                  AND (?::bigint IS NULL OR m.bodega_id = ?)
                ORDER BY m.id DESC
                LIMIT 500""",
                varianteId, varianteId, bodegaId, bodegaId);
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
