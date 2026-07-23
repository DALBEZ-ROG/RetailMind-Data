package com.retailmind.referencias;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogos de referencia de solo lectura para poblar selects del frontend
 * operativo (compras, ventas, inventario). Corre bajo SET LOCAL ROLE del
 * usuario autenticado (PgSessionRoleAspect), así que la BD sigue mandando.
 */
@Service
public class ReferenciasService {

    private final JdbcTemplate pg;

    public ReferenciasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> proveedores() {
        return pg.queryForList("""
                SELECT id, razon_social, dias_credito FROM proveedor
                WHERE activo ORDER BY razon_social""");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> bodegas() {
        return pg.queryForList(
                "SELECT id, codigo, nombre FROM bodega WHERE activo ORDER BY nombre");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> clientes() {
        return pg.queryForList("""
                SELECT id, nombre || ' ' || COALESCE(apellido, '') AS nombre, email
                FROM cliente ORDER BY nombre""");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> transportistas() {
        return pg.queryForList(
                "SELECT id, nombre FROM transportista WHERE activo ORDER BY nombre");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> metodosEnvio() {
        return pg.queryForList(
                "SELECT id, codigo, nombre FROM metodo_envio WHERE activo ORDER BY nombre");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> metodosPago() {
        return pg.queryForList(
                "SELECT id, codigo, nombre FROM metodo_pago WHERE activo ORDER BY nombre");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> motivosDevolucion() {
        return pg.queryForList(
                "SELECT id, codigo, nombre FROM motivo_devolucion WHERE activo ORDER BY nombre");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> variantes() {
        return pg.queryForList("""
                SELECT pv.id, pv.sku, pr.nombre AS producto, pv.precio, pv.costo
                FROM producto_variante pv
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE pv.activo ORDER BY pr.nombre, pv.sku""");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> stock(Long varianteId, Long bodegaId) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.producto_variante_id, pv.sku, pr.nombre AS producto,
                       i.bodega_id, b.nombre AS bodega, i.stock_actual,
                       i.stock_minimo, i.stock_maximo
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                JOIN bodega b ON b.id = i.bodega_id
                WHERE 1=1""");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (varianteId != null) { sql.append(" AND i.producto_variante_id = ?"); args.add(varianteId); }
        if (bodegaId != null)   { sql.append(" AND i.bodega_id = ?");            args.add(bodegaId); }
        sql.append(" ORDER BY pr.nombre, pv.sku, b.nombre");
        return pg.queryForList(sql.toString(), args.toArray());
    }
}
