package com.retailmind.informes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DEL DEPARTAMENTO DE COMPRAS — los cuatro objetivos SIMPLES
 * del catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §4):
 *
 * <ul>
 *   <li><b>OTD-COM-01</b> {@link #ordenes} — órdenes de compra por estado, con las
 *       que esperan aprobación de Gerencia destacadas (CON MONTO).</li>
 *   <li><b>OTD-COM-02</b> {@link #cuentasPorPagar} — cuánto le debemos a cada
 *       proveedor y qué cuotas vencieron (CON MONTO).</li>
 *   <li><b>OTD-COM-08</b> {@link #defectuosos} — pool de mercancía defectuosa y
 *       devoluciones al proveedor en curso (SOLO CANTIDADES).</li>
 *   <li><b>OTD-COM-10</b> {@link #catalogoProveedor} — qué proveedor ofrece cada
 *       producto, a qué costo y en cuántos días (CON COSTO).</li>
 *   <li><b>OTD-COM-11</b> {@link #entregasIncompletas} — quién entrega de menos:
 *       pedido contra recibido, línea a línea (MIXTO: Bodega sin montos).</li>
 * </ul>
 *
 * Los COMPUESTOS de Compras (puntualidad de pago — COM-03, gasto mensual —
 * COM-04, cumplimiento de plazo — COM-05, días de ciclo — COM-06, rechazos en
 * puerta — COM-07, monto recuperado — COM-09, evolución del costo — COM-12) NO
 * viven aquí: los sirve {@link InformesComprasCompuestosService} desde
 * ClickHouse.
 *
 * OTD-COM-11 sí está aquí, y es deliberado: el catálogo lo clasifica SIMPLE
 * porque agrega sobre la foto presente del abastecimiento sin comparar un
 * período con otro (§3 del catálogo, la regla que separa simple de compuesto).
 * Era el último objetivo SIMPLE del catálogo pendiente de construir.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * SEGREGACIÓN FINANCIERA — tres de los cuatro informes llevan dinero y se
 * cierran en SecurityConfig a ADMIN/GERENTE/COMPRAS. Ahí el corte SÍ lo respalda
 * el motor: grp_bodega no tiene SELECT sobre {@code orden_compra.total/subtotal}
 * (script 41) y ni grp_bodega ni grp_despacho tienen GRANT alguno sobre
 * {@code cuenta_por_pagar} ni sobre {@code producto_proveedor}.
 *
 * La EXCEPCIÓN es {@link #defectuosos} (OTD-COM-08), que sí incluye a BODEGA
 * porque el catálogo se lo asigna «en cantidades, sin montos». Aquí el motor NO
 * puede ser la última línea: el script 45 dio a grp_bodega y grp_compras SELECT
 * sobre {@code item_defectuoso.costo_unitario} y
 * {@code devolucion_proveedor.monto_credito} (los necesita el flujo operativo de
 * la devolución al proveedor). Por eso el informe simplemente NO SELECCIONA
 * ninguna columna de dinero: el control es la CONSULTA, igual que en OTD-INV-07
 * el control es la ruta. Queda declarado para que nadie añada el costo «porque
 * la BD lo deja».
 */
@Service
public class InformesComprasService extends InformeServiceBase {

    /**
     * Espeja {@code orden_compra_estado_check} más el valor sintético
     * {@code pendiente_aprobacion}, que agrupa los dos estados anteriores al
     * visto bueno de Gerencia ('borrador' y 'enviada'): en el esquema NO existe
     * un estado 'aprobada' — la aprobación deja la orden en 'confirmada'.
     */
    private static final Set<String> ESTADOS_ORDEN = Set.of(
            "pendiente_aprobacion", "borrador", "enviada", "confirmada",
            "recibida_parcial", "recibida", "cancelada");

    /** Espeja cuenta_por_pagar_estado_check. */
    private static final Set<String> ESTADOS_CXP = Set.of(
            "pendiente", "parcial", "pagada", "vencida");

    /** Clasificación por FECHA, calculada al vuelo (no es una columna). */
    private static final Set<String> SITUACIONES_CXP = Set.of(
            "vencida", "por_vencer", "vigente", "saldada");

    /** Espeja item_defectuoso_estado_check. */
    private static final Set<String> ESTADOS_DEFECTUOSO = Set.of(
            "pendiente", "en_devolucion", "resuelto");

    /** Espeja item_defectuoso_origen_check: de dónde salió la unidad mala. */
    private static final Set<String> ORIGENES_DEFECTUOSO = Set.of("rma", "recepcion");

    /** Filtro de OTD-COM-10 sobre la marca de proveedor preferido. */
    private static final Set<String> MARCAS_OFERTA = Set.of("preferida", "mas_barata");

    /**
     * OTD-COM-11 — qué órdenes entran en «entregas incompletas».
     *
     * NO es {@code orden_compra.estado} disfrazado: son tres LECTURAS distintas
     * de la misma columna, y la diferencia es el sentido del informe. Sin este
     * filtro, una orden cancelada (0 recibido de 25 pedidos) contaría como el
     * peor incumplimiento del proveedor cuando la canceló Compras.
     */
    private static final Set<String> ALCANCES_ENTREGA =
            Set.of("entregadas", "en_camino", "canceladas", "todas");

    /**
     * OTD-COM-11 — ejes del agregado. Deliberadamente SIN «mes»: el catálogo lo
     * clasifica SIMPLE porque agrega sobre la foto presente, y un eje temporal
     * lo convertiría en una serie, es decir en un compuesto de ClickHouse.
     */
    private static final Set<String> EJES_ENTREGA = Set.of("proveedor", "producto");

    public InformesComprasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-COM-01 — Órdenes de compra por estado (CON MONTO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Todas las órdenes de compra con su estado, proveedor, fecha y monto,
     * poniendo PRIMERO las que esperan el visto bueno de Gerencia.
     *
     * Qué significa «pendiente de aprobación»: el esquema no tiene un estado
     * 'aprobada' — {@code ComprasService.aprobarOrden} deja la orden en
     * 'confirmada', y las aprobables son las que están en 'borrador' o
     * 'enviada'. Esas son las que frenan la cadena: sin aprobación no hay
     * recepción, sin recepción completa no hay factura y sin factura no hay
     * cuenta por pagar. Por eso salen destacadas y con los días que llevan
     * esperando.
     *
     * El avance de recepción sale de {@code orden_compra_detalle
     * .cantidad/cantidad_recibida}, que es donde vive el detalle: la cabecera
     * solo guarda el estado global.
     *
     * Filtros: estado (incluido el valor sintético 'pendiente_aprobacion'),
     * proveedor (búsqueda por razón social o RUC) y rango de fecha de emisión.
     * Paginado: 865 órdenes.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ordenes(String estado, String proveedor, String desde,
                                       String hasta, int page, int size) {
        String est = opcion(estado, ESTADOS_ORDEN, "estado");
        String prov = texto(proveedor);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        final String tabla = """
                FROM orden_compra oc
                JOIN proveedor pr ON pr.id = oc.proveedor_id
                JOIN bodega b ON b.id = oc.bodega_id
                """;
        // El avance de recepción vive en el DETALLE, no en la cabecera; el
        // conteo de la paginación no lo necesita, así que va aparte.
        final String avanceRecepcion = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS lineas,
                           COALESCE(sum(dt.cantidad), 0) AS unidades_pedidas,
                           COALESCE(sum(dt.cantidad_recibida), 0) AS unidades_recibidas
                    FROM orden_compra_detalle dt
                    WHERE dt.orden_compra_id = oc.id) ag ON true
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL
                       OR (?::varchar = 'pendiente_aprobacion'
                           AND oc.estado IN ('borrador', 'enviada'))
                       OR oc.estado = ?::varchar)
                  AND (?::varchar IS NULL OR pr.razon_social ILIKE '%' || ?::varchar || '%'
                       OR pr.ruc ILIKE '%' || ?::varchar || '%')
                  AND (?::date IS NULL OR oc.fecha_emision >= ?::date)
                  AND (?::date IS NULL OR oc.fecha_emision <= ?::date)
                """;
        Object[] args = { est, est, est, prov, prov, prov, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT oc.id, oc.numero, oc.estado, pr.razon_social AS proveedor,
                       b.nombre AS bodega, oc.fecha_emision, oc.fecha_entrega_esperada,
                       (oc.estado IN ('borrador', 'enviada')) AS pendiente_aprobacion,
                       CASE WHEN oc.estado IN ('borrador', 'enviada')
                            THEN (CURRENT_DATE - oc.fecha_emision) END AS dias_esperando,
                       oc.subtotal, oc.monto_impuesto, oc.total,
                       ag.lineas, ag.unidades_pedidas, ag.unidades_recibidas,
                       round(ag.unidades_recibidas * 100.0
                             / NULLIF(ag.unidades_pedidas, 0), 1) AS recibido_pct,
                       oc.observacion
                """ + tabla + avanceRecepcion + filtro + """
                ORDER BY (oc.estado IN ('borrador', 'enviada')) DESC,
                         oc.fecha_emision DESC, oc.id DESC""",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS ordenes,
                       COALESCE(round(sum(oc.total), 2), 0) AS monto,
                       count(*) FILTER (WHERE oc.estado IN ('borrador', 'enviada'))
                           AS por_aprobar,
                       COALESCE(round(sum(oc.total)
                           FILTER (WHERE oc.estado IN ('borrador', 'enviada')), 2), 0)
                           AS monto_por_aprobar
                """ + tabla + filtro, args);

        return conResumen(res, List.of(
                kpi("Órdenes", tot.get("ordenes"), "numero"),
                kpi("Monto comprometido", tot.get("monto"), "moneda"),
                kpi("Esperan aprobación", tot.get("por_aprobar"), "numero"),
                kpi("Monto por aprobar", tot.get("monto_por_aprobar"), "moneda")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-COM-02 — Cuentas por pagar (CON MONTO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Deuda con proveedores: saldo pendiente de cada cuenta, cuándo vence y si
     * ya se pasó de fecha.
     *
     * Dos clasificaciones que NO son lo mismo y por eso son dos filtros
     * distintos:
     * <ul>
     *   <li>{@code estado} es la columna real de la tabla (pendiente, parcial,
     *       pagada, vencida) y la mantiene el flujo de pagos a proveedor.</li>
     *   <li>{@code situacion} se calcula HOY contra {@code fecha_vencimiento}:
     *       saldada (saldo 0), vencida, por vencer (≤ 7 días) o vigente. Es la
     *       que responde «qué tengo que pagar esta semana», independientemente
     *       de que el estado guardado se haya quedado atrás.</li>
     * </ul>
     *
     * Lo pagado NO se lee de pago_proveedor: es {@code monto_original −
     * saldo_pendiente}, que el flujo de pagos mantiene con el CHECK
     * {@code saldo_pendiente <= monto_original} de por medio.
     *
     * Filtros: estado, situación y proveedor. Paginado: 839 cuentas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cuentasPorPagar(String estado, String situacion, String proveedor,
                                               int page, int size) {
        String est = opcion(estado, ESTADOS_CXP, "estado");
        String sit = opcion(situacion, SITUACIONES_CXP, "situacion");
        String prov = texto(proveedor);

        // La misma expresión de situación se usa en el filtro y en el SELECT;
        // ambas son constantes del código, el valor del usuario va en args.
        final String from = """
                FROM cuenta_por_pagar c
                JOIN proveedor pr ON pr.id = c.proveedor_id
                LEFT JOIN factura_compra fc ON fc.id = c.factura_compra_id
                WHERE (?::varchar IS NULL OR c.estado = ?::varchar)
                  AND (?::varchar IS NULL OR pr.razon_social ILIKE '%' || ?::varchar || '%'
                       OR pr.ruc ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL
                       OR (?::varchar = 'saldada'    AND c.saldo_pendiente = 0)
                       OR (?::varchar = 'vencida'    AND c.saldo_pendiente > 0
                           AND c.fecha_vencimiento <  CURRENT_DATE)
                       OR (?::varchar = 'por_vencer' AND c.saldo_pendiente > 0
                           AND c.fecha_vencimiento >= CURRENT_DATE
                           AND c.fecha_vencimiento <= CURRENT_DATE + 7)
                       OR (?::varchar = 'vigente'    AND c.saldo_pendiente > 0
                           AND c.fecha_vencimiento >  CURRENT_DATE + 7))
                """;
        Object[] args = { est, est, prov, prov, prov, sit, sit, sit, sit, sit };

        Map<String, Object> res = paginar("""
                SELECT c.id, pr.razon_social AS proveedor, fc.numero_factura AS factura,
                       c.monto_original, (c.monto_original - c.saldo_pendiente) AS pagado,
                       c.saldo_pendiente, c.fecha_vencimiento, c.estado,
                       CASE WHEN c.saldo_pendiente = 0 THEN 'saldada'
                            WHEN c.fecha_vencimiento <  CURRENT_DATE THEN 'vencida'
                            WHEN c.fecha_vencimiento <= CURRENT_DATE + 7 THEN 'por_vencer'
                            ELSE 'vigente' END AS situacion,
                       (CURRENT_DATE - c.fecha_vencimiento) AS dias_vencida,
                       pr.dias_credito, fc.fecha_emision AS fecha_factura
                """ + from + """
                ORDER BY (c.saldo_pendiente > 0) DESC, c.fecha_vencimiento, c.id""",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) FILTER (WHERE c.saldo_pendiente > 0) AS abiertas,
                       COALESCE(round(sum(c.saldo_pendiente), 2), 0) AS saldo,
                       COALESCE(round(sum(c.saldo_pendiente)
                           FILTER (WHERE c.fecha_vencimiento < CURRENT_DATE), 2), 0)
                           AS saldo_vencido,
                       count(DISTINCT c.proveedor_id)
                           FILTER (WHERE c.saldo_pendiente > 0) AS proveedores
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Cuentas con saldo", tot.get("abiertas"), "numero"),
                kpi("Saldo pendiente", tot.get("saldo"), "moneda"),
                kpi("Saldo VENCIDO", tot.get("saldo_vencido"), "moneda"),
                kpi("Proveedores con deuda", tot.get("proveedores"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-COM-08 — Defectuosos y devoluciones a proveedor (SOLO CANTIDADES)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Pool de mercancía defectuosa y en qué paso va su devolución al proveedor.
     *
     * El pool {@code item_defectuoso} tiene DOS orígenes (script 45) y leerlos
     * distinto es lo que hace útil el informe:
     * <ul>
     *   <li>{@code rma}: la inspección de una devolución de cliente marcó la
     *       unidad como defectuosa. NUNCA reingresó al stock vendible.</li>
     *   <li>{@code recepcion}: se detectó al recibir del proveedor — rechazo en
     *       puerta (jamás entró) o marcado posterior de BODEGA (ahí sí salió del
     *       stock con kardex {@code salida_devolucion_proveedor}).</li>
     * </ul>
     *
     * El proveedor puede ser NULL: el RMA lo rastrea por la última orden de
     * compra de esa variante y, cuando no la encuentra, COMPRAS debe asignarlo a
     * mano antes de poder agrupar la devolución. Esas filas se muestran como
     * «(por asignar)» y tienen su propio KPI, porque son las que bloquean el
     * proceso.
     *
     * SIN COLUMNAS DE DINERO, a propósito: BODEGA es destinataria del informe y
     * el catálogo se lo da «en cantidades, sin montos». El motor no lo impide
     * (grp_bodega tiene SELECT sobre {@code item_defectuoso.costo_unitario} y
     * {@code devolucion_proveedor.monto_credito} desde el script 45), así que el
     * control es esta consulta. El monto recuperado es OTD-COM-09, un compuesto
     * que no vive aquí.
     *
     * Filtros: estado del ítem, origen, proveedor y búsqueda por SKU o producto.
     * Paginado: 38 ítems.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> defectuosos(String estado, String origen, String proveedor,
                                           String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS_DEFECTUOSO, "estado");
        String ori = opcion(origen, ORIGENES_DEFECTUOSO, "origen");
        String prov = texto(proveedor);
        String q = texto(buscar);

        final String from = """
                FROM item_defectuoso d
                JOIN producto_variante pv ON pv.id = d.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN bodega b ON b.id = d.bodega_id
                LEFT JOIN proveedor pr ON pr.id = d.proveedor_id
                LEFT JOIN devolucion_proveedor_detalle dd ON dd.item_defectuoso_id = d.id
                LEFT JOIN devolucion_proveedor dp ON dp.id = dd.devolucion_proveedor_id
                WHERE (?::varchar IS NULL OR d.estado = ?::varchar)
                  AND (?::varchar IS NULL OR d.origen = ?::varchar)
                  AND (?::varchar IS NULL OR pr.razon_social ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { est, est, ori, ori, prov, prov, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT d.id, d.fecha_creacion, pv.sku, p.nombre AS producto, d.cantidad,
                       d.origen, d.estado, b.nombre AS bodega,
                       COALESCE(pr.razon_social, '(por asignar)') AS proveedor,
                       (d.proveedor_id IS NULL) AS sin_proveedor,
                       dp.numero AS devolucion, dp.estado AS estado_devolucion,
                       dp.tipo_resolucion, dp.fecha_envio, dp.fecha_resolucion,
                       (CURRENT_DATE - d.fecha_creacion::date) AS dias_en_pool,
                       d.nota
                """ + from + " ORDER BY (d.estado = 'pendiente') DESC, d.fecha_creacion DESC, d.id DESC",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS items,
                       COALESCE(sum(d.cantidad), 0) AS unidades,
                       COALESCE(sum(d.cantidad) FILTER (WHERE d.estado = 'pendiente'), 0)
                           AS unidades_pendientes,
                       count(*) FILTER (WHERE d.proveedor_id IS NULL) AS sin_proveedor,
                       count(DISTINCT dp.id) FILTER (WHERE dp.estado IN ('registrada', 'enviada'))
                           AS devoluciones_en_curso
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Ítems en el pool", tot.get("items"), "numero"),
                kpi("Unidades defectuosas", tot.get("unidades"), "numero"),
                kpi("Unidades sin devolver", tot.get("unidades_pendientes"), "numero"),
                kpi("Sin proveedor asignado", tot.get("sin_proveedor"), "numero"),
                kpi("Devoluciones en curso", tot.get("devoluciones_en_curso"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-COM-10 — Catálogo proveedor–producto (CON COSTO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A quién le conviene comprarle cada producto: qué proveedores lo ofrecen,
     * a qué costo, en cuántos días y con qué cantidad mínima.
     *
     * {@code es_mas_barato} NO se calcula con una función de ventana sobre la
     * página: se compara contra el mínimo de TODAS las ofertas activas de esa
     * variante (subconsulta LATERAL). Si se calculara sobre el conjunto ya
     * filtrado, buscar «Adidas» marcaría como «más barata» a una oferta que deja
     * de serlo en cuanto se limpia el buscador — es decir, el informe mentiría
     * según el filtro.
     *
     * {@code costo} de la oferta se contrasta con {@code producto_variante.costo}
     * (el costo vigente del catálogo maestro): la diferencia dice si el
     * proveedor está por encima o por debajo de lo que el sistema asume al
     * valorizar. Ojo con leerlo como margen: no hay COGS almacenado (decisión de
     * alcance del catálogo, script 67).
     *
     * CON COSTO: SecurityConfig lo cierra a ADMIN/GERENTE/COMPRAS. Aquí el corte
     * SÍ lo respalda el motor — ni grp_bodega ni grp_despacho tienen GRANT
     * alguno sobre {@code producto_proveedor}.
     *
     * Filtros: proveedor, búsqueda por SKU/producto y marca de la oferta
     * (preferida del proveedor o la más barata de la variante). Paginado: 1.106
     * ofertas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> catalogoProveedor(String proveedor, String buscar, String oferta,
                                                 int page, int size) {
        String prov = texto(proveedor);
        String q = texto(buscar);
        String marca = opcion(oferta, MARCAS_OFERTA, "oferta");

        final String from = """
                FROM producto_proveedor pp
                JOIN producto_variante pv ON pv.id = pp.producto_variante_id
                JOIN producto p ON p.id = pv.producto_id
                JOIN proveedor pr ON pr.id = pp.proveedor_id
                LEFT JOIN LATERAL (
                    SELECT count(*) AS ofertas, min(x.costo) AS costo_minimo
                    FROM producto_proveedor x
                    WHERE x.producto_variante_id = pp.producto_variante_id
                      AND x.activo) ag ON true
                WHERE (?::varchar IS NULL OR pr.razon_social ILIKE '%' || ?::varchar || '%'
                       OR pr.ruc ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR pv.sku ILIKE '%' || ?::varchar || '%'
                       OR p.nombre ILIKE '%' || ?::varchar || '%'
                       OR pp.codigo_proveedor ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL
                       OR (?::varchar = 'preferida'   AND pp.es_preferido)
                       OR (?::varchar = 'mas_barata'  AND pp.activo
                           AND pp.costo = ag.costo_minimo))
                """;
        Object[] args = { prov, prov, prov, q, q, q, q, marca, marca, marca };

        Map<String, Object> res = paginar("""
                SELECT pp.id, pv.sku, p.nombre AS producto, pr.razon_social AS proveedor,
                       pp.codigo_proveedor, pp.costo, pv.costo AS costo_catalogo,
                       round((pp.costo - pv.costo) * 100.0 / NULLIF(pv.costo, 0), 1)
                           AS brecha_catalogo_pct,
                       pp.tiempo_entrega_dias, pp.cantidad_minima, pp.es_preferido,
                       (pp.activo AND pp.costo = ag.costo_minimo) AS es_mas_barato,
                       ag.ofertas, pp.activo, pp.fecha_actualizacion
                """ + from + " ORDER BY p.nombre, pv.sku, pp.costo, pp.id",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS ofertas,
                       count(DISTINCT pp.producto_variante_id) AS variantes,
                       count(DISTINCT pp.proveedor_id) AS proveedores,
                       COALESCE(round(avg(pp.tiempo_entrega_dias), 1), 0) AS plazo_medio,
                       count(*) FILTER (WHERE pp.es_preferido) AS preferidas
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Ofertas listadas", tot.get("ofertas"), "numero"),
                kpi("Variantes cubiertas", tot.get("variantes"), "numero"),
                kpi("Proveedores", tot.get("proveedores"), "numero"),
                kpi("Plazo medio de entrega", tot.get("plazo_medio"), "dias"),
                kpi("Marcadas como preferidas", tot.get("preferidas"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-COM-11 — Quién entrega incompleto (MIXTO: Bodega sin montos)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Lo que se pidió contra lo que de verdad llegó, por proveedor.
     *
     * <h3>Por qué está aquí y no entre los compuestos</h3>
     * El catálogo lo clasifica <b>SIMPLE</b> y es uno de los nueve simples que
     * agregan: totaliza la foto presente del abastecimiento sin comparar un
     * período contra otro. Por eso NO tiene eje de mes — añadirlo lo convertiría
     * en una serie temporal y lo mandaría a ClickHouse, que es justo la frontera
     * que el catálogo §3 define. Era el ÚNICO simple del catálogo que quedaba sin
     * construir.
     *
     * <h3>«Incompleto» no es lo mismo que «todavía no ha llegado»</h3>
     * Contar como entrega incompleta toda línea con
     * {@code cantidad_recibida < cantidad} da <b>259 líneas</b>, y esa cifra
     * mezcla tres situaciones que no significan lo mismo:
     *
     * <pre>
     *   recibida / recibida_parcial ... 165 líneas   el proveedor sirvió de menos
     *   confirmada / enviada .......... 41 líneas    aún viene de camino
     *   cancelada ..................... 53 líneas    se anuló: nunca hubo que servirla
     * </pre>
     *
     * Las 94 últimas suman 2.372 unidades que <b>nunca llegaron a deberse</b>
     * (corrección C3.7), y achacárselas al proveedor lo hunde en el ranking por
     * una decisión de Compras. Por eso el alcance por defecto es
     * {@code entregadas} —las órdenes que YA llegaron— y las otras dos
     * situaciones son valores explícitos del filtro, no una omisión silenciosa.
     *
     * <h3>MIXTO: Bodega entra, y sin importes</h3>
     * El catálogo se lo da a Bodega «en cantidades, sin montos». Aquí el motor NO
     * puede ser la última línea: grp_bodega conserva SELECT sobre
     * {@code orden_compra_detalle.precio_unitario} (excepción declarada del
     * script 41 — lo necesita para valorizar el kardex al recibir), de modo que
     * la BD le dejaría calcular el valor faltante. El control es la CONSULTA: con
     * Bodega, {@code valor_faltante} no se selecciona. Lo que sí respalda el
     * motor es que la consulta jamás toca {@code orden_compra.total} ni
     * {@code cuenta_por_pagar}, sobre los que Bodega no tiene privilegio.
     *
     * Verificado contra la base: 165 líneas incompletas sobre 2.855 entregadas,
     * 1.514 unidades no servidas y 11 proveedores.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> entregasIncompletas(String alcance, String agrupar,
                                                   String proveedor, String desde,
                                                   String hasta, int page, int size) {
        String alc = opcion(alcance, ALCANCES_ENTREGA, "alcance");
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES_ENTREGA, "agrupar");
        String prov = texto(proveedor);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);
        boolean conDinero = !"BODEGA".equals(rolActual());

        // Sin filtro explícito, solo lo que YA llegó: ver el javadoc.
        String estados = switch (alc == null ? "entregadas" : alc) {
            case "en_camino" -> "('confirmada', 'enviada')";
            case "canceladas" -> "('cancelada')";
            case "todas" -> null;
            default -> "('recibida', 'recibida_parcial')";
        };

        final String from = """
                FROM orden_compra_detalle d
                JOIN orden_compra oc     ON oc.id = d.orden_compra_id
                JOIN proveedor pr        ON pr.id = oc.proveedor_id
                JOIN producto_variante v ON v.id = d.producto_variante_id
                JOIN producto p          ON p.id = v.producto_id
                WHERE (?::varchar IS NULL OR pr.razon_social ILIKE '%' || ?::varchar || '%'
                       OR pr.ruc ILIKE '%' || ?::varchar || '%')
                  AND (?::date IS NULL OR oc.fecha_emision >= ?::date)
                  AND (?::date IS NULL OR oc.fecha_emision <= ?::date)
                """ + (estados == null ? "" : "  AND oc.estado IN " + estados + "\n");
        Object[] args = { prov, prov, prov, d, d, h, h };

        String clave = "producto".equals(eje) ? "v.sku, p.nombre" : "pr.razon_social";
        // OJO: va SIN bloque de texto a propósito. Un text block de Java recorta
        // el espacio final de cada línea, así que `"""SELECT """ + etiqueta`
        // produce `SELECTpr.razon_social` — sintaxis inválida, y solo revienta en
        // tiempo de ejecución.
        String etiqueta = "producto".equals(eje)
                ? "SELECT v.sku AS etiqueta, p.nombre AS detalle"
                : "SELECT pr.razon_social AS etiqueta, '' AS detalle";

        // La ÚNICA columna de dinero del informe. Bodega no la recibe.
        String columnaValor = conDinero ? """
                ,      ROUND(SUM((d.cantidad - d.cantidad_recibida) * d.precio_unitario), 2)
                           AS valor_faltante
                """ : "";

        Map<String, Object> res = paginar(etiqueta + """
                ,      count(DISTINCT oc.id)                              AS ordenes,
                       count(*)                                           AS lineas,
                       count(*) FILTER (WHERE d.cantidad_recibida < d.cantidad)
                                                                          AS lineas_incompletas,
                       SUM(d.cantidad)                                    AS uds_pedidas,
                       SUM(d.cantidad_recibida)                           AS uds_recibidas,
                       SUM(d.cantidad - d.cantidad_recibida)              AS uds_faltantes,
                       ROUND(SUM(d.cantidad_recibida) * 100.0
                             / NULLIF(SUM(d.cantidad), 0), 2)             AS pct_cumplimiento,
                       ROUND(count(*) FILTER (WHERE d.cantidad_recibida < d.cantidad) * 100.0
                             / NULLIF(count(*), 0), 2)                    AS pct_lineas_incompletas
                """ + columnaValor + from
                + " GROUP BY " + clave
                + " ORDER BY pct_cumplimiento ASC, uds_faltantes DESC",
                "SELECT count(*) FROM (SELECT 1 " + from + " GROUP BY " + clave + ") x",
                args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*)                                           AS lineas,
                       count(*) FILTER (WHERE d.cantidad_recibida < d.cantidad)
                                                                          AS incompletas,
                       count(DISTINCT oc.id)                              AS ordenes,
                       count(DISTINCT oc.id) FILTER (WHERE d.cantidad_recibida < d.cantidad)
                                                                          AS ordenes_afectadas,
                       count(DISTINCT oc.proveedor_id)                    AS proveedores,
                       COALESCE(SUM(d.cantidad), 0)                       AS pedidas,
                       COALESCE(SUM(d.cantidad_recibida), 0)              AS recibidas,
                       COALESCE(SUM(d.cantidad - d.cantidad_recibida), 0) AS faltantes,
                       COALESCE(ROUND(SUM(d.cantidad_recibida) * 100.0
                             / NULLIF(SUM(d.cantidad), 0), 2), 0)         AS pct,
                       COALESCE(ROUND(SUM((d.cantidad - d.cantidad_recibida)
                             * d.precio_unitario), 2), 0)                 AS valor
                """ + from, args);

        List<Map<String, Object>> kpis = new java.util.ArrayList<>(List.of(
                kpi("Líneas incompletas", tot.get("incompletas"), "numero"),
                kpi("Líneas en el alcance", tot.get("lineas"), "numero"),
                kpi("Unidades no servidas", tot.get("faltantes"), "numero"),
                kpi("Cumplimiento en unidades", tot.get("pct"), "porcentaje"),
                kpi("Unidades pedidas", tot.get("pedidas"), "numero"),
                kpi("Unidades recibidas", tot.get("recibidas"), "numero"),
                kpi("Órdenes con alguna falta", tot.get("ordenes_afectadas"), "numero"),
                kpi("Órdenes en el alcance", tot.get("ordenes"), "numero"),
                kpi("Proveedores", tot.get("proveedores"), "numero")));
        if (conDinero) {
            kpis.add(kpi("Valor no servido", tot.get("valor"), "moneda"));
        }
        conResumen(res, kpis);
        res.put("conValorizacion", conDinero);
        return res;
    }
}
