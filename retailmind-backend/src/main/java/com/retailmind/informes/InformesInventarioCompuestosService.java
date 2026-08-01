package com.retailmind.informes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INFORMES TÁCTICOS COMPUESTOS — INVENTARIO / BODEGA. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Los TRES objetivos compuestos del departamento, servidos por las dos tablas
 * de la Fase 3B del pipeline:
 *
 * <pre>
 *   OTD-INV-04  /rotacion               fact_stock_mensual + fact_movimiento_inventario
 *   OTD-INV-09  /capital-inmovilizado   fact_stock_mensual
 *   OTD-INV-10  /mermas                 fact_movimiento_inventario (+ dim_producto)
 * </pre>
 *
 * Ninguno lleva {@code @Transactional}: no tocan PostgreSQL.
 *
 * <h2>Las tres cosas que hay que saber antes de tocar este archivo</h2>
 *
 * <ol>
 *   <li><b>INV-10 filtra por {@code es_ajuste_real}, NUNCA por
 *       {@code naturaleza = 'ajuste'}</b> (corrección C3B.1 de la bitácora).
 *       La apertura de inventario se registró como {@code entrada_ajuste}: son
 *       343 movimientos y 34.210 unidades que NO son sobrantes. Filtrar por
 *       naturaleza multiplica el sobrante real por 380 y da un informe que se
 *       lee perfectamente y miente.</li>
 *
 *   <li><b>INV-09 valoriza a COSTO VIGENTE y lo declara en pantalla</b> (§8.3
 *       del diseño). No existe costo histórico en el sistema, así que el
 *       informe responde «cuántas unidades había cada mes, valoradas a precio
 *       de hoy» — una serie de volumen a moneda constante, que NO es «cuánto
 *       valía la bodega aquel mes». El sobre viaja con {@code salvedad} y la
 *       pantalla genérica la pinta: presentarlo como valor histórico sería
 *       falso, y decirlo solo en la documentación no cuenta.</li>
 *
 *   <li><b>El corte financiero lo hace la RUTA y la CONSULTA, no el motor.</b>
 *       ClickHouse no tiene GRANT por columna (§8.2), y {@code fact_stock_mensual}
 *       sí tiene {@code valor_cierre}. INV-04 no selecciona ni un importe, por
 *       eso BODEGA entra; INV-09 es dinero de principio a fin y BODEGA queda
 *       fuera en {@code SecurityConfig}; INV-10 es MIXTO — todos ven las
 *       cantidades y solo GERENTE/ADMIN reciben las columnas de valor, decidido
 *       aquí con {@link #puedeVerDinero()} sobre el rol del JWT, que es el mismo
 *       mecanismo role-aware de {@code VentasService.colaPreparacion}.</li>
 * </ol>
 */
@Service
public class InformesInventarioCompuestosService extends InformeCompuestoServiceBase {

    private static final String TABLA_STOCK = "fact_stock_mensual";
    private static final String TABLA_KARDEX = "fact_movimiento_inventario";

    /** Códigos de bodega — los MISMOS que aceptan los informes simples. */
    private static final Set<String> BODEGAS = Set.of("bod-01", "bod-02");

    /** `ajuste_inventario.tipo`: los tres valores que usa el sistema. */
    private static final Set<String> TIPOS_AJUSTE = Set.of("negativo", "positivo", "conteo");

    /** `ajuste_inventario.estado`. El borrador no genera kardex (deuda conocida). */
    private static final Set<String> ESTADOS_AJUSTE = Set.of("aplicado", "anulado");

    /**
     * La salvedad de §8.3, palabra por palabra, en el sobre de todo informe que
     * valorice inventario. Vive como constante porque la comparten INV-09 y el
     * modo financiero de INV-10: dos redacciones distintas de la misma
     * limitación acabarían diciendo cosas distintas.
     */
    private static final String SALVEDAD_COSTO_VIGENTE =
            "Valorización a COSTO VIGENTE, no histórico. El sistema no guarda el costo "
            + "que tenía cada producto en el pasado, así que todos los meses se valoran "
            + "con el costo de hoy. Esta serie responde «cuántas unidades había cada mes, "
            + "valoradas a precio de hoy» — es volumen a moneda constante, y NO es «cuánto "
            + "valía la bodega aquel mes». Para comparar meses entre sí es incluso más "
            + "limpia, porque aísla el efecto del volumen del efecto del precio.";

    /**
     * El movimiento que cuenta como rotación. Una transferencia entre bodegas
     * NO rota: sale de una y entra en otra, y contarla inflaría la rotación de
     * la categoría sin que se haya vendido una sola unidad. Un ajuste tampoco.
     */
    private static final String SALIDA_QUE_ROTA = "salida_venta";

    public InformesInventarioCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /** GERENTE y ADMIN ven importes; el resto de destinatarios, cantidades. */
    private static boolean puedeVerDinero() {
        String rol = rolActual();
        return "ADMIN".equals(rol) || "GERENTE".equals(rol);
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-INV-04 — Rotación por categoría y período
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Qué categorías rotan y cuáles se quedan paradas.
     *
     * <h3>Numerador y denominador, declarados</h3>
     * <pre>
     *   rotacion_veces = unidades vendidas en el período / stock promedio del período
     *   dias_cobertura = días del período · stock promedio / unidades vendidas
     * </pre>
     *
     * El <b>numerador</b> son las salidas por VENTA y solo esas
     * ({@code salida_venta}). Es una decisión, no un descuido: contar también
     * las transferencias haría rotar a una categoría que solo cambió de estante
     * —la unidad sale de una bodega y entra en otra—, y contar los ajustes haría
     * rotar a la que se rompió. La columna {@code unidades_salida_total} viaja
     * al lado para que la diferencia sea visible y no haya que creerse esta nota.
     *
     * El <b>denominador</b> es el stock promedio MENSUAL, no el stock de hoy:
     * se suma el cierre de la categoría en cada mes del período y se promedia
     * sobre los meses. Usar el stock final haría que una categoría liquidada
     * pareciera rotar infinito.
     *
     * <h3>Las categorías paradas tienen que APARECER</h3>
     * La mitad de la pregunta del objetivo es «cuáles se quedan paradas», así
     * que la base es {@code fact_stock_mensual} —toda categoría con stock en el
     * período— y las ventas entran por {@code LEFT JOIN}. Una categoría con
     * stock y cero ventas sale con {@code rotacion_veces = 0}, que es
     * exactamente la fila que el gerente busca. Partir de los movimientos la
     * habría hecho desaparecer del informe.
     *
     * <h3>Ni una columna de dinero</h3>
     * BODEGA es destinataria (catálogo §5) y el corte financiero la deja fuera
     * de todo importe. {@code fact_stock_mensual} SÍ tiene {@code valor_cierre},
     * así que la barrera la hace esta CONSULTA, que no lo selecciona — mismo
     * mecanismo que OTD-COM-08 y OTD-LOG-12.
     */
    public Map<String, Object> rotacion(String desde, String hasta, String bodega,
                                        String categoria) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fBodega = opcion(bodega, BODEGAS, "bodega");
        String fCategoria = texto(categoria);

        return ejecutar("OTD-INV-04", () -> {
            // Los dos lados se filtran por separado —uno por `mes`, el otro por
            // `fecha`— pero con los MISMOS valores, así que el filtro se
            // construye dos veces con los mismos argumentos y se concatenan.
            Filtros stock = new Filtros();
            stock.y("toDate(mes) >= toStartOfMonth(toDate(?))", fDesde);
            stock.y("toDate(mes) <= toStartOfMonth(toDate(?))", fHasta);
            stock.y("upper(bodega_codigo) = upper(?)", fBodega);
            stock.y("categoria = ?", fCategoria);

            Filtros kardex = new Filtros();
            kardex.y("toDate(fecha) >= toDate(?)", fDesde);
            kardex.y("toDate(fecha) <= toDate(?)", fHasta);
            kardex.y("upper(bodega_codigo) = upper(?)", fBodega);
            kardex.y("categoria = ?", fCategoria);

            Object[] args = concatenar(stock.args(), kardex.args());

            List<Map<String, Object>> items =
                    ch.queryForList(sqlRotacion(stock.where(), kardex.where()), args);

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisRotacion(items));
            return conMarcaDeAgua(sobre, TABLA_STOCK);
        });
    }

    private static String sqlRotacion(String whereStock, String whereKardex) {
        return """
            SELECT
                s.categoria                                        AS categoria,
                s.meses                                            AS meses,
                s.posiciones                                       AS posiciones,
                s.stock_promedio                                    AS stock_promedio,
                s.stock_final                                      AS stock_final,
                v.unidades_vendidas                                AS unidades_vendidas,
                v.unidades_salida_total                            AS unidades_salida_total,
                -- Cero ventas ⇒ rotación 0. La categoría PARADA es media
                -- respuesta del objetivo y tiene que salir en la tabla.
                if(s.stock_promedio > 0,
                   round(v.unidades_vendidas / s.stock_promedio, 2), 0)  AS rotacion_veces,
                -- Días que duraría el stock al ritmo del período. Sin ventas no
                -- hay ritmo: NULL, y no un número enorme que parezca medido.
                if(v.unidades_vendidas > 0,
                   round(s.dias * s.stock_promedio / v.unidades_vendidas, 1), NULL)
                                                                   AS dias_cobertura,
                if(v.unidades_vendidas = 0, 1, 0)                  AS parada
            FROM (
                -- Dos niveles de agregación, y el orden importa: PRIMERO se
                -- suma el cierre de la categoría dentro de cada mes, y DESPUÉS
                -- se promedian los meses. Al revés —un `avg` directo sobre las
                -- filas— se promediarían posiciones y no meses, y una categoría
                -- con muchas variantes pequeñas saldría con un stock promedio
                -- ridículo.
                SELECT categoria,
                       count()                                      AS meses,
                       max(posiciones_mes)                          AS posiciones,
                       round(avg(unidades_mes), 2)                  AS stock_promedio,
                       argMax(unidades_mes, mes)                    AS stock_final,
                       greatest(dateDiff('day', min(mes),
                                addMonths(max(mes), 1)), 1)         AS dias
                FROM (
                    SELECT categoria, mes,
                           sum(toInt64(stock_cierre))               AS unidades_mes,
                           uniqExact(producto_variante_id)          AS posiciones_mes
                    FROM %1$s.%2$s
                    WHERE 1 %3$s
                    GROUP BY categoria, mes
                )
                GROUP BY categoria
            ) s
            LEFT JOIN (
                SELECT categoria,
                       sumIf(cantidad, tipo_movimiento = '%5$s')     AS unidades_vendidas,
                       sumIf(cantidad, cantidad_con_signo < 0)       AS unidades_salida_total
                FROM %1$s.%4$s
                WHERE 1 %6$s
                GROUP BY categoria
            ) v ON v.categoria = s.categoria
            ORDER BY rotacion_veces DESC, s.stock_promedio DESC
            """.formatted(DWH, TABLA_STOCK, whereStock, TABLA_KARDEX,
                          SALIDA_QUE_ROTA, whereKardex);
    }

    private static List<Map<String, Object>> kpisRotacion(List<Map<String, Object>> items) {
        double vendidas = 0;
        double stock = 0;
        int paradas = 0;
        String masRapida = null;
        double mejor = -1;

        for (Map<String, Object> f : items) {
            vendidas += num(f.get("unidades_vendidas"));
            stock += num(f.get("stock_promedio"));
            if (num(f.get("parada")) == 1) {
                paradas++;
            }
            double r = num(f.get("rotacion_veces"));
            if (r > mejor) {
                mejor = r;
                masRapida = String.valueOf(f.get("categoria"));
            }
        }

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Categorías con stock", items.size(), "numero"));
        k.add(kpi("Unidades vendidas", (long) vendidas, "numero"));
        k.add(kpi("Stock promedio (uds)", Math.round(stock * 100.0) / 100.0, "numero"));
        // La rotación global NO es el promedio de las rotaciones por categoría:
        // eso daría el mismo peso a una categoría de 12 unidades que a una de
        // 40.000. Se recalcula sobre los totales.
        k.add(kpi("Rotación global (veces)",
                stock > 0 ? Math.round(vendidas / stock * 100.0) / 100.0 : 0, "numero"));
        k.add(kpi("Categorías paradas", paradas, "numero"));
        if (masRapida != null && mejor > 0) {
            k.add(kpi("Rota más rápido", masRapida, "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-INV-09 — Evolución mensual del capital inmovilizado
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Cómo evoluciona mes a mes el dinero inmovilizado en la mercancía.
     *
     * <h3>La salvedad NO es opcional</h3>
     * Ver {@link #SALVEDAD_COSTO_VIGENTE} y el punto 2 de la cabecera de la
     * clase. El sobre la lleva SIEMPRE en {@code salvedad}, la pantalla la pinta
     * sobre la tabla, y además viaja un KPI que nombra la base de valoración.
     * Un informe de «capital inmovilizado» que no aclara que valora el pasado a
     * precio de hoy invita a leerlo como contabilidad, que es justo lo que no es.
     *
     * <h3>La variación es contra el mes anterior DE LA SERIE</h3>
     * Se calcula con una ventana sobre el resultado ya filtrado, no contra el
     * mes calendario anterior: si el usuario filtra por bodega o categoría, la
     * comparación tiene que ser contra la misma población. La primera fila no
     * tiene variación —y no un 0 %, que se leería como «no cambió».
     *
     * <h3>Meses sin movimiento incluidos, y contados aparte</h3>
     * El 59 % de las filas del almacén son posiciones que ese mes no se
     * movieron: su saldo se arrastró. Se incluyen —sin ellas la serie tendría
     * huecos y el capital parecería desplomarse en los meses tranquilos— y el
     * informe expone {@code posiciones_sin_movimiento} para que se vea cuánta
     * parte del capital de cada mes es mercancía quieta.
     */
    public Map<String, Object> capitalInmovilizado(String desde, String hasta,
                                                   String bodega, String categoria) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fBodega = opcion(bodega, BODEGAS, "bodega");
        String fCategoria = texto(categoria);

        return ejecutar("OTD-INV-09", () -> {
            Filtros f = new Filtros();
            f.y("toDate(mes) >= toStartOfMonth(toDate(?))", fDesde);
            f.y("toDate(mes) <= toStartOfMonth(toDate(?))", fHasta);
            f.y("upper(bodega_codigo) = upper(?)", fBodega);
            f.y("categoria = ?", fCategoria);

            List<Map<String, Object>> items =
                    ch.queryForList(sqlCapital(f.where()), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisCapital(items));
            sobre.put("salvedad", SALVEDAD_COSTO_VIGENTE);
            return conMarcaDeAgua(sobre, TABLA_STOCK);
        });
    }

    private static String sqlCapital(String where) {
        return """
            SELECT
                formatDateTime(mes, '%%Y-%%m')                       AS mes,
                posiciones,
                unidades,
                valor_cierre,
                posiciones_sin_movimiento,
                unidades_entradas,
                unidades_salidas,
                -- Ventana sobre la serie YA filtrada: comparar contra el mes
                -- calendario anterior mezclaría poblaciones cuando hay filtro.
                -- El PRIMER mes de la serie no tiene variación, y eso NO es
                -- cero: `any(...) OVER (… 1 PRECEDING)` devuelve el DEFECTO del
                -- tipo cuando no hay fila anterior, así que sin esta guarda el
                -- primer mes mostraba su capital entero como si fuera el
                -- incremento del período — $8,38 M de «variación» inventados en
                -- la fila que abre el informe. `previos` cuenta filas y no
                -- valores, de modo que un mes que de verdad cerrara en 0 sigue
                -- comparándose bien.
                if(previos = 0, NULL, round(valor_cierre - anterior, 2))
                                                                    AS variacion_valor,
                -- `toFloat64` en el PORCENTAJE, y solo ahí. Dividir dos
                -- `Decimal(14,2)` en ClickHouse trunca el resultado a la escala
                -- del operando izquierdo: la variación salía en múltiplos
                -- enteros (21, 12, 2 …) y parecía un dato redondo en vez de uno
                -- truncado. El DINERO sigue en Decimal — pasar un importe por
                -- float es exactamente lo que `validar_dwh.py` prohíbe; aquí
                -- solo se convierte el ratio.
                -- (Ojo: este bloque va por `String.formatted`, así que un signo
                --  de porcentaje literal habría que escribirlo doblado.)
                if(previos = 0 OR anterior = 0, NULL,
                   round((toFloat64(valor_cierre) - toFloat64(anterior))
                         / toFloat64(anterior) * 100, 2))
                                                                    AS variacion_pct
            FROM (
                SELECT mes, posiciones, unidades, valor_cierre,
                       posiciones_sin_movimiento, unidades_entradas, unidades_salidas,
                       any(valor_cierre) OVER (ORDER BY mes
                           ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING) AS anterior,
                       count() OVER (ORDER BY mes
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS previos
                FROM (
                    SELECT mes,
                           count()                                  AS posiciones,
                           sum(toInt64(stock_cierre))               AS unidades,
                           sum(valor_cierre)                        AS valor_cierre,
                           countIf(mes_sin_movimiento = 1)          AS posiciones_sin_movimiento,
                           sum(toInt64(entradas_mes))               AS unidades_entradas,
                           sum(toInt64(salidas_mes))                AS unidades_salidas
                    FROM %1$s.%2$s
                    WHERE 1 %3$s
                    GROUP BY mes
                )
            )
            ORDER BY mes
            """.formatted(DWH, TABLA_STOCK, where);
    }

    private static List<Map<String, Object>> kpisCapital(List<Map<String, Object>> items) {
        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", items.size(), "numero"));
        if (items.isEmpty()) {
            return k;
        }
        Map<String, Object> primero = items.get(0);
        Map<String, Object> ultimo = items.get(items.size() - 1);

        double vIni = num(primero.get("valor_cierre"));
        double vFin = num(ultimo.get("valor_cierre"));

        k.add(kpi("Capital al cierre (" + ultimo.get("mes") + ")", vFin, "moneda"));
        k.add(kpi("Unidades al cierre", (long) num(ultimo.get("unidades")), "numero"));
        // Al centavo: la resta de dos `double` deja cola binaria y un importe
        // como 13641171.620000001 en una tarjeta de resumen desmerece un
        // pipeline cuyo criterio de aceptación es la igualdad exacta.
        k.add(kpi("Variación del período",
                Math.round((vFin - vIni) * 100.0) / 100.0, "moneda"));
        if (vIni > 0) {
            k.add(kpi("Variación del período (%)",
                    Math.round((vFin - vIni) / vIni * 10000.0) / 100.0, "porcentaje"));
        }

        double pico = 0;
        String mesPico = null;
        for (Map<String, Object> f : items) {
            double v = num(f.get("valor_cierre"));
            if (v > pico) {
                pico = v;
                mesPico = String.valueOf(f.get("mes"));
            }
        }
        if (mesPico != null) {
            k.add(kpi("Mes de mayor capital", mesPico, "texto"));
        }
        // El KPI que nombra la base de valoración. Redundante con `salvedad` a
        // propósito: quien solo mira las tarjetas también tiene que verlo.
        k.add(kpi("Base de valoración", "Costo vigente (no histórico)", "texto"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-INV-10 — Mermas y sobrantes por período y motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Mermas y sobrantes acumulados, por motivo.
     *
     * <h3>Aquí es donde C3B.1 se paga o se cobra</h3>
     * El filtro es {@code es_ajuste_real = 1} — la columna que el ETL precalcula
     * desde {@code referencia_tipo = 'ajuste_inventario'}—, y NO
     * {@code naturaleza = 'ajuste'}. Con el filtro «obvio» entrarían los 343
     * movimientos de apertura de inventario (34.210 uds), que se registraron
     * como {@code entrada_ajuste} y no son sobrantes de nada. Cifras reales:
     * 56 movimientos y 227 unidades ajustadas, con un neto de −47.
     *
     * <h3>El anulado se muestra y no se descuenta a mano</h3>
     * Un ajuste anulado deja DOS movimientos —el original y su
     * contramovimiento—, así que su neto es 0 por construcción. No hace falta
     * excluirlo: se muestra con su estado, y el filtro de estado permite
     * aislarlo. Restarlo a mano sería recalcular algo que el kardex ya resolvió.
     *
     * <h3>Mixto: cantidades para todos, valor solo para GERENTE/ADMIN</h3>
     * Es lo que pide el catálogo («Bodega en cantidades; valorizado solo Gerente,
     * Administrador»). La valorización usa {@code dim_producto.costo} —el
     * VIGENTE— porque los 56 movimientos de ajuste son precisamente los que no
     * traen {@code costo_unitario} en el origen (corrección C3B.3): mover y
     * ajustar no valorizan. Por eso el modo financiero arrastra la MISMA
     * salvedad que INV-09.
     */
    public Map<String, Object> mermas(String desde, String hasta, String bodega,
                                      String tipo, String estado) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fBodega = opcion(bodega, BODEGAS, "bodega");
        String fTipo = opcion(tipo, TIPOS_AJUSTE, "tipo");
        String fEstado = opcion(estado, ESTADOS_AJUSTE, "estado");
        boolean conDinero = puedeVerDinero();

        return ejecutar("OTD-INV-10", () -> {
            Filtros f = new Filtros();
            // C3B.1: la columna precalculada, no `naturaleza`.
            f.y("es_ajuste_real = 1");
            f.y("toDate(fecha) >= toDate(?)", fDesde);
            f.y("toDate(fecha) <= toDate(?)", fHasta);
            f.y("upper(bodega_codigo) = upper(?)", fBodega);
            f.y("ajuste_tipo = ?", fTipo);
            f.y("ajuste_estado = ?", fEstado);

            List<Map<String, Object>> items =
                    ch.queryForList(sqlMermas(f.where(), conDinero), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisMermas(items, conDinero));
            sobre.put("conValorizacion", conDinero);
            if (conDinero) {
                sobre.put("salvedad", SALVEDAD_COSTO_VIGENTE);
            }
            return conMarcaDeAgua(sobre, TABLA_KARDEX);
        });
    }

    /**
     * El SQL cambia con el rol, y las columnas de dinero NO se seleccionan
     * cuando no corresponde: es el mismo criterio role-aware de
     * {@code VentasService}, y aquí es la única barrera posible porque
     * ClickHouse no tiene GRANT por columna.
     */
    private static String sqlMermas(String where, boolean conDinero) {
        String columnasValor = conDinero ? """
            ,   round(sumIf(m.cantidad * d.costo, m.cantidad_con_signo < 0), 2) AS valor_merma
            ,   round(sumIf(m.cantidad * d.costo, m.cantidad_con_signo > 0), 2) AS valor_sobrante
            ,   round(sum(m.cantidad_con_signo * d.costo), 2)                   AS valor_neto
            """ : "";
        String joinCosto = conDinero ? """
            JOIN (SELECT producto_variante_id, costo FROM %1$s.dim_producto FINAL) d
              ON d.producto_variante_id = m.producto_variante_id
            """.formatted(DWH) : "";

        return """
            SELECT
                m.ajuste_tipo                                       AS tipo,
                m.ajuste_motivo                                     AS motivo,
                m.ajuste_estado                                     AS estado,
                count()                                             AS movimientos,
                uniqExact(m.producto_variante_id)                   AS productos,
                sumIf(m.cantidad, m.cantidad_con_signo < 0)         AS unidades_merma,
                sumIf(m.cantidad, m.cantidad_con_signo > 0)         AS unidades_sobrante,
                sum(m.cantidad_con_signo)                           AS unidades_netas,
                min(toDate(m.fecha))                                AS primera,
                max(toDate(m.fecha))                                AS ultima
                %3$s
            FROM %1$s.%2$s m
            %4$s
            WHERE 1 %5$s
            GROUP BY m.ajuste_tipo, m.ajuste_motivo, m.ajuste_estado
            ORDER BY unidades_merma DESC, movimientos DESC
            """.formatted(DWH, TABLA_KARDEX, columnasValor, joinCosto, where);
    }

    private static List<Map<String, Object>> kpisMermas(List<Map<String, Object>> items,
                                                        boolean conDinero) {
        long merma = 0;
        long sobrante = 0;
        long movimientos = 0;
        double valorMerma = 0;
        String peor = null;
        long peorUds = -1;

        for (Map<String, Object> f : items) {
            long m = (long) num(f.get("unidades_merma"));
            merma += m;
            sobrante += (long) num(f.get("unidades_sobrante"));
            movimientos += (long) num(f.get("movimientos"));
            if (conDinero) {
                valorMerma += num(f.get("valor_merma"));
            }
            if (m > peorUds) {
                peorUds = m;
                peor = String.valueOf(f.get("motivo"));
            }
        }

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Motivos distintos", items.size(), "numero"));
        k.add(kpi("Movimientos de ajuste", movimientos, "numero"));
        k.add(kpi("Unidades perdidas", merma, "numero"));
        k.add(kpi("Unidades sobrantes", sobrante, "numero"));
        k.add(kpi("Neto (uds)", sobrante - merma, "numero"));
        if (peor != null && peorUds > 0) {
            k.add(kpi("Motivo con más pérdida", peor, "texto"));
        }
        if (conDinero) {
            k.add(kpi("Valor perdido", Math.round(valorMerma * 100.0) / 100.0, "moneda"));
        }
        return k;
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    /** Los argumentos de dos acumuladores de filtros, en orden. */
    private static Object[] concatenar(Object[] a, Object[] b) {
        Object[] todos = new Object[a.length + b.length];
        System.arraycopy(a, 0, todos, 0, a.length);
        System.arraycopy(b, 0, todos, a.length, b.length);
        return todos;
    }

    /** Un número del sobre, tolerante al tipo con que lo devuelva el driver. */
    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
