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

    /** Tope de un selector: nunca se descarga el padrón entero. */
    private static final int TOPE_SELECTOR = 50;

    /**
     * Clientes para los selectores, BUSCANDO EN EL SERVIDOR.
     *
     * <h3>Por qué no se pagina: se busca</h3>
     * Devolvía los **50.072 clientes** (4,03 MB medidos) en cada apertura de la
     * pantalla de pedidos y de la de tickets, y esta última los pintaba como
     * 50.072 {@code <mat-option>}. Un selector no se recorre por páginas —nadie
     * busca a un cliente pasando 2.003 páginas de 25—: se escribe el nombre. Por
     * eso el criterio del buscador se resuelve aquí, contra el padrón COMPLETO,
     * y solo vuelven {@link #TOPE_SELECTOR} filas.
     *
     * <h3>Sin texto devuelve las primeras, no todas</h3>
     * Así el desplegable se abre con algo que enseñar mientras el usuario
     * escribe, sin que eso implique traerse el padrón.
     *
     * El texto viaja como parámetro ligado; no se concatena nada.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> clientes(String q) {
        String filtro = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        if (filtro == null) {
            return pg.queryForList("""
                    SELECT id, nombre || ' ' || COALESCE(apellido, '') AS nombre, email
                    FROM cliente ORDER BY nombre LIMIT ?""", TOPE_SELECTOR);
        }
        return pg.queryForList("""
                SELECT id, nombre || ' ' || COALESCE(apellido, '') AS nombre, email
                FROM cliente
                WHERE nombre ILIKE ? OR apellido ILIKE ? OR email ILIKE ?
                   OR (nombre || ' ' || COALESCE(apellido, '')) ILIKE ?
                ORDER BY nombre LIMIT ?""",
                filtro, filtro, filtro, filtro, TOPE_SELECTOR);
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
