package com.retailmind.admin.catalogo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD de catálogo sobre PostgreSQL (categoria, marca, producto,
 * producto_variante, atributos). Todo dentro de @Transactional para que
 * PgSessionRoleAspect asuma el rol de grupo de la sesión (admin en /api/admin).
 * Nunca se escriben columnas generadas ni totales: aquí no existen, pero las
 * fechas (fecha_creacion / fecha_actualizacion) las maneja la BD.
 */
@Service
public class CatalogoAdminService {

    private final JdbcTemplate pg;

    public CatalogoAdminService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Categorías ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCategorias() {
        return pg.queryForList("""
                SELECT id, categoria_padre_id, nombre, slug, descripcion, orden, activo
                FROM categoria ORDER BY orden, nombre""");
    }

    @Transactional
    public long crearCategoria(String nombre, String slug, String descripcion, Long padreId) {
        return idDe(pg.queryForObject("""
                INSERT INTO categoria (nombre, slug, descripcion, categoria_padre_id)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, nombre, slug, descripcion, padreId));
    }

    @Transactional
    public void editarCategoria(long id, String nombre, String slug, String descripcion) {
        exigir(pg.update("""
                UPDATE categoria SET nombre = ?, slug = ?, descripcion = ?
                WHERE id = ?""", nombre, slug, descripcion, id), "categoria", id);
    }

    @Transactional
    public void activarCategoria(long id, boolean activo) {
        exigir(pg.update("UPDATE categoria SET activo = ? WHERE id = ?", activo, id),
                "categoria", id);
    }

    // ── Marcas ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarMarcas() {
        return pg.queryForList("SELECT id, nombre, slug, descripcion, activo FROM marca ORDER BY nombre");
    }

    @Transactional
    public long crearMarca(String nombre, String slug, String descripcion) {
        return idDe(pg.queryForObject("""
                INSERT INTO marca (nombre, slug, descripcion)
                VALUES (?, ?, ?) RETURNING id""", Long.class, nombre, slug, descripcion));
    }

    @Transactional
    public void editarMarca(long id, String nombre, String slug, String descripcion) {
        exigir(pg.update("UPDATE marca SET nombre = ?, slug = ?, descripcion = ? WHERE id = ?",
                nombre, slug, descripcion, id), "marca", id);
    }

    @Transactional
    public void activarMarca(long id, boolean activo) {
        exigir(pg.update("UPDATE marca SET activo = ? WHERE id = ?", activo, id), "marca", id);
    }

    // ── Productos ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProductos() {
        return pg.queryForList("""
                SELECT p.id, p.nombre, p.slug, p.descripcion_corta, p.publicado, p.activo,
                       m.nombre AS marca,
                       (SELECT count(*) FROM producto_variante pv WHERE pv.producto_id = p.id) AS variantes
                FROM producto p LEFT JOIN marca m ON m.id = p.marca_id
                ORDER BY p.nombre""");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerProducto(long id) {
        Map<String, Object> producto = pg.queryForMap("""
                SELECT p.id, p.nombre, p.slug, p.descripcion_corta, p.descripcion,
                       p.publicado, p.destacado, p.activo, p.marca_id, m.nombre AS marca
                FROM producto p LEFT JOIN marca m ON m.id = p.marca_id
                WHERE p.id = ?""", id);
        producto.put("variantes", pg.queryForList("""
                SELECT pv.id, pv.sku, pv.precio, pv.costo, pv.es_predeterminada, pv.activo,
                       COALESCE((SELECT string_agg(a.nombre || ': ' || va.valor, ', ' ORDER BY a.nombre)
                                 FROM variante_valor_atributo vva
                                 JOIN valor_atributo va ON va.id = vva.valor_atributo_id
                                 JOIN atributo a ON a.id = va.atributo_id
                                 WHERE vva.producto_variante_id = pv.id), '') AS atributos
                FROM producto_variante pv WHERE pv.producto_id = ? ORDER BY pv.sku""", id));
        producto.put("categorias", pg.queryForList("""
                SELECT c.id, c.nombre, pc.es_principal
                FROM producto_categoria pc JOIN categoria c ON c.id = pc.categoria_id
                WHERE pc.producto_id = ?""", id));
        return producto;
    }

    @Transactional
    public long crearProducto(String nombre, String slug, Long marcaId, String descripcionCorta,
                              String descripcion, boolean publicado, List<Long> categoriaIds) {
        long id = idDe(pg.queryForObject("""
                INSERT INTO producto (nombre, slug, marca_id, descripcion_corta, descripcion, publicado)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, nombre, slug, marcaId, descripcionCorta, descripcion, publicado));
        if (categoriaIds != null) {
            boolean principal = true;
            for (Long catId : categoriaIds) {
                pg.update("""
                        INSERT INTO producto_categoria (producto_id, categoria_id, es_principal)
                        VALUES (?, ?, ?)""", id, catId, principal);
                principal = false;
            }
        }
        return id;
    }

    @Transactional
    public void editarProducto(long id, String nombre, String slug, Long marcaId,
                               String descripcionCorta, String descripcion, Boolean publicado) {
        exigir(pg.update("""
                UPDATE producto
                SET nombre = ?, slug = ?, marca_id = ?, descripcion_corta = ?,
                    descripcion = ?, publicado = COALESCE(?, publicado)
                WHERE id = ?""",
                nombre, slug, marcaId, descripcionCorta, descripcion, publicado, id),
                "producto", id);
    }

    @Transactional
    public void activarProducto(long id, boolean activo) {
        exigir(pg.update("UPDATE producto SET activo = ? WHERE id = ?", activo, id), "producto", id);
    }

    // ── Variantes ────────────────────────────────────────────────────────

    @Transactional
    public long crearVariante(long productoId, String sku, BigDecimal precio, BigDecimal costo,
                              String codigoBarras, boolean esPredeterminada) {
        return idDe(pg.queryForObject("""
                INSERT INTO producto_variante
                    (producto_id, sku, precio, costo, codigo_barras, es_predeterminada)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, productoId, sku, precio, costo, codigoBarras, esPredeterminada));
    }

    @Transactional
    public void editarVariante(long id, String sku, BigDecimal precio, BigDecimal costo) {
        exigir(pg.update("""
                UPDATE producto_variante SET sku = ?, precio = ?, costo = ? WHERE id = ?""",
                sku, precio, costo, id), "producto_variante", id);
    }

    @Transactional
    public void activarVariante(long id, boolean activo) {
        exigir(pg.update("UPDATE producto_variante SET activo = ? WHERE id = ?", activo, id),
                "producto_variante", id);
    }

    /** Asocia un valor de atributo existente (talla M, color Negro...) a la variante. */
    @Transactional
    public void asociarAtributo(long varianteId, long valorAtributoId) {
        pg.update("""
                INSERT INTO variante_valor_atributo (producto_variante_id, valor_atributo_id)
                VALUES (?, ?)
                ON CONFLICT (producto_variante_id, valor_atributo_id) DO NOTHING""",
                varianteId, valorAtributoId);
    }

    // ── Atributos ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarAtributos() {
        return pg.queryForList("""
                SELECT a.id, a.codigo, a.nombre, a.tipo, a.activo,
                       COALESCE(json_agg(json_build_object('id', va.id, 'valor', va.valor)
                                ORDER BY va.orden) FILTER (WHERE va.id IS NOT NULL), '[]') AS valores
                FROM atributo a LEFT JOIN valor_atributo va ON va.atributo_id = a.id
                GROUP BY a.id ORDER BY a.nombre""");
    }

    @Transactional
    public long crearAtributo(String codigo, String nombre, String tipo) {
        return idDe(pg.queryForObject("""
                INSERT INTO atributo (codigo, nombre, tipo) VALUES (?, ?, ?) RETURNING id""",
                Long.class, codigo, nombre, tipo));
    }

    @Transactional
    public long crearValorAtributo(long atributoId, String valor) {
        return idDe(pg.queryForObject("""
                INSERT INTO valor_atributo (atributo_id, valor) VALUES (?, ?) RETURNING id""",
                Long.class, atributoId, valor));
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private static long idDe(Long id) {
        if (id == null) throw new IllegalStateException("INSERT no devolvio id");
        return id;
    }

    private static void exigir(int filas, String tabla, long id) {
        if (filas == 0) throw new IllegalArgumentException("No existe " + tabla + " con id " + id);
    }
}
