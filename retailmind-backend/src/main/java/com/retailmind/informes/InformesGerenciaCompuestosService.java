package com.retailmind.informes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INFORMES TÁCTICOS COMPUESTOS — GERENCIA / DIRECCIÓN. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Implementa OTD-GER-02 (balanza mensual de caja) y OTD-GER-05 (descuento
 * otorgado por cupón y período). Los otros cuatro compuestos de Gerencia
 * —GER-03, GER-07, GER-10, GER-11— llegan con sus tablas en las fases 3 y 4.
 *
 * Ninguno de estos métodos lleva {@code @Transactional}: no tocan PostgreSQL.
 */
@Service
public class InformesGerenciaCompuestosService extends InformeCompuestoServiceBase {

    private static final String TABLA_CAJA   = "fact_flujo_caja";
    private static final String TABLA_PEDIDO = "fact_pedido";

    public InformesGerenciaCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-GER-02 — Balanza mensual: entra por ventas vs sale a proveedores
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El dinero que ENTRA por cobros de cliente contra el que SALE hacia
     * proveedores, mes a mes.
     *
     * <h3>Base CAJA, y se dice en la pantalla</h3>
     * Esta versión mide el movimiento REAL de dinero: cobros efectivos contra
     * pagos efectuados, los dos lados de {@code fact_flujo_caja}. El diseño
     * (§2.6) contempla además una lectura DEVENGADA —facturado de venta contra
     * facturado de compra—, y su otra mitad, {@code fact_orden_compra}, llega
     * en la Fase 3. Hasta entonces el informe declara su base en el resumen en
     * vez de mezclar una mitad devengada con otra de caja, que daría una
     * balanza que no es ninguna de las dos.
     *
     * <h3>Por qué el saldo sale tan negativo, y no es un error</h3>
     * Los pagos a proveedor ($16,08 M) superan con mucho los cobros ($5,47 M).
     * No es un descuadre del informe: el stock sembrado equivale a unos 6,8
     * años de rotación, así que el abastecimiento del período pagó mercancía
     * que aún no se ha vendido. Es una característica declarada de los datos
     * (rebalanceo de scripts 74-78), y el informe la muestra tal cual — una
     * balanza que se maquilla no sirve para dirigir.
     *
     * <h3>Los cobros fallidos van aparte</h3>
     * Se cuentan en su propia columna y NUNCA se suman al ingreso: son dinero
     * que se intentó cobrar y no entró. Verlos al lado del cobrado es lo que
     * permite leer si un mes flojo fue por menos venta o por más rechazos.
     */
    public Map<String, Object> balanza(String desde, String hasta, int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);

        return ejecutar("OTD-GER-02", () -> {
            Filtros f = new Filtros();
            f.y("mes >= toStartOfMonth(toDate(?))", fDesde);
            f.y("mes <= toStartOfMonth(toDate(?))", fHasta);

            String sql = sqlBalanza(f.where());
            Map<String, Object> sobre = paginarCh(
                    sql, "SELECT count() FROM (" + sql + ")", f.args(), page, size);
            conResumen(sobre, kpisBalanza(f));
            return conMarcaDeAgua(sobre, TABLA_CAJA);
        });
    }

    /**
     * Un solo {@code GROUP BY mes} con agregados condicionales por sentido.
     *
     * Es la ventaja concreta de haber unificado cobros y pagos en una tabla: la
     * balanza sale sin unir nada. Con dos tablas separadas, un mes con pagos y
     * sin cobros (o al revés) exigiría un FULL JOIN y desaparecería de la serie
     * si alguien escribiera un INNER por descuido.
     */
    private static String sqlBalanza(String where) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')  AS mes,
                t_cobrado                            AS cobrado,
                n_cobros                             AS cobros,
                t_pagado                             AS pagado_proveedor,
                n_pagos                              AS pagos,
                t_cobrado - t_pagado                 AS saldo,
                round(toFloat64(t_cobrado) / nullIf(toFloat64(t_pagado), 0) * 100, 2)
                                                     AS cobertura_pct,
                n_fallidos                           AS cobros_fallidos,
                t_fallido                            AS monto_fallido,
                round(toFloat64(n_fallidos)
                      / nullIf(toFloat64(n_cobros + n_fallidos), 0) * 100, 2)
                                                     AS tasa_rechazo_pct,
                n_pagos_a_tiempo                     AS pagos_a_tiempo,
                round(toFloat64(n_pagos_a_tiempo) / nullIf(toFloat64(n_pagos), 0) * 100, 2)
                                                     AS puntualidad_pct
            FROM (
                SELECT
                    mes AS mes_dato,
                    sumIf(monto, sentido = 'ingreso' AND estado = 'completado') AS t_cobrado,
                    countIf(sentido = 'ingreso' AND estado = 'completado')      AS n_cobros,
                    sumIf(monto, sentido = 'egreso')                            AS t_pagado,
                    countIf(sentido = 'egreso')                                 AS n_pagos,
                    countIf(sentido = 'ingreso' AND estado = 'fallido')         AS n_fallidos,
                    sumIf(monto, sentido = 'ingreso' AND estado = 'fallido')    AS t_fallido,
                    countIf(sentido = 'egreso' AND a_tiempo = 1)                AS n_pagos_a_tiempo
                FROM %s.%s
                WHERE 1 %s
                GROUP BY mes
            )
            ORDER BY mes_dato DESC
            """.formatted(DWH, TABLA_CAJA, where);
    }

    private List<Map<String, Object>> kpisBalanza(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT countDistinct(mes)                                        AS n_meses,
                   sumIf(monto, sentido='ingreso' AND estado='completado')    AS t_cobrado,
                   sumIf(monto, sentido='egreso')                            AS t_pagado,
                   sumIf(monto, sentido='ingreso' AND estado='completado')
                     - sumIf(monto, sentido='egreso')                        AS t_saldo,
                   countIf(sentido='ingreso' AND estado='fallido')           AS n_fallidos,
                   sumIf(monto, sentido='ingreso' AND estado='fallido')      AS t_fallido,
                   round(toFloat64(sumIf(monto, sentido='ingreso' AND estado='completado'))
                         / nullIf(toFloat64(sumIf(monto, sentido='egreso')), 0) * 100, 2)
                                                                             AS t_cobertura
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_CAJA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Cobrado a clientes", t.get("t_cobrado"), "moneda"));
        k.add(kpi("Pagado a proveedores", t.get("t_pagado"), "moneda"));
        k.add(kpi("Saldo de caja", t.get("t_saldo"), "moneda"));
        k.add(kpi("Cobertura del pago", valorOCero(t.get("t_cobertura")), "porcentaje"));
        k.add(kpi("Cobros rechazados", t.get("n_fallidos"), "numero"));
        k.add(kpi("Monto rechazado", t.get("t_fallido"), "moneda"));
        // La base no es un adorno: sin ella, «entró $5,47 M» se confunde con
        // «se facturó $5,47 M», que son cifras distintas del mismo período.
        k.add(kpi("Base de medición", "Caja (cobros y pagos efectivos)", "texto"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-GER-05 — Descuento otorgado por cupón y período
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Qué cupones usaron los clientes y cuánto descuento costaron, mes a mes.
     *
     * <h3>Grano (mes, cupón), y por qué es COMPUESTO</h3>
     * OTD-GER-04 (SIMPLE, PostgreSQL) ya da la foto de los cupones vigentes hoy.
     * Éste es el recorrido histórico: 25 cupones canjeados a lo largo de 19
     * meses, con su costo por mes. Es el barrido temporal lo que lo saca de una
     * consulta directa, exactamente igual que el par VEN-16 / VEN-13.
     *
     * <h3>`monto_cupon` y no `monto_descuento`</h3>
     * Son dos cifras distintas y el ETL las carga por separado a propósito:
     * `monto_cupon` es lo que el canje registró ($50.727,89 en 564 usos) y
     * `monto_descuento` es lo que la cabecera del pedido acabó descontando
     * ($50.590,25 en 562). La diferencia son los pedidos legacy 20 y 21, que
     * tienen canje pero no llegaron a reflejarlo en el pedido. Este informe
     * pregunta «cuánto costó el cupón», así que mide el CANJE — y trae las dos
     * columnas para que la discrepancia sea visible en vez de quedar arbitrada
     * en silencio.
     *
     * <h3>Sobre qué venta se mide el descuento</h3>
     * `venta_asociada` es el total de los pedidos que usaron ese cupón, no la
     * venta del mes: `descuento_sobre_venta_pct` responde «de cada 100 dólares
     * que trajo este cupón, cuántos costó», que es la pregunta de rentabilidad.
     * Compararlo contra la venta total del mes daría un porcentaje diminuto y
     * sin significado.
     */
    public Map<String, Object> descuentoCupones(String desde, String hasta, String cupon,
                                                int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCupon = texto(cupon);

        return ejecutar("OTD-GER-05", () -> {
            Filtros f = new Filtros();
            f.y("codigo_cupon != ''");
            // Los cancelados quedan fuera: un cupón canjeado en un pedido que
            // se anuló no le costó nada al negocio.
            f.y("es_cancelado = 0");
            f.y("mes >= toStartOfMonth(toDate(?))", fDesde);
            f.y("mes <= toStartOfMonth(toDate(?))", fHasta);
            f.y("positionCaseInsensitive(codigo_cupon, ?) > 0", fCupon);

            String sql = sqlCupones(f.where());
            Map<String, Object> sobre = paginarCh(
                    sql, "SELECT count() FROM (" + sql + ")", f.args(), page, size);
            conResumen(sobre, kpisCupones(f));
            return conMarcaDeAgua(sobre, TABLA_PEDIDO);
        });
    }

    private static String sqlCupones(String where) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')  AS mes,
                cupon_dato                           AS cupon,
                n_usos                               AS usos,
                n_clientes                           AS clientes,
                t_cupon                              AS descuento_canjeado,
                t_descuento_pedido                   AS descuento_en_pedido,
                t_venta                              AS venta_asociada,
                round(toFloat64(t_cupon) / nullIf(toFloat64(t_venta), 0) * 100, 2)
                                                     AS descuento_sobre_venta_pct,
                round(toFloat64(t_cupon) / nullIf(n_usos, 0), 2)  AS descuento_promedio,
                round(toFloat64(t_venta) / nullIf(n_usos, 0), 2)  AS ticket_promedio,
                round(toFloat64(t_cupon)
                      / nullIf(toFloat64(sum(t_cupon) OVER (PARTITION BY mes_dato)), 0)
                      * 100, 2)                      AS participacion_mes_pct
            FROM (
                SELECT
                    mes                       AS mes_dato,
                    codigo_cupon              AS cupon_dato,
                    count()                   AS n_usos,
                    countDistinct(cliente_id) AS n_clientes,
                    sum(monto_cupon)          AS t_cupon,
                    sum(monto_descuento)      AS t_descuento_pedido,
                    sum(total)                AS t_venta
                FROM %s.%s
                WHERE 1 %s
                GROUP BY mes, codigo_cupon
            )
            ORDER BY mes_dato DESC, t_cupon DESC
            """.formatted(DWH, TABLA_PEDIDO, where);
    }

    private List<Map<String, Object>> kpisCupones(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                          AS n_usos,
                   countDistinct(codigo_cupon)      AS n_cupones,
                   countDistinct(cliente_id)        AS n_clientes,
                   countDistinct(mes)               AS n_meses,
                   sum(monto_cupon)                 AS t_cupon,
                   sum(monto_descuento)             AS t_descuento_pedido,
                   sum(total)                       AS t_venta,
                   round(toFloat64(sum(monto_cupon))
                         / nullIf(toFloat64(sum(total)), 0) * 100, 2) AS t_costo_pct
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_PEDIDO, f.where()), f.args());

        List<Map<String, Object>> lider = ch.queryForList("""
            SELECT codigo_cupon AS cupon_dato, sum(monto_cupon) AS t_cupon
            FROM %s.%s WHERE 1 %s
            GROUP BY codigo_cupon ORDER BY t_cupon DESC LIMIT 1
            """.formatted(DWH, TABLA_PEDIDO, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Canjes", t.get("n_usos"), "numero"));
        k.add(kpi("Cupones distintos", t.get("n_cupones"), "numero"));
        k.add(kpi("Clientes que canjearon", t.get("n_clientes"), "numero"));
        k.add(kpi("Meses con canjes", t.get("n_meses"), "numero"));
        k.add(kpi("Descuento canjeado", t.get("t_cupon"), "moneda"));
        k.add(kpi("Descuento en el pedido", t.get("t_descuento_pedido"), "moneda"));
        k.add(kpi("Venta con cupón", t.get("t_venta"), "moneda"));
        k.add(kpi("Costo sobre esa venta", valorOCero(t.get("t_costo_pct")), "porcentaje"));
        if (!lider.isEmpty()) {
            k.add(kpi("Cupón más costoso", lider.get(0).get("cupon_dato"), "texto"));
        }
        return k;
    }

    /** Un conjunto vacío devuelve NULL en el porcentaje; la tarjeta muestra 0. */
    private static Object valorOCero(Object v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ═════════════════════════════════════════════════════════════════════
    // FASE 4 — margen, descuento y efecto de las promociones
    // ═════════════════════════════════════════════════════════════════════

    private static final String TABLA_LINEA = "fact_venta_linea";
    private static final String TABLA_PROMO = "dim_promocion_producto";

    /**
     * La salvedad que arrastran GER-03 y GER-10, palabra por palabra.
     *
     * El sistema NO guarda histórico de costos: {@code producto_variante.costo}
     * es el vigente y el ETL lo copia tal cual a {@code fact_venta_linea}. Un
     * margen de marzo de 2025 se calcula por tanto contra el costo de HOY. Es
     * la misma salvedad ya declarada en OTD-INV-09 e INV-10, y va en el sobre
     * porque quien lee la pantalla no lee el diseño.
     */
    private static final String SALVEDAD_COSTO_VIGENTE =
            "El margen se calcula contra el costo VIGENTE de cada producto: el sistema "
            + "no guarda histórico de costos, así que la ganancia de un mes pasado se "
            + "mide con el costo de hoy. La comparación entre categorías y entre "
            + "productos es válida —todos usan la misma base—, pero la evolución "
            + "temporal del margen refleja cambios de PRECIO y de mezcla, no de costo.";

    /** Filtros de período + canal que comparten los informes de línea. */
    private Filtros filtrosLinea(String desde, String hasta, String canal) {
        Filtros f = new Filtros();
        f.y("es_cancelado = 0");
        f.y("toDate(fecha_pedido) >= toDate(?)", fecha(desde, "desde"));
        f.y("toDate(fecha_pedido) <= toDate(?)", fecha(hasta, "hasta"));
        f.y("canal = ?", opcion(canal, CANALES, "canal"));
        return f;
    }

    // ── OTD-GER-03 · Qué categorías dejan más ganancia ───────────────────

    /** Ejes de la ganancia. La categoría es la pregunta; el mes, su evolución. */
    private static final java.util.Set<String> EJES_MARGEN =
            java.util.Set.of("categoria", "mes", "marca");

    /**
     * Ganancia por categoría de producto y período.
     *
     * <h3>El grano tiene que ser la LÍNEA</h3>
     * La categoría es un atributo del producto, no del pedido: un pedido con
     * artículos de tres categorías reparte su venta entre las tres. Agregar
     * desde {@code fact_pedido} daría el total correcto y el desglose
     * imposible — misma razón que en VEN-06.
     *
     * <h3>La ganancia se mide sobre la venta NETA</h3>
     * {@code venta_neta} ya lleva descontadas las dos capas de descuento
     * (promoción por línea y cupón prorrateado) y excluido el IVA, y
     * {@code margen} = venta neta − costo. Calcular el margen sobre el bruto
     * daría una ganancia que el descuento ya se comió: en el conjunto completo,
     * $64.037,45 de diferencia.
     *
     * <h3>Pedidos cancelados fuera</h3>
     * {@code es_cancelado = 0}, igual que en VEN-06 y GER-02.
     */
    public Map<String, Object> margenCategoria(String desde, String hasta, String canal,
                                               String categoria, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "categoria" : opcion(agrupar, EJES_MARGEN, "agrupar");

        return ejecutar("OTD-GER-03", () -> {
            Filtros f = filtrosLinea(desde, hasta, canal);
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));

            String clave = switch (eje) {
                case "mes"   -> "formatDateTime(mes, '%Y-%m')";
                case "marca" -> "marca";
                default      -> "categoria";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                       AS etiqueta,
                       count()                                  AS lineas,
                       countDistinct(pedido_id)                 AS pedidos,
                       sum(cantidad)                            AS unidades,
                       countDistinct(producto_variante_id)      AS productos,
                       sum(subtotal_bruto)                      AS venta_bruta,
                       sum(descuento_total)                     AS descuentos,
                       -- `venta` y no `venta_neta`: en ClickHouse un agregado
                       -- no puede llamarse como la columna que agrega
                       -- (ILLEGAL_AGGREGATION, lección de la Fase 1).
                       sum(venta_neta)                          AS venta,
                       sum(costo_total)                         AS costo,
                       sum(margen)                              AS ganancia,
                       round(sum(margen) * 100.0
                             / nullIf(sum(venta_neta), 0), 2)   AS margen_pct,
                       round(sum(margen) / nullIf(sum(cantidad), 0), 2)
                                                                AS ganancia_por_unidad,
                       round(sum(descuento_total) * 100.0
                             / nullIf(sum(subtotal_bruto), 0), 2) AS descuento_pct
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_LINEA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "ganancia DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisMargen(f, items, eje));
            sobre.put("salvedad", SALVEDAD_COSTO_VIGENTE);
            return conMarcaDeAgua(sobre, TABLA_LINEA);
        });
    }

    private List<Map<String, Object>> kpisMargen(Filtros f, List<Map<String, Object>> items,
                                                 String eje) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                            AS lineas,
                   countDistinct(pedido_id)           AS pedidos,
                   sum(venta_neta)                    AS venta,
                   sum(costo_total)                   AS costo,
                   sum(margen)                        AS ganancia,
                   sum(descuento_total)               AS descuentos,
                   round(sum(margen) * 100.0 / nullIf(sum(venta_neta), 0), 2) AS pct
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_LINEA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Líneas de venta", t.get("lineas"), "numero"));
        k.add(kpi("Pedidos", t.get("pedidos"), "numero"));
        k.add(kpi("Venta neta", t.get("venta"), "moneda"));
        k.add(kpi("Costo", t.get("costo"), "moneda"));
        k.add(kpi("Ganancia", t.get("ganancia"), "moneda"));
        k.add(kpi("Margen sobre venta", valorOCero(t.get("pct")), "porcentaje"));
        k.add(kpi("Descuentos concedidos", t.get("descuentos"), "moneda"));
        if (!items.isEmpty() && !"mes".equals(eje)) {
            k.add(kpi("Más rentable", items.get(0).get("etiqueta"), "texto"));
        }
        return k;
    }

    // ── OTD-GER-10 · Margen producto por producto ────────────────────────

    /**
     * La ganancia real de cada producto, con buscador.
     *
     * <h3>Grano de VARIANTE, y por eso se puede unir a dim_producto</h3>
     * {@code fact_venta_linea} lleva {@code producto_variante_id}, así que el
     * enlace con la dimensión es 1:1. (Es justo lo contrario de
     * {@code fact_resena}, cuyo grano es el producto padre y donde el mismo
     * JOIN multiplica — corrección C4.4.) Aquí ni siquiera hace falta: los
     * atributos del producto ya viajan denormalizados en el hecho.
     *
     * <h3>Solo aparecen los productos CON venta en el período</h3>
     * El informe se maneja desde el hecho: 834 variantes de las 1.221 han
     * vendido alguna vez. Un producto sin ventas no tiene margen realizado que
     * mostrar —tiene margen de catálogo, que es otra cosa y la contesta
     * OTD-INV-07—, y rellenarlo con ceros lo pondría al final de un ranking de
     * rentabilidad como si vendiera con ganancia nula.
     *
     * El resumen declara cuántos productos entraron.
     */
    public Map<String, Object> margenProducto(String desde, String hasta, String canal,
                                              String categoria, String buscar,
                                              int page, int size) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));

        return ejecutar("OTD-GER-10", () -> {
            Filtros f = filtrosLinea(desde, hasta, canal);
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));
            f.y("(positionCaseInsensitive(producto_nombre, ?) > 0 "
                + "OR positionCaseInsensitive(sku, ?) > 0)", texto(buscar));

            // El filtro de búsqueda usa el MISMO valor dos veces (nombre o SKU)
            // y `Filtros.y` solo lo acumula una: se añade el duplicado aquí,
            // manteniendo el texto del usuario como parámetro y nunca en el SQL.
            Object[] args = f.args();
            if (texto(buscar) != null) {
                Object[] conBuscar = new Object[args.length + 1];
                System.arraycopy(args, 0, conBuscar, 0, args.length);
                conBuscar[args.length] = texto(buscar);
                args = conBuscar;
            }

            String sqlItems = """
                SELECT producto_nombre, any(sku) AS sku, any(categoria) AS categoria,
                       any(marca)                               AS marca,
                       sum(cantidad)                            AS unidades,
                       countDistinct(pedido_id)                 AS pedidos,
                       sum(subtotal_bruto)                      AS venta_bruta,
                       sum(descuento_total)                     AS descuentos,
                       -- `venta` y no `venta_neta`: en ClickHouse un agregado
                       -- no puede llamarse como la columna que agrega
                       -- (ILLEGAL_AGGREGATION, lección de la Fase 1).
                       sum(venta_neta)                          AS venta,
                       sum(costo_total)                         AS costo,
                       sum(margen)                              AS ganancia,
                       round(sum(margen) * 100.0
                             / nullIf(sum(venta_neta), 0), 2)   AS margen_pct,
                       round(sum(venta_neta) / nullIf(sum(cantidad), 0), 2)
                                                                AS precio_medio,
                       round(sum(margen) / nullIf(sum(cantidad), 0), 2)
                                                                AS ganancia_por_unidad,
                       countIf(tuvo_promocion = 1)              AS lineas_con_promocion
                FROM %s.%s
                WHERE 1 %s
                GROUP BY producto_nombre
                ORDER BY ganancia DESC, unidades DESC
                """.formatted(DWH, TABLA_LINEA, f.where());

            Map<String, Object> sobre = paginarCh(sqlItems,
                    "SELECT count() FROM (" + sqlItems + ")", args, page, size);
            conResumen(sobre, kpisMargenProducto(f.where(), args));
            sobre.put("salvedad", SALVEDAD_COSTO_VIGENTE
                    + " Solo aparecen los productos CON venta en el período: un producto "
                    + "sin ventas no tiene margen realizado, y ponerlo con cero lo "
                    + "dejaría al final del ranking como si vendiera sin ganancia.");
            return conMarcaDeAgua(sobre, TABLA_LINEA);
        });
    }

    private List<Map<String, Object>> kpisMargenProducto(String where, Object[] args) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT countDistinct(producto_variante_id) AS variantes,
                   countDistinct(producto_nombre)      AS productos,
                   sum(cantidad)                       AS unidades,
                   sum(venta_neta)                     AS venta,
                   sum(costo_total)                    AS costo,
                   sum(margen)                         AS ganancia,
                   round(sum(margen) * 100.0 / nullIf(sum(venta_neta), 0), 2) AS pct,
                   countIf(margen < 0)                 AS lineas_en_perdida
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_LINEA, where), args);

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Productos con venta", t.get("productos"), "numero"));
        k.add(kpi("Variantes", t.get("variantes"), "numero"));
        k.add(kpi("Unidades vendidas", t.get("unidades"), "numero"));
        k.add(kpi("Venta neta", t.get("venta"), "moneda"));
        k.add(kpi("Costo", t.get("costo"), "moneda"));
        k.add(kpi("Ganancia", t.get("ganancia"), "moneda"));
        k.add(kpi("Margen medio", valorOCero(t.get("pct")), "porcentaje"));
        k.add(kpi("Líneas vendidas en pérdida", t.get("lineas_en_perdida"), "numero"));
        return k;
    }

    // ── OTD-GER-11 · Cuánto descuento se entrega, y sobre qué ────────────

    /** Ejes del descuento total. */
    private static final java.util.Set<String> EJES_DESCUENTO =
            java.util.Set.of("mes", "producto", "categoria");

    /**
     * El descuento total entregado —promoción + cupón— por mes y por producto.
     *
     * <h3>Las dos capas se muestran SEPARADAS y sumadas</h3>
     * El sistema aplica dos descuentos distintos y por caminos distintos: la
     * PROMOCIÓN va a la línea ({@code pedido_detalle.monto_descuento}) y el
     * CUPÓN a la cabecera, prorrateado después entre las líneas de la factura.
     * El ETL los trae ya despejados en {@code descuento_promocion} y
     * {@code descuento_cupon_prorrateado}, y el informe los presenta por
     * separado porque se deciden en sitios distintos —Marketing pone la promo,
     * el cliente elige el cupón— y su total junto, que es lo que se come el
     * margen.
     *
     * <h3>El descuento arrastra su IVA</h3>
     * Cada dólar descontado le cuesta al negocio 1,15 dólares de ingreso,
     * porque el impuesto se recalcula sobre la base rebajada. El informe lo
     * declara en un KPI en vez de dejarlo implícito.
     *
     * <h3>Las 6 líneas con excepción se cuentan aparte</h3>
     * {@code excepcion_descuento = 1} marca los pedidos con descuento que
     * nunca llegaron a facturarse y de los que no hay factura de la que
     * prorratear el cupón (corrección C1.2). Van con cupón 0 y el informe dice
     * cuántas son: es un hueco conocido, no un cero real.
     */
    public Map<String, Object> descuentoTotal(String desde, String hasta, String canal,
                                              String categoria, String agrupar,
                                              int page, int size) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "mes" : opcion(agrupar, EJES_DESCUENTO, "agrupar");

        return ejecutar("OTD-GER-11", () -> {
            Filtros f = filtrosLinea(desde, hasta, canal);
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));

            String clave = switch (eje) {
                case "producto"  -> "producto_nombre";
                case "categoria" -> "categoria";
                default          -> "formatDateTime(mes, '%Y-%m')";
            };

            String sqlItems = """
                SELECT %s                                        AS etiqueta,
                       count()                                   AS lineas,
                       countDistinct(pedido_id)                  AS pedidos,
                       sum(cantidad)                             AS unidades,
                       sum(subtotal_bruto)                       AS venta_bruta,
                       -- Sin repetir el nombre de la columna agregada
                       -- (ILLEGAL_AGGREGATION): `promocion`/`cupon`/`descuento`.
                       sum(descuento_promocion)                  AS promocion,
                       sum(descuento_cupon_prorrateado)          AS cupon,
                       sum(descuento_total)                      AS descuento,
                       round(sum(descuento_total) * 100.0
                             / nullIf(sum(subtotal_bruto), 0), 2) AS descuento_pct,
                       sum(venta_neta)                           AS venta,
                       sum(margen)                               AS ganancia,
                       round(sum(margen) * 100.0
                             / nullIf(sum(venta_neta), 0), 2)    AS margen_pct,
                       countIf(tuvo_promocion = 1)               AS lineas_con_promocion,
                       countIf(descuento_cupon_prorrateado > 0)  AS lineas_con_cupon,
                       countIf(excepcion_descuento = 1)          AS excepciones
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                HAVING sum(descuento_total) > 0
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_LINEA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "descuento DESC");

            Map<String, Object> sobre = paginarCh(sqlItems,
                    "SELECT count() FROM (" + sqlItems + ")", f.args(), page, size);
            conResumen(sobre, kpisDescuento(f));
            return conMarcaDeAgua(sobre, TABLA_LINEA);
        });
    }

    private List<Map<String, Object>> kpisDescuento(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT sum(descuento_promocion)         AS promocion,
                   sum(descuento_cupon_prorrateado) AS cupon,
                   sum(descuento_total)             AS total,
                   sum(subtotal_bruto)              AS bruto,
                   sum(venta_neta)                  AS neta,
                   sum(margen)                      AS ganancia,
                   countIf(tuvo_promocion = 1)      AS lineas_promo,
                   countIf(descuento_cupon_prorrateado > 0) AS lineas_cupon,
                   countIf(excepcion_descuento = 1) AS excepciones,
                   round(sum(descuento_total) * 100.0
                         / nullIf(sum(subtotal_bruto), 0), 2) AS pct
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_LINEA, f.where()), f.args());

        BigDecimal total = (BigDecimal) t.get("total");
        // El descuento arrastra su IVA: el ingreso cae 1,15 veces lo descontado.
        Object costeReal = total == null ? BigDecimal.ZERO
                : total.multiply(new BigDecimal("1.15"))
                       .setScale(2, java.math.RoundingMode.HALF_UP);

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Descuento total", valorOCero(total), "moneda"));
        k.add(kpi("Por promoción", t.get("promocion"), "moneda"));
        k.add(kpi("Por cupón", t.get("cupon"), "moneda"));
        k.add(kpi("Ingreso perdido (con IVA)", costeReal, "moneda"));
        k.add(kpi("Descuento sobre la venta", valorOCero(t.get("pct")), "porcentaje"));
        k.add(kpi("Líneas con promoción", t.get("lineas_promo"), "numero"));
        k.add(kpi("Líneas con cupón", t.get("lineas_cupon"), "numero"));
        k.add(kpi("Ganancia tras descuentos", t.get("ganancia"), "moneda"));
        k.add(kpi("Líneas sin factura (excepción)", t.get("excepciones"), "numero"));
        return k;
    }

    // ── OTD-GER-07 · ¿Las promociones hacen vender más? ──────────────────

    /**
     * Clasificación de una línea de venta respecto de la ventana de la
     * promoción, escrita UNA sola vez.
     *
     * Los dos fragmentos se interpolan en seis agregados distintos. Repetir la
     * comparación de fechas seis veces es la manera segura de que una de ellas
     * acabe diciendo algo distinto de las otras cinco — misma razón por la que
     * {@code fact_envio} aisló la expresión del día en una constante (C3C.1).
     *
     * <b>El guardia {@code lv.producto_id > 0} no es decorativo.</b> Con un
     * LEFT JOIN, ClickHouse rellena las filas sin pareja con el DEFECTO del
     * tipo y no con NULL (lección de la Fase 3B): una promoción sobre un
     * producto que nunca se vendió recibiría una fila fantasma con
     * {@code fecha_pedido = 1970-01-01}, que es anterior a cualquier ventana y
     * se contaría como una venta del «antes». La línea base saldría inflada en
     * exactamente un caso por cada producto sin ventas, y el efecto de la
     * promoción, hundido.
     */
    private static final String LINEA_DURANTE =
            "lv.producto_id > 0 AND lv.fecha_pedido >= pp.fecha_inicio "
            + "AND lv.fecha_pedido <= ifNull(pp.fecha_fin, pp.fecha_inicio)";

    /**
     * El «antes» son las ventas ANTERIORES al inicio de la ventana, no todas
     * las de fuera: incluir también las posteriores mezclaría el efecto de la
     * promoción con su resaca, que es otra pregunta.
     */
    private static final String LINEA_ANTES =
            "lv.producto_id > 0 AND lv.fecha_pedido < pp.fecha_inicio";

    /** La muestra, declarada en la pantalla junto al número. */
    private static final String SALVEDAD_MUESTRA_GER07 =
            "MUESTRA DÉBIL — léela antes que los porcentajes. Hay 24 promociones sobre "
            + "232 productos, pero solo unas 195 líneas de venta caen dentro de alguna "
            + "ventana y ~123 llevan descuento aplicado, frente a más de 4.000 líneas de "
            + "esos mismos productos ANTES de su promoción. Muchas filas se calculan "
            + "sobre una o dos ventas: «Líneas durante» y «Líneas antes» son el "
            + "denominador y hay que mirarlas antes que la variación. La tabla se ordena "
            + "por VOLUMEN durante la promoción y no por la variación, precisamente para "
            + "no poner arriba los casos que no se sostienen. Las medias son por DÍA de "
            + "calendario: una ventana de dos semanas no se compara en totales con el "
            + "año que la precede.";

    /**
     * Ventas del producto ANTES y DURANTE su ventana de promoción.
     *
     * <h3>ESTE INFORME TIENE UNA MUESTRA DÉBIL Y LO DICE EN PANTALLA</h3>
     * El catálogo lo clasifica <i>REQUIERE VOLUMEN</i>, y no es un defecto del
     * pipeline: promociones y descuentos por línea se escriben solos desde los
     * scripts 40 y 73. Sencillamente hay poco «durante»:
     *
     * <pre>
     *   promociones ..................................    24
     *   pares promoción-producto .....................   232
     *   líneas vendidas DENTRO de alguna ventana .....  ~195
     *   de ellas, con descuento efectivamente aplicado  ~123
     *   línea base (los mismos productos, ANTES) ..... ~4.133
     * </pre>
     *
     * Un producto con dos ventas «durante» y ochenta «antes» produce una
     * variación porcentual enorme y sin significado. Por eso:
     * <ul>
     *   <li>cada fila trae {@code lineas_durante} y {@code lineas_antes} —el
     *       denominador va al lado del número, no en una nota—;</li>
     *   <li>el sobre lleva {@code salvedad} con las cifras de la muestra;</li>
     *   <li>y el orden por defecto es por VOLUMEN durante la promoción, NO por
     *       la variación. Ordenar por la variación pondría arriba justo los
     *       casos que no se sostienen, que es la manera de que un informe
     *       débil parezca un hallazgo.</li>
     * </ul>
     *
     * <h3>Cómo se decide que una línea cayó «dentro»</h3>
     * {@code dim_promocion_producto} es un puente CON VENTANA. La línea de
     * venta tiene grano de VARIANTE y el puente de PRODUCTO, así que el enlace
     * pasa por {@code dim_producto} —1:1 con la variante— para recuperar el
     * {@code producto_id}. Es la dirección correcta: una promoción sobre un
     * producto cubre todas sus variantes. Al revés —unir el puente contra una
     * dimensión de variantes por {@code producto_id}— es el fan-out de C4.4.
     *
     * <h3>Las medias son por DÍA, no totales</h3>
     * Una ventana de 15 días no se puede comparar con los 400 días anteriores
     * en totales absolutos: el «antes» ganaría siempre, y el informe diría que
     * las promociones hunden la venta. Se comparan unidades por día de
     * calendario en cada tramo.
     */
    public Map<String, Object> efectoPromociones(String buscar, String categoria,
                                                 int page, int size) {
        return ejecutar("OTD-GER-07", () -> {
            // Los filtros se aplican al resultado ya agregado (producto y
            // categoría del PUENTE), no a la línea de venta: el informe enumera
            // pares promoción-producto, y filtrar la venta dejaría filas con su
            // ventana pero sin la base contra la que compararla.
            Filtros f = new Filtros();
            f.y("positionCaseInsensitive(producto, ?) > 0", texto(buscar));
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));

            String sql = sqlEfecto(f.where());
            Map<String, Object> sobre = paginarCh(
                    sql, "SELECT count() FROM (" + sql + ")", f.args(), page, size);
            conResumen(sobre, kpisEfecto());
            sobre.put("salvedad", SALVEDAD_MUESTRA_GER07);
            return conMarcaDeAgua(sobre, TABLA_PROMO);
        });
    }

    /**
     * El agregado de GER-07.
     *
     * Se calcula en una subconsulta y se envuelve porque las tres medidas
     * derivadas —unidades por día de cada tramo y su variación— se apoyan en
     * agregados que ClickHouse no deja reutilizar como alias dentro del mismo
     * {@code SELECT} agregado.
     */
    private static String sqlEfecto(String where) {
        return """
            SELECT promocion, producto, categoria, tipo_descuento, valor_descuento,
                   inicio, fin, dias_ventana, dias_antes,
                   lineas_durante, unidades_durante, venta_durante,
                   lineas_antes, unidades_antes, venta_antes,
                   lineas_con_descuento, descuento_aplicado,
                   round(unidades_durante / dias_ventana, 3)          AS uds_dia_durante,
                   round(unidades_antes  / dias_antes, 3)             AS uds_dia_antes,
                   round((unidades_durante / dias_ventana - unidades_antes / dias_antes)
                         * 100.0 / nullIf(unidades_antes / dias_antes, 0), 2)
                                                                      AS variacion_pct
            FROM (
                SELECT pp.promocion_nombre                            AS promocion,
                       pp.producto_nombre                             AS producto,
                       pp.categoria                                   AS categoria,
                       pp.tipo_descuento                              AS tipo_descuento,
                       pp.valor                                       AS valor_descuento,
                       toDate(pp.fecha_inicio)                        AS inicio,
                       toDate(ifNull(pp.fecha_fin, pp.fecha_inicio))  AS fin,
                       greatest(dateDiff('day', pp.fecha_inicio,
                                ifNull(pp.fecha_fin, pp.fecha_inicio)), 1)
                                                                      AS dias_ventana,
                       greatest(dateDiff('day', pp.primer_dia, pp.fecha_inicio), 1)
                                                                      AS dias_antes,
                       countIf(%5$s)                                  AS lineas_durante,
                       sumIf(lv.cantidad,   %5$s)                     AS unidades_durante,
                       sumIf(lv.venta_neta, %5$s)                     AS venta_durante,
                       countIf(%6$s)                                  AS lineas_antes,
                       sumIf(lv.cantidad,   %6$s)                     AS unidades_antes,
                       sumIf(lv.venta_neta, %6$s)                     AS venta_antes,
                       countIf((%5$s) AND lv.tuvo_promocion = 1)      AS lineas_con_descuento,
                       sumIf(lv.descuento_promocion, %5$s)            AS descuento_aplicado
                FROM (
                    SELECT pr.*,
                           (SELECT min(fecha_pedido) FROM %1$s.%2$s) AS primer_dia
                    FROM (SELECT * FROM %1$s.%3$s FINAL) pr
                ) pp
                LEFT JOIN (
                    SELECT d.producto_id AS producto_id, l.fecha_pedido, l.cantidad,
                           l.venta_neta, l.descuento_promocion, l.tuvo_promocion
                    FROM %1$s.%2$s l
                    INNER JOIN (SELECT producto_variante_id, producto_id
                                  FROM %1$s.dim_producto FINAL) d
                            ON d.producto_variante_id = l.producto_variante_id
                    WHERE l.es_cancelado = 0
                ) lv ON lv.producto_id = pp.producto_id
                GROUP BY promocion, producto, categoria, tipo_descuento,
                         valor_descuento, inicio, fin, dias_ventana, dias_antes
            )
            WHERE 1 %4$s
            ORDER BY unidades_durante DESC, lineas_durante DESC, producto
            """.formatted(DWH, TABLA_LINEA, TABLA_PROMO, where,
                    LINEA_DURANTE, LINEA_ANTES);
    }

    /**
     * El resumen de GER-07 es, sobre todo, el tamaño de la muestra.
     *
     * Las tarjetas dicen PRIMERO cuántas líneas sostienen la comparación y solo
     * después el efecto agregado. En un informe con esta base, el orden de
     * lectura importa tanto como los números.
     */
    private List<Map<String, Object>> kpisEfecto() {
        Map<String, Object> p = ch.queryForMap("""
            SELECT count() AS pares, countDistinct(promocion_id) AS promociones,
                   countDistinct(producto_id) AS productos
            FROM %s.%s FINAL
            """.formatted(DWH, TABLA_PROMO));

        // El «durante» real, contado sobre la VENTA y no sobre el puente: una
        // línea puede caer en la ventana de dos promociones del mismo producto,
        // así que se cuenta DISTINTA para no inflar la muestra que se declara.
        Map<String, Object> v = ch.queryForMap("""
            SELECT countDistinct(l.pedido_detalle_id)                       AS lineas_durante,
                   countDistinctIf(l.pedido_detalle_id, l.tuvo_promocion = 1)
                                                                            AS con_descuento,
                   sum(l.cantidad)                                          AS unidades,
                   sumIf(l.descuento_promocion, l.tuvo_promocion = 1)       AS descuento
            FROM %1$s.%2$s l
            INNER JOIN (SELECT producto_variante_id, producto_id
                          FROM %1$s.dim_producto FINAL) d
                    ON d.producto_variante_id = l.producto_variante_id
            INNER JOIN (SELECT producto_id, fecha_inicio, fecha_fin
                          FROM %1$s.%3$s FINAL) pp
                    ON pp.producto_id = d.producto_id
            WHERE l.es_cancelado = 0
              AND l.fecha_pedido >= pp.fecha_inicio
              AND l.fecha_pedido <= ifNull(pp.fecha_fin, pp.fecha_inicio)
            """.formatted(DWH, TABLA_LINEA, TABLA_PROMO));

        Map<String, Object> b = ch.queryForMap("""
            SELECT countDistinct(l.pedido_detalle_id) AS lineas_antes
            FROM %1$s.%2$s l
            INNER JOIN (SELECT producto_variante_id, producto_id
                          FROM %1$s.dim_producto FINAL) d
                    ON d.producto_variante_id = l.producto_variante_id
            INNER JOIN (SELECT producto_id, min(fecha_inicio) AS inicio
                          FROM %1$s.%3$s FINAL GROUP BY producto_id) pp
                    ON pp.producto_id = d.producto_id
            WHERE l.es_cancelado = 0 AND l.fecha_pedido < pp.inicio
            """.formatted(DWH, TABLA_LINEA, TABLA_PROMO));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Líneas DURANTE la promoción", v.get("lineas_durante"), "numero"));
        k.add(kpi("De ellas, con descuento", v.get("con_descuento"), "numero"));
        k.add(kpi("Líneas de la base ANTES", b.get("lineas_antes"), "numero"));
        k.add(kpi("Promociones", p.get("promociones"), "numero"));
        k.add(kpi("Productos en promoción", p.get("productos"), "numero"));
        k.add(kpi("Pares promoción-producto", p.get("pares"), "numero"));
        k.add(kpi("Unidades en ventana", v.get("unidades"), "numero"));
        k.add(kpi("Descuento aplicado", valorOCero(v.get("descuento")), "moneda"));
        return k;
    }
}

