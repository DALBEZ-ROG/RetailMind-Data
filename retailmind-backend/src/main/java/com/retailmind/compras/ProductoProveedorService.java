package com.retailmind.compras;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;

/**
 * Catálogo proveedor ↔ producto (producto_proveedor, OTD-COM-10, script 51):
 * a qué proveedor conviene comprarle cada variante — costo pactado, plazo de
 * entrega, cantidad mínima y marcador de preferido.
 *
 * Escriben COMPRAS/ADMIN (SecurityConfig espeja los GRANTs de la tabla);
 * GERENTE lee la ficha. BODEGA/DESPACHO no acceden (la tabla contiene costo:
 * segregación financiera, script 41) — por eso la alimentación automática
 * desde la recepción va vía fn_upsert_producto_proveedor SECURITY DEFINER.
 *
 * Un solo proveedor PREFERIDO por variante: el índice único parcial
 * uq_producto_proveedor_preferido es el backstop; aquí, al marcar uno, se
 * desmarca al resto en la misma transacción.
 */
@Service
public class ProductoProveedorService {

    private final JdbcTemplate pg;
    private final AuditoriaService auditoria;

    public ProductoProveedorService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                    AuditoriaService auditoria) {
        this.pg = pg;
        this.auditoria = auditoria;
    }

    /** Fichas de proveedor con el tamaño de su catálogo. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProveedores() {
        return pg.queryForList("""
                SELECT p.id, p.ruc, p.razon_social, p.nombre_comercial, p.email,
                       p.telefono, p.dias_credito, p.activo,
                       (SELECT count(*) FROM producto_proveedor pp
                        WHERE pp.proveedor_id = p.id AND pp.activo) AS productos
                FROM proveedor p
                ORDER BY COALESCE(p.nombre_comercial, p.razon_social)""");
    }

    /** "Productos que ofrece" el proveedor, con su variante y datos comerciales. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProductosDe(long proveedorId) {
        return pg.queryForList("""
                SELECT pp.id, pp.producto_variante_id, pr.nombre AS producto, pv.sku,
                       pp.codigo_proveedor, pp.costo, pp.tiempo_entrega_dias,
                       pp.cantidad_minima, pp.es_preferido, pp.activo,
                       pp.fecha_creacion, pp.fecha_actualizacion
                FROM producto_proveedor pp
                JOIN producto_variante pv ON pv.id = pp.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE pp.proveedor_id = ?
                ORDER BY pr.nombre, pv.sku""", proveedorId);
    }

    /** Asocia una variante al proveedor con sus condiciones comerciales. */
    @Transactional
    public long asociar(long proveedorId, Long varianteId, BigDecimal costo,
                        Integer tiempoEntregaDias, Integer cantidadMinima,
                        Boolean esPreferido, String codigoProveedor) {
        validarDatos(varianteId, costo, tiempoEntregaDias, cantidadMinima);
        Integer proveedorOk = pg.queryForObject(
                "SELECT count(*) FROM proveedor WHERE id = ? AND activo",
                Integer.class, proveedorId);
        if (proveedorOk == null || proveedorOk == 0) {
            throw new IllegalArgumentException("El proveedor no existe o está inactivo");
        }
        Integer repetido = pg.queryForObject("""
                SELECT count(*) FROM producto_proveedor
                WHERE proveedor_id = ? AND producto_variante_id = ?""",
                Integer.class, proveedorId, varianteId);
        if (repetido != null && repetido > 0) {
            throw new IllegalStateException(
                    "Ese producto ya está asociado a este proveedor; edítalo en lugar de crearlo");
        }
        boolean preferido = Boolean.TRUE.equals(esPreferido);
        if (preferido) {
            desmarcarPreferido(varianteId, null);
        }
        Long id = pg.queryForObject("""
                INSERT INTO producto_proveedor
                    (proveedor_id, producto_variante_id, costo, tiempo_entrega_dias,
                     cantidad_minima, es_preferido, codigo_proveedor)
                VALUES (?, ?, ?, ?, COALESCE(?, 1), ?, NULLIF(?, ''))
                RETURNING id""", Long.class,
                proveedorId, varianteId, costo, tiempoEntregaDias, cantidadMinima,
                preferido, codigoProveedor);
        auditoria.registrar("producto_proveedor", id, "INSERT", null,
                Map.of("proveedorId", proveedorId, "varianteId", varianteId,
                       "costo", costo, "esPreferido", preferido));
        return id;
    }

    /** Edita las condiciones comerciales (no cambia proveedor ni variante). */
    @Transactional
    public void editar(long id, BigDecimal costo, Integer tiempoEntregaDias,
                       Integer cantidadMinima, Boolean esPreferido, String codigoProveedor) {
        Map<String, Object> actual = obtener(id);
        long varianteId = ((Number) actual.get("producto_variante_id")).longValue();
        validarDatos(varianteId, costo, tiempoEntregaDias, cantidadMinima);
        boolean preferido = Boolean.TRUE.equals(esPreferido);
        if (preferido) {
            desmarcarPreferido(varianteId, id);
        }
        pg.update("""
                UPDATE producto_proveedor
                SET costo = ?, tiempo_entrega_dias = ?, cantidad_minima = COALESCE(?, 1),
                    es_preferido = ?, codigo_proveedor = NULLIF(?, '')
                WHERE id = ?""",
                costo, tiempoEntregaDias, cantidadMinima, preferido, codigoProveedor, id);
        auditoria.registrar("producto_proveedor", id, "UPDATE",
                Map.of("costo", actual.get("costo"),
                       "esPreferido", actual.get("es_preferido")),
                Map.of("costo", costo, "esPreferido", preferido));
    }

    @Transactional
    public void activar(long id, boolean activo) {
        int filas = pg.update(
                "UPDATE producto_proveedor SET activo = ? WHERE id = ?", activo, id);
        if (filas == 0) {
            throw new NoSuchElementException("No existe la relación producto-proveedor " + id);
        }
    }

    /**
     * Buscador del selector de producto (mismo patrón que soporte, script 50):
     * búsqueda en servidor por nombre o SKU, mínimo 2 letras, tope 20.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> buscarProductosRef(String q) {
        String filtro = q == null ? "" : q.trim();
        if (filtro.length() < 2) {
            return List.of();
        }
        return pg.queryForList("""
                SELECT pv.id, p.nombre, pv.sku
                FROM producto_variante pv
                JOIN producto p ON p.id = pv.producto_id
                WHERE pv.activo AND p.activo
                  AND (p.nombre ILIKE '%' || ? || '%' OR pv.sku ILIKE '%' || ? || '%')
                ORDER BY p.nombre, pv.sku
                LIMIT 20""", filtro, filtro);
    }

    // ── Guardias ─────────────────────────────────────────────────────────

    private void validarDatos(Long varianteId, BigDecimal costo,
                              Integer tiempoEntregaDias, Integer cantidadMinima) {
        if (varianteId == null) {
            throw new IllegalArgumentException("Selecciona el producto (variante) a asociar");
        }
        Integer existe = pg.queryForObject(
                "SELECT count(*) FROM producto_variante WHERE id = ?", Integer.class, varianteId);
        if (existe == null || existe == 0) {
            throw new IllegalArgumentException("No existe la variante de producto " + varianteId);
        }
        if (costo == null || costo.signum() < 0) {
            throw new IllegalArgumentException("El costo debe ser cero o mayor");
        }
        if (tiempoEntregaDias != null && tiempoEntregaDias < 0) {
            throw new IllegalArgumentException("El tiempo de entrega no puede ser negativo");
        }
        if (cantidadMinima != null && cantidadMinima <= 0) {
            throw new IllegalArgumentException("La cantidad mínima debe ser mayor que cero");
        }
    }

    /** Solo un preferido por variante: desmarca a los demás (backstop = índice único). */
    private void desmarcarPreferido(long varianteId, Long exceptoId) {
        pg.update("""
                UPDATE producto_proveedor SET es_preferido = false
                WHERE producto_variante_id = ? AND es_preferido
                  AND id <> COALESCE(?::bigint, -1)""", varianteId, exceptoId);
    }

    private Map<String, Object> obtener(long id) {
        List<Map<String, Object>> filas = pg.queryForList(
                "SELECT * FROM producto_proveedor WHERE id = ?", id);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe la relación producto-proveedor " + id);
        }
        return filas.get(0);
    }
}
