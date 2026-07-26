package com.retailmind.informes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DEL DEPARTAMENTO DE INVENTARIO / BODEGA — los siete
 * objetivos SIMPLES del catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §5):
 *
 * <ul>
 *   <li><b>OTD-INV-01</b> {@link #bajoMinimo} — productos por debajo de su mínimo.</li>
 *   <li><b>OTD-INV-02</b> {@link #stockPorBodega} — existencias por variante y bodega.</li>
 *   <li><b>OTD-INV-03</b> {@link #kardex} — historial de entradas y salidas de un producto.</li>
 *   <li><b>OTD-INV-05</b> {@link #ajustes} — mermas y sobrantes detectados en los ajustes.</li>
 *   <li><b>OTD-INV-06</b> {@link #transferencias} — traslados entre bodegas y su estado.</li>
 *   <li><b>OTD-INV-07</b> {@link #valorInventario} — dinero parado en mercancía (CON MONTO).</li>
 *   <li><b>OTD-INV-08</b> {@link #sobreStock} — existencias por encima del tope máximo.</li>
 * </ul>
 *
 * Los COMPUESTOS de Inventario (rotación por categoría en el tiempo — OTD-INV-04,
 * evolución mensual del valor almacenado — OTD-INV-09, acumulado de mermas —
 * OTD-INV-10) NO viven aquí: pertenecen a la fase ETL → ClickHouse.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * SEGREGACIÓN FINANCIERA — la particularidad de este departamento: seis de los
 * siete informes son de CANTIDADES y BODEGA es su destinataria natural. El
 * séptimo, {@link #valorInventario}, es dinero y lo cierra SecurityConfig a
 * ADMIN/GERENTE/ANALISTA. Aquí el corte NO lo respalda el motor: grp_bodega
 * conserva SELECT sobre {@code producto_variante.costo} por la excepción
 * declarada del script 41 (valoriza el kardex bajo su propio rol al recibir
 * mercancía y al reingresar un RMA). Por eso este informe se separa en su
 * PROPIO endpoint en vez de esconder columnas: la ruta es el control.
 *
 * Consecuencia de diseño en los seis de cantidades: NO se une {@code categoria}
 * ni {@code producto_categoria} — grp_bodega no tiene SELECT sobre esas tablas
 * y el join haría fallar el informe con 42501 → 403 justo para su destinataria
 * principal. La categoría solo aparece en el informe de valorización.
 */
@Service
public class InformesInventarioService extends InformeServiceBase {

    /** Espeja tipo_movimiento.codigo (9 tipos en uso). Filtro de INV-03. */
    private static final Set<String> TIPOS_MOVIMIENTO = Set.of(
            "entrada_compra", "entrada_ajuste", "entrada_transferencia",
            "entrada_devolucion_cliente", "entrada_reposicion_proveedor",
            "salida_venta", "salida_ajuste", "salida_transferencia",
            "salida_devolucion_proveedor");

    /** Espeja tipo_movimiento.naturaleza. Filtro grueso de INV-03. */
    private static final Set<String> NATURALEZAS = Set.of(
            "entrada", "salida", "ajuste", "transferencia");

    /** Espeja ajuste_inventario.tipo (CHECK de la tabla). Filtro de INV-05. */
    private static final Set<String> TIPOS_AJUSTE = Set.of("positivo", "negativo", "conteo");

    /** Espeja ajuste_inventario.estado. 'borrador' existe en el CHECK pero no tiene flujo. */
    private static final Set<String> ESTADOS_AJUSTE = Set.of("aplicado", "anulado", "borrador");

    /** Espeja transferencia_bodega.estado. Filtro de INV-06. */
    private static final Set<String> ESTADOS_TRANSFERENCIA = Set.of(
            "pendiente", "en_transito", "recibida", "cancelada");

    public InformesInventarioService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-01 — Productos bajo mínimo (lista de reposición)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Variantes cuyo stock cayó por debajo del mínimo definido para esa bodega,
     * con el faltante que hay que reponer.
     *
     * Precisión del dato: {@code stock_minimo} tiene default 0, y 0 significa
     * «sin mínimo definido», no «mínimo cero». Por eso la condición exige
     * {@code stock_minimo > 0}: sin ese filtro TODA variante con stock 0 y sin
     * mínimo capturado entraría al informe como si hubiera que reponerla.
     *
     * Filtros: bodega (código) y búsqueda por SKU o nombre de producto.
     * Paginado (162 filas bajo mínimo hoy sobre 1.406 de inventario).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> bajoMinimo(String bodega, String buscar, int page, int size) {
        String bod = texto(bodega);
        String q = texto(buscar);

        final String from = """
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = i.bodega_id
                WHERE i.stock_minimo > 0
                  AND i.stock_actual < i.stock_minimo
                  AND (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { bod, bod, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT i.id, pv.sku, p.nombre AS producto, b.nombre AS bodega,
                       i.stock_actual, i.stock_reservado,
                       (i.stock_actual - i.stock_reservado) AS stock_disponible,
                       i.stock_minimo,
                       (i.stock_minimo - i.stock_actual) AS faltante,
                       round(i.stock_actual * 100.0 / NULLIF(i.stock_minimo, 0), 1) AS cobertura_pct,
                       i.fecha_actualizacion
                """ + from + " ORDER BY faltante DESC, p.nombre, pv.sku",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS variantes,
                       COALESCE(sum(i.stock_minimo - i.stock_actual), 0) AS faltante,
                       count(*) FILTER (WHERE i.stock_actual = 0) AS agotadas
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Variantes a reponer", tot.get("variantes"), "numero"),
                kpi("Unidades faltantes", tot.get("faltante"), "numero"),
                kpi("Variantes agotadas", tot.get("agotadas"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-02 — Stock actual por bodega
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Existencias de cada variante en cada bodega, separando lo que está
     * apartado para pedidos ({@code stock_reservado}) de lo realmente
     * disponible para vender.
     *
     * Filtros: bodega (código), búsqueda por SKU o producto y situación
     * (con/sin existencias, o con unidades apartadas). Paginado: 1.406 filas
     * de inventario repartidas en 2 bodegas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stockPorBodega(String bodega, String buscar, String situacion,
                                              int page, int size) {
        String bod = texto(bodega);
        String q = texto(buscar);
        String sit = opcion(situacion, Set.of("con_stock", "sin_stock", "con_reserva"), "situacion");

        // Guarda NULL por parámetro también en la situación: el mismo valor va
        // dos veces y las tres ramas del CASE son constantes del código.
        final String from = """
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = i.bodega_id
                WHERE (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL
                       OR (?::varchar = 'con_stock'   AND i.stock_actual > 0)
                       OR (?::varchar = 'sin_stock'   AND i.stock_actual = 0)
                       OR (?::varchar = 'con_reserva' AND i.stock_reservado > 0))
                """;
        Object[] args = { bod, bod, q, q, q, sit, sit, sit, sit };

        Map<String, Object> res = paginar("""
                SELECT i.id, pv.sku, p.nombre AS producto, b.codigo AS bodega_codigo,
                       b.nombre AS bodega,
                       i.stock_actual, i.stock_reservado,
                       (i.stock_actual - i.stock_reservado) AS stock_disponible,
                       i.stock_minimo, i.stock_maximo, i.fecha_actualizacion
                """ + from + " ORDER BY p.nombre, pv.sku, b.nombre",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS filas,
                       COALESCE(sum(i.stock_actual), 0) AS unidades,
                       COALESCE(sum(i.stock_reservado), 0) AS reservadas,
                       COALESCE(sum(i.stock_actual - i.stock_reservado), 0) AS disponibles
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Variantes almacenadas", tot.get("filas"), "numero"),
                kpi("Unidades en existencia", tot.get("unidades"), "numero"),
                kpi("Unidades apartadas", tot.get("reservadas"), "numero"),
                kpi("Unidades disponibles", tot.get("disponibles"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-03 — Kardex de un producto
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Historial de entradas y salidas: qué se movió, cuándo, cuánto, por qué y
     * con qué saldo quedó la variante en esa bodega.
     *
     * El kardex es POR PRODUCTO: se escribe el SKU o el nombre en el buscador y
     * la tabla queda encadenada por {@code stock_anterior → stock_nuevo}. Sin
     * buscador el informe sigue sirviendo como bitácora general de bodega
     * (13.287 movimientos, paginados y ordenados por fecha), pero el saldo
     * corrido solo se lee de verdad al acotar a una variante.
     *
     * El signo lo pone {@code tipo_movimiento.factor} (±1): la tabla guarda la
     * cantidad SIEMPRE en positivo, así que {@code cantidad_con_signo} es lo
     * que de hecho sumó o restó al stock.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> kardex(String buscar, String bodega, String tipo, String naturaleza,
                                      String desde, String hasta, int page, int size) {
        String q = texto(buscar);
        String bod = texto(bodega);
        String tp = opcion(tipo, TIPOS_MOVIMIENTO, "tipo");
        String nat = opcion(naturaleza, NATURALEZAS, "naturaleza");
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        final String from = """
                FROM movimiento_inventario mi
                JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                JOIN producto_variante pv ON pv.id = mi.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = mi.bodega_id
                WHERE (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR tm.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR tm.naturaleza = ?::varchar)
                  AND (?::date    IS NULL OR mi.fecha_creacion >= ?::date)
                  AND (?::date    IS NULL OR mi.fecha_creacion <  (?::date + 1))
                """;
        Object[] args = { q, q, q, bod, bod, tp, tp, nat, nat, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT mi.id, mi.fecha_creacion, pv.sku, p.nombre AS producto,
                       b.nombre AS bodega,
                       tm.codigo AS tipo_codigo, tm.nombre AS tipo, tm.naturaleza,
                       mi.cantidad, (mi.cantidad * tm.factor) AS cantidad_con_signo,
                       mi.stock_anterior, mi.stock_nuevo,
                       mi.referencia_tipo, mi.referencia_id, mi.observacion
                """ + from + " ORDER BY mi.fecha_creacion DESC, mi.id DESC",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS movimientos,
                       COALESCE(sum(mi.cantidad) FILTER (WHERE tm.factor > 0), 0) AS entradas,
                       COALESCE(sum(mi.cantidad) FILTER (WHERE tm.factor < 0), 0) AS salidas,
                       COALESCE(sum(mi.cantidad * tm.factor), 0) AS neto
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Movimientos", tot.get("movimientos"), "numero"),
                kpi("Unidades que entraron", tot.get("entradas"), "numero"),
                kpi("Unidades que salieron", tot.get("salidas"), "numero"),
                kpi("Movimiento neto", tot.get("neto"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-05 — Ajustes de inventario (mermas y sobrantes)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ajustes con su motivo, tipo, estado y el impacto real en existencias.
     *
     * Detalle del modelo que hay que conocer para leer bien este informe:
     * {@code ajuste_inventario} es SOLO CABECERA — no existe tabla de líneas
     * (deuda técnica declarada). La variante y la cantidad viven en el KARDEX,
     * enlazadas por {@code referencia_tipo = 'ajuste_inventario'}, y el texto
     * del motivo arrastra el prefijo {@code [SKU xN]} con que lo escribe el
     * flujo operativo. De ahí que las unidades se agreguen desde
     * movimiento_inventario y no desde la cabecera.
     *
     * {@code unidades_netas} usa {@code tipo_movimiento.factor}, así que un
     * ajuste ANULADO — que lleva su movimiento original más el contramovimiento
     * de anulación — sale correctamente en 0: no movió stock en neto.
     *
     * El motivo es texto libre en la BD (lo escribe quien hace el ajuste), por
     * eso su filtro es una BÚSQUEDA y no un select cerrado: un select mentiría
     * sobre lo que la columna admite.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ajustes(String tipo, String estado, String motivo, String bodega,
                                       String desde, String hasta, int page, int size) {
        String tp = opcion(tipo, TIPOS_AJUSTE, "tipo");
        String est = opcion(estado, ESTADOS_AJUSTE, "estado");
        String mot = texto(motivo);
        String bod = texto(bodega);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        // La antigüedad se mide sobre la fecha de aplicación cuando existe: un
        // ajuste en borrador todavía no tiene fecha_aplicacion.
        final String tabla = """
                FROM ajuste_inventario a
                JOIN bodega b ON b.id = a.bodega_id
                LEFT JOIN usuario u ON u.id = a.usuario_id
                """;
        final String kardexAjuste = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS movimientos,
                           COALESCE(sum(mi.cantidad * tm.factor), 0) AS unidades_netas,
                           COALESCE(sum(mi.cantidad), 0) AS unidades_movidas,
                           string_agg(DISTINCT pv.sku, ', ') AS skus
                    FROM movimiento_inventario mi
                    JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                    JOIN producto_variante pv ON pv.id = mi.producto_variante_id
                    WHERE mi.referencia_tipo = 'ajuste_inventario'
                      AND mi.referencia_id = a.id) ag ON true
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR a.tipo   = ?::varchar)
                  AND (?::varchar IS NULL OR a.estado = ?::varchar)
                  AND (?::varchar IS NULL OR a.motivo ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::date    IS NULL
                       OR COALESCE(a.fecha_aplicacion, a.fecha_creacion) >= ?::date)
                  AND (?::date    IS NULL
                       OR COALESCE(a.fecha_aplicacion, a.fecha_creacion) < (?::date + 1))
                """;
        Object[] args = { tp, tp, est, est, mot, mot, bod, bod, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT a.id, a.tipo, a.estado, a.motivo, b.nombre AS bodega,
                       a.fecha_creacion, a.fecha_aplicacion,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '')
                           AS responsable,
                       ag.skus, ag.movimientos, ag.unidades_movidas, ag.unidades_netas
                """ + tabla + kardexAjuste + filtro
                + " ORDER BY COALESCE(a.fecha_aplicacion, a.fecha_creacion) DESC, a.id DESC",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS ajustes,
                       count(*) FILTER (WHERE a.estado = 'anulado') AS anulados,
                       COALESCE(sum(ag.unidades_netas) FILTER (WHERE ag.unidades_netas > 0), 0)
                           AS sobrantes,
                       COALESCE(-sum(ag.unidades_netas) FILTER (WHERE ag.unidades_netas < 0), 0)
                           AS mermas
                """ + tabla + kardexAjuste + filtro, args);

        return conResumen(res, List.of(
                kpi("Ajustes registrados", tot.get("ajustes"), "numero"),
                kpi("Unidades de merma", tot.get("mermas"), "numero"),
                kpi("Unidades sobrantes", tot.get("sobrantes"), "numero"),
                kpi("Ajustes anulados", tot.get("anulados"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-06 — Transferencias entre bodegas
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Traslados de mercancía entre almacenes: cuáles van en camino, cuáles ya
     * se recibieron y cuánto tardaron.
     *
     * Igual que el ajuste, {@code transferencia_bodega} es solo cabecera: las
     * unidades salen del kardex, del movimiento de SALIDA del origen
     * ({@code salida_transferencia}) para no contar dos veces el par
     * salida+entrada de una transferencia ya recibida.
     *
     * Por eso una transferencia 'pendiente' o 'cancelada' aparece con 0
     * unidades: NO movió stock — no es un vacío del informe sino el estado
     * real del proceso (solo el envío escribe kardex). La 'en_transito' sí
     * tiene su salida registrada, y el stock viaja fuera de ambas bodegas.
     *
     * El filtro de bodega mira ORIGEN o DESTINO: quien pregunta por su almacén
     * quiere ver lo que sale y lo que llega.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> transferencias(String estado, String bodega, String desde,
                                              String hasta, int page, int size) {
        String est = opcion(estado, ESTADOS_TRANSFERENCIA, "estado");
        String bod = texto(bodega);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        final String tabla = """
                FROM transferencia_bodega t
                JOIN bodega bo ON bo.id = t.bodega_origen_id
                JOIN bodega bd ON bd.id = t.bodega_destino_id
                LEFT JOIN usuario u ON u.id = t.usuario_solicita_id
                """;
        final String kardexTransferencia = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS lineas,
                           COALESCE(sum(mi.cantidad), 0) AS unidades,
                           string_agg(DISTINCT pv.sku, ', ') AS skus
                    FROM movimiento_inventario mi
                    JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                    JOIN producto_variante pv ON pv.id = mi.producto_variante_id
                    WHERE mi.referencia_tipo = 'transferencia_bodega'
                      AND mi.referencia_id = t.id
                      AND tm.codigo = 'salida_transferencia') ag ON true
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR t.estado = ?::varchar)
                  AND (?::varchar IS NULL OR bo.codigo = ?::varchar OR bd.codigo = ?::varchar)
                  AND (?::date    IS NULL OR t.fecha_creacion >= ?::date)
                  AND (?::date    IS NULL OR t.fecha_creacion <  (?::date + 1))
                """;
        Object[] args = { est, est, bod, bod, bod, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT t.id, t.estado, bo.nombre AS origen, bd.nombre AS destino,
                       t.fecha_creacion, t.fecha_envio, t.fecha_recepcion,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '')
                           AS solicitante,
                       ag.skus, ag.lineas, ag.unidades,
                       CASE WHEN t.fecha_recepcion IS NOT NULL AND t.fecha_envio IS NOT NULL
                                 THEN (EXTRACT(epoch FROM t.fecha_recepcion - t.fecha_envio)
                                       / 86400)::int
                            WHEN t.fecha_envio IS NOT NULL
                                 THEN (EXTRACT(epoch FROM now() - t.fecha_envio) / 86400)::int
                       END AS dias_transito,
                       t.observacion
                """ + tabla + kardexTransferencia + filtro
                + " ORDER BY t.fecha_creacion DESC, t.id DESC",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS transferencias,
                       count(*) FILTER (WHERE t.estado = 'en_transito') AS en_camino,
                       COALESCE(sum(ag.unidades) FILTER (WHERE t.estado = 'en_transito'), 0)
                           AS unidades_en_camino,
                       COALESCE(sum(ag.unidades) FILTER (WHERE t.estado = 'recibida'), 0)
                           AS unidades_recibidas
                """ + tabla + kardexTransferencia + filtro, args);

        return conResumen(res, List.of(
                kpi("Transferencias", tot.get("transferencias"), "numero"),
                kpi("En camino", tot.get("en_camino"), "numero"),
                kpi("Unidades en tránsito", tot.get("unidades_en_camino"), "numero"),
                kpi("Unidades ya recibidas", tot.get("unidades_recibidas"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-07 — Valor del inventario por categoría y bodega (CON MONTO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cuánto dinero hay parado en mercancía almacenada, agregado por categoría
     * principal del producto y por bodega.
     *
     * ÚNICO informe de Inventario con dinero: SecurityConfig lo cierra a
     * ADMIN/GERENTE/ANALISTA. Bodega y Despacho no llegan a esta ruta (ver el
     * javadoc de la clase: aquí el corte lo hace la ruta, no el motor).
     *
     * Se valoriza a COSTO VIGENTE ({@code producto_variante.costo}): el sistema
     * no almacena COGS ni histórico de costos (decisión de alcance del catálogo),
     * así que este informe responde «cuánto vale hoy lo que tengo», no «cuánto
     * me costó cuando lo compré». El valor a precio de venta va al lado para
     * leer el margen potencial que duerme en bodega.
     *
     * La categoría es la PRINCIPAL del producto ({@code producto_categoria
     * .es_principal}, poblada en las 1.214 asignaciones): sin ese filtro un
     * producto en varias categorías inflaría el total al contarse dos veces.
     *
     * Pocas filas por naturaleza (11 categorías × 2 bodegas): sin paginar.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> valorInventario(String bodega, String categoria) {
        String bod = texto(bodega);
        String cat = texto(categoria);

        final String from = """
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = i.bodega_id
                LEFT JOIN producto_categoria pc ON pc.producto_id = p.id AND pc.es_principal
                LEFT JOIN categoria c ON c.id = pc.categoria_id
                WHERE i.stock_actual > 0
                  AND (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR c.nombre ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { bod, bod, cat, cat };

        List<Map<String, Object>> items = pg.queryForList("""
                SELECT COALESCE(c.nombre, 'Sin categoría') AS categoria,
                       b.nombre AS bodega,
                       count(*) AS variantes,
                       sum(i.stock_actual) AS unidades,
                       round(sum(i.stock_actual * pv.costo), 2) AS valor_costo,
                       round(sum(i.stock_actual * pv.precio), 2) AS valor_venta,
                       round(sum(i.stock_actual * (pv.precio - pv.costo)), 2) AS margen_potencial,
                       round(sum(i.stock_actual * (pv.precio - pv.costo)) * 100
                             / NULLIF(sum(i.stock_actual * pv.precio), 0), 2) AS margen_pct
                """ + from + """
                GROUP BY COALESCE(c.nombre, 'Sin categoría'), b.nombre
                ORDER BY valor_costo DESC""", args);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT COALESCE(sum(i.stock_actual), 0) AS unidades,
                       COALESCE(round(sum(i.stock_actual * pv.costo), 2), 0) AS valor_costo,
                       COALESCE(round(sum(i.stock_actual * pv.precio), 2), 0) AS valor_venta,
                       COALESCE(round(sum(i.stock_actual * (pv.precio - pv.costo)), 2), 0)
                           AS margen_potencial
                """ + from, args);

        return conResumen(sobre(items), List.of(
                kpi("Valor a costo", tot.get("valor_costo"), "moneda"),
                kpi("Valor a precio de venta", tot.get("valor_venta"), "moneda"),
                kpi("Margen potencial", tot.get("margen_potencial"), "moneda"),
                kpi("Unidades almacenadas", tot.get("unidades"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-INV-08 — Sobre-stock
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Variantes por encima del tope máximo deseado, con el exceso de unidades
     * que está enterrando capital y espacio en bodega.
     *
     * Igual que el mínimo, {@code stock_maximo} con 0 o NULL significa «sin
     * tope definido»: sin exigir {@code stock_maximo > 0} el informe daría
     * como sobre-stock a toda variante sin tope capturado. Los topes se cargan
     * desde la pantalla de inventario (PUT /api/inventario/niveles).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> sobreStock(String bodega, String buscar, int page, int size) {
        String bod = texto(bodega);
        String q = texto(buscar);

        final String from = """
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = i.bodega_id
                WHERE i.stock_maximo > 0
                  AND i.stock_actual > i.stock_maximo
                  AND (?::varchar IS NULL OR b.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { bod, bod, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT i.id, pv.sku, p.nombre AS producto, b.nombre AS bodega,
                       i.stock_actual, i.stock_reservado, i.stock_maximo,
                       (i.stock_actual - i.stock_maximo) AS exceso,
                       round(i.stock_actual * 100.0 / NULLIF(i.stock_maximo, 0), 1) AS ocupacion_pct,
                       i.fecha_actualizacion
                """ + from + " ORDER BY exceso DESC, p.nombre, pv.sku",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS variantes,
                       COALESCE(sum(i.stock_actual - i.stock_maximo), 0) AS exceso,
                       COALESCE(round(avg(i.stock_actual * 100.0
                                          / NULLIF(i.stock_maximo, 0)), 1), 0) AS ocupacion_media
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Variantes sobre el tope", tot.get("variantes"), "numero"),
                kpi("Unidades en exceso", tot.get("exceso"), "numero"),
                kpi("Ocupación media del tope", tot.get("ocupacion_media"), "porcentaje")));
    }
}
