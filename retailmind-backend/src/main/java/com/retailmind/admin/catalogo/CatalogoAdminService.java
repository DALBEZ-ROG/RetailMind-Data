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

    /**
     * Búsqueda paginada del catálogo (LIMIT/OFFSET). Es la ÚNICA forma de
     * listar productos, y la que la pantalla usa desde siempre.
     *
     * El listado completo sin paginar (`listarProductos`) se retiró el
     * 2026-08-19 con su endpoint — defecto D-04: devolvía los 6.217 productos
     * en cada llamada y no lo consumía nadie. El motivo por el que existía
     * («compatibilidad») se escribió cuando el catálogo tenía ~1.200.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buscarProductos(String q, Long marcaId, Long categoriaId,
                                               int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String filtro = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        String where = """
                WHERE (?::text IS NULL OR p.nombre ILIKE ?::text OR p.slug ILIKE ?::text
                       OR m.nombre ILIKE ?::text
                       OR EXISTS (SELECT 1 FROM producto_variante pv
                                  WHERE pv.producto_id = p.id AND pv.sku ILIKE ?::text))
                  AND (?::bigint IS NULL OR p.marca_id = ?::bigint)
                  AND (?::bigint IS NULL OR EXISTS (SELECT 1 FROM producto_categoria pc
                                                    WHERE pc.producto_id = p.id
                                                      AND pc.categoria_id = ?::bigint))""";
        Object[] filtros = { filtro, filtro, filtro, filtro, filtro,
                             marcaId, marcaId, categoriaId, categoriaId };

        Long total = pg.queryForObject(
                "SELECT count(*) FROM producto p LEFT JOIN marca m ON m.id = p.marca_id " + where,
                Long.class, filtros);

        Object[] args = new Object[filtros.length + 2];
        System.arraycopy(filtros, 0, args, 0, filtros.length);
        args[filtros.length] = limit;
        args[filtros.length + 1] = offset;
        // El proveedor NO cuelga del producto sino de la VARIANTE
        // (`producto_proveedor` es (proveedor, variante)), así que un producto
        // puede tener varios y hay que agregarlos: 6.043 de los 6.217 productos
        // tienen al menos uno. El DISTINCT va en la subconsulta interna y no en
        // el `string_agg`, porque `DISTINCT` y `ORDER BY` dentro de un agregado
        // exigen ordenar por la misma expresión que se distingue, y así el
        // orden es explícito. Corre solo para las filas de la página (25), y
        // se apoya en `idx_producto_variante_producto` y en
        // `idx_producto_proveedor_variante`.
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT p.id, p.nombre, p.slug, p.descripcion_corta, p.publicado, p.activo,
                       m.nombre AS marca,
                       (SELECT count(*) FROM producto_variante pv WHERE pv.producto_id = p.id) AS variantes,
                       COALESCE((SELECT string_agg(x.razon_social, ', ' ORDER BY x.razon_social)
                                 FROM (SELECT DISTINCT pr.razon_social
                                       FROM producto_variante pv2
                                       JOIN producto_proveedor pp
                                            ON pp.producto_variante_id = pv2.id AND pp.activo
                                       JOIN proveedor pr ON pr.id = pp.proveedor_id
                                       WHERE pv2.producto_id = p.id) x), '') AS proveedores
                FROM producto p LEFT JOIN marca m ON m.id = p.marca_id
                """ + where + """

                ORDER BY p.nombre
                LIMIT ? OFFSET ?""", args);

        return Map.of("items", items, "total", total == null ? 0 : total,
                      "page", Math.max(page, 0), "size", limit);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerProducto(long id) {
        Map<String, Object> producto = pg.queryForMap("""
                SELECT p.id, p.nombre, p.slug, p.descripcion_corta, p.descripcion,
                       p.publicado, p.destacado, p.activo, p.marca_id, m.nombre AS marca
                FROM producto p LEFT JOIN marca m ON m.id = p.marca_id
                WHERE p.id = ?""", id);
        producto.put("variantes", pg.queryForList("""
                SELECT pv.id, pv.sku, pv.precio, pv.costo, pv.peso_kg,
                       pv.es_predeterminada, pv.activo,
                       -- Proveedor de la variante: el PREFERIDO si lo hay
                       -- (`uq_producto_proveedor_preferido` garantiza que sea
                       -- uno solo) y, si no, el más barato. `proveedores` dice
                       -- cuántos hay para que la pantalla no dé a entender que
                       -- el que muestra es el único.
                       (SELECT pr.razon_social
                        FROM producto_proveedor pp
                        JOIN proveedor pr ON pr.id = pp.proveedor_id
                        WHERE pp.producto_variante_id = pv.id AND pp.activo
                        ORDER BY pp.es_preferido DESC, pp.costo, pr.razon_social
                        LIMIT 1) AS proveedor,
                       (SELECT count(*) FROM producto_proveedor pp
                        WHERE pp.producto_variante_id = pv.id AND pp.activo) AS proveedores,
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

    /**
     * `peso_kg` es OBLIGATORIO en el alta, y esa exigencia es de la aplicación y
     * no del motor: la columna es NULLABLE en PostgreSQL y lo seguirá siendo
     * (hay 1.221 variantes históricas que se poblaron con el script 54).
     *
     * El motivo es que un peso ausente NO degrada solo esa variante: sale caro en
     * el pedido COMPLETO. `VentasService.pesoTotalPedido` es TODO-O-NADA por
     * diseño —un total parcial distorsionaría el costo por kg en silencio—, así
     * que UNA línea sin peso deja el peso del pedido entero en NULL y el envío se
     * cobra solo con `costo_base`, **sin el cargo por kilo**. Es decir: una
     * variante mal dada de alta subfactura el flete de todos los pedidos que la
     * incluyan, sin un error en ningún log.
     *
     * Hasta el 2026-08-17 el campo NO EXISTÍA en este servicio ni en el
     * `VarianteReq` del controlador, de modo que **toda** variante creada desde la
     * pantalla nacía sin peso. Se descubrió porque las tres que se crearon
     * probando (ids 2427-2429) tumbaron la carga de `dim_producto` en el DWH
     * —NULL contra una columna `Decimal` no-Nullable— y no porque nadie notara el
     * flete mal cobrado, que es el daño de verdad.
     */
    @Transactional
    public long crearVariante(long productoId, String sku, BigDecimal precio, BigDecimal costo,
                              String codigoBarras, boolean esPredeterminada, BigDecimal pesoKg) {
        return idDe(pg.queryForObject("""
                INSERT INTO producto_variante
                    (producto_id, sku, precio, costo, codigo_barras, es_predeterminada, peso_kg)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, productoId, sku, precioValidado(precio), costo, codigoBarras,
                esPredeterminada, pesoExigido(pesoKg)));
    }

    /**
     * En la edición `pesoKg` es OPCIONAL y omitirlo CONSERVA el que hubiera
     * (`COALESCE(?, peso_kg)`), en vez de borrarlo. Dos razones:
     *
     *  * este método ya era una actualización parcial —solo tocaba sku, precio y
     *    costo de las quince columnas de la tabla—, así que conservar es lo
     *    coherente con lo que la pantalla espera;
     *  * y así la pantalla puede RELLENAR el peso de las variantes que hoy lo
     *    tienen en NULL sin necesidad de un script de migración.
     *
     * Lo que no se puede es ponerlo a cero o en negativo: eso lo rechaza
     * `pesoValidado`. Para «no lo sé» ya está el NULL que la columna admite; un 0
     * sería una afirmación —«no pesa»— y además `pesoTotalPedido` lo trata igual
     * que un NULL (`peso_kg <= 0`), con lo que reintroduciría el mismo fallo por
     * la puerta de atrás.
     */
    @Transactional
    public void editarVariante(long id, String sku, BigDecimal precio, BigDecimal costo,
                               BigDecimal pesoKg) {
        exigir(pg.update("""
                UPDATE producto_variante
                SET sku = ?, precio = ?, costo = ?, peso_kg = COALESCE(?, peso_kg)
                WHERE id = ?""",
                sku, precioValidado(precio), costo, pesoValidado(pesoKg), id),
                "producto_variante", id);
    }

    /**
     * `precio` tiene que ser ESTRICTAMENTE positivo, tanto al crear como al editar.
     *
     * El motor NO lo impide: el CHECK es `producto_variante_precio_check
     * CHECK (precio >= 0)`, o sea **el cero es legal para PostgreSQL**, y hasta el
     * 2026-08-17 este servicio no lo miraba. La pantalla sí
     * (`variante-dialog.component.ts`, `precio > 0`), con lo que el hueco solo era
     * alcanzable por API — que es exactamente la clase de hueco que no se ve.
     *
     * Lo que un precio 0 rompe, y no es el catálogo: `dim_producto` calcula
     * `margen_catalogo_pct` con `ROUND(((precio − costo) / NULLIF(precio, 0)) * 100, 2)`.
     * Con precio 0 ese `NULLIF` devuelve NULL, y un NULL contra una columna
     * `Decimal(6,2)` **no-Nullable** del almacén **aborta la carga entera** con
     * `InvalidOperation: [ConversionSyntax]` — un mensaje que no nombra la tabla, ni
     * la fila, ni la columna. Es el MISMO fallo que `peso_kg` provocó el 2026-08-16
     * (ficha C-19 de `DEUDA_TECNICA.md`, corrección C6.4 de
     * `docs/estrategico/CORRECCIONES_DISENO_ETL.md`), una columna más allá.
     *
     * O sea: esta guarda de dos líneas no protege un importe, protege la
     * publicación de la dimensión contra la que resuelven `fact_venta_linea`,
     * `fact_compra_linea`, `fact_resena` y `fact_devolucion_linea`.
     *
     * `costo` NO se valida aquí a propósito: un costo 0 es legítimo (muestra,
     * obsequio) y no entra en ningún denominador. Que el costo supere al precio
     * también se admite —margen negativo, que es un remate real— y hoy pasa en 5
     * variantes.
     */
    private static BigDecimal precioValidado(BigDecimal precio) {
        if (precio == null) {
            throw new IllegalArgumentException("El precio es obligatorio.");
        }
        if (precio.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El precio debe ser mayor que cero (llegó " + precio.toPlainString()
                    + "). Sin un precio positivo el margen de catálogo queda indefinido y la "
                    + "carga del almacén no puede publicar la dimensión de producto.");
        }
        return precio;
    }

    /** Alta: el peso es obligatorio. Ver el javadoc de `crearVariante`. */
    private static BigDecimal pesoExigido(BigDecimal pesoKg) {
        if (pesoKg == null) {
            throw new IllegalArgumentException(
                    "El peso en kg es obligatorio: sin él, el costo de envío de todo pedido "
                    + "que incluya esta variante se calcula sin el cargo por kilo.");
        }
        return pesoValidado(pesoKg);
    }

    /** Edición: si viene, tiene que ser positivo; si no viene, se conserva. */
    private static BigDecimal pesoValidado(BigDecimal pesoKg) {
        if (pesoKg != null && pesoKg.signum() <= 0) {
            throw new IllegalArgumentException(
                    "El peso en kg debe ser mayor que cero (llegó " + pesoKg.toPlainString()
                    + "). Un peso de 0 no se distingue de «sin peso» al calcular el envío.");
        }
        return pesoKg;
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
