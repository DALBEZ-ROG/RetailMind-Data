package com.retailmind.inventario;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movimiento de stock con kardex — el patrón del ciclo de compra extraído
 * para reuso (transferencias, ventas, devoluciones):
 *   upsert de inventario + SELECT ... FOR UPDATE + fila en
 *   movimiento_inventario (kardex polimórfico) + UPDATE del stock.
 *
 * El signo lo decide el factor del tipo_movimiento (entrada=+1 / salida=-1),
 * validado contra el catálogo tipo_movimiento (lista blanca en BD).
 * MANDATORY: siempre participa de la transacción del caso de uso que lo llama
 * (así corre bajo el mismo SET LOCAL ROLE).
 */
@Service
public class StockService {

    private final JdbcTemplate pg;

    public StockService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    /**
     * Mueve stock y registra el kardex. Devuelve el stock resultante.
     * @param tipoCodigo código de tipo_movimiento (p.ej. salida_venta,
     *                   entrada_devolucion_cliente, salida_transferencia)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int mover(long varianteId, long bodegaId, String tipoCodigo, int cantidad,
                     String referenciaTipo, long referenciaId, BigDecimal costoUnitario,
                     Long usuarioId, String observacion) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        }
        Integer factor = pg.queryForObject(
                "SELECT factor FROM tipo_movimiento WHERE codigo = ? AND activo",
                Integer.class, tipoCodigo);

        pg.update("""
                INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual)
                VALUES (?, ?, 0)
                ON CONFLICT (producto_variante_id, bodega_id) DO NOTHING""",
                varianteId, bodegaId);
        Integer stockAnterior = pg.queryForObject("""
                SELECT stock_actual FROM inventario
                WHERE producto_variante_id = ? AND bodega_id = ? FOR UPDATE""",
                Integer.class, varianteId, bodegaId);

        int stockNuevo = stockAnterior + factor * cantidad;
        if (stockNuevo < 0) {
            String sku = pg.queryForObject(
                    "SELECT sku FROM producto_variante WHERE id = ?", String.class, varianteId);
            String bodega = pg.queryForObject(
                    "SELECT nombre FROM bodega WHERE id = ?", String.class, bodegaId);
            throw new IllegalArgumentException("Stock insuficiente para SKU " + sku
                    + " en bodega " + bodega + ": disponible " + stockAnterior
                    + ", solicitado " + cantidad);
        }

        pg.update("""
                INSERT INTO movimiento_inventario
                    (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                     cantidad, stock_anterior, stock_nuevo, costo_unitario,
                     referencia_tipo, referencia_id, observacion)
                VALUES (?, ?, (SELECT id FROM tipo_movimiento WHERE codigo = ?), ?,
                        ?, ?, ?, ?, ?, ?, ?)""",
                varianteId, bodegaId, tipoCodigo, usuarioId,
                cantidad, stockAnterior, stockNuevo, costoUnitario,
                referenciaTipo, referenciaId, observacion);

        pg.update("""
                UPDATE inventario SET stock_actual = ?
                WHERE producto_variante_id = ? AND bodega_id = ?""",
                stockNuevo, varianteId, bodegaId);
        return stockNuevo;
    }
}
