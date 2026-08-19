package com.retailmind.informes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DEL DEPARTAMENTO DE LOGÍSTICA / DESPACHO — los cuatro
 * objetivos del catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §7)
 * que se resuelven con una consulta directa a PostgreSQL:
 *
 * <ul>
 *   <li><b>OTD-LOG-01</b> {@link #colaDespacho} — pedidos listos y en espera de
 *       salir (SOLO ESTADOS Y CANTIDADES).</li>
 *   <li><b>OTD-LOG-02</b> {@link #envios} — envíos por estado, transportista y
 *       guía (SOLO ESTADOS Y FECHAS).</li>
 *   <li><b>OTD-LOG-06</b> {@link #devoluciones} — devoluciones de cliente en
 *       curso por paso del ciclo (SOLO CANTIDADES).</li>
 *   <li><b>OTD-LOG-11</b> {@link #costoEnvio} — costo real del envío por zona y
 *       transportista (CON DINERO).</li>
 * </ul>
 *
 * Los COMPUESTOS de Logística (entregas a tiempo — LOG-03, días de tránsito —
 * LOG-04, novedades por desenlace — LOG-05, días de ciclo de devolución —
 * LOG-07, motivos y destino de la mercancía — LOG-08, tasa de devolución
 * mensual — LOG-09, reembolsos pagados — LOG-10, tiempos por etapa — LOG-12) NO
 * viven aquí: pertenecen a la fase ETL → ClickHouse.
 *
 * NOTA DE CLASIFICACIÓN: el catálogo marca OTD-LOG-11 como COMPUESTO porque su
 * lectura natural es la EVOLUCIÓN del costo por período. Lo que se implementa
 * aquí es la foto de HOY —el costo acumulado por zona y transportista sobre los
 * envíos que cumplen el filtro, sin comparar períodos—, que cae del lado SIMPLE
 * según la regla del §2 del catálogo (sumar sobre el presente no vuelve
 * compuesto a un informe). La serie temporal sigue siendo trabajo de ClickHouse.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * SEGREGACIÓN FINANCIERA — DESPACHO es el destinatario natural de los tres
 * primeros informes y queda FUERA del cuarto:
 * <ul>
 *   <li>En LOG-01 y LOG-06 el corte lo respalda el MOTOR: grp_despacho no tiene
 *       SELECT sobre {@code pedido.total/subtotal/costo_envio} ni sobre
 *       {@code devolucion.monto_total} (scripts 41 y 38). Por eso este servicio
 *       tampoco los selecciona — pedirlos rompería el informe con 42501 → 403
 *       justo para quien más lo usa.</li>
 *   <li>En LOG-11 el corte lo hace la RUTA, no el motor: grp_despacho SÍ tiene
 *       SELECT sobre {@code envio.costo}, {@code tarifa_envio} y
 *       {@code zona_envio} porque los necesita al despachar. Por eso el informe
 *       de dinero va en su PROPIO endpoint, cerrado en SecurityConfig a
 *       ADMIN/GERENTE — mismo patrón declarado en OTD-INV-07. Queda escrito para
 *       que nadie lo mezcle con LOG-02 «ya que son la misma tabla».</li>
 * </ul>
 */
@Service
public class InformesLogisticaService extends InformeServiceBase {

    /**
     * Tramo de SALIDA del ciclo de venta (script 39): son los tres estados en
     * los que un pedido ya está pagado y facturado pero todavía no salió por la
     * puerta. Es el universo de OTD-LOG-01 y NO un filtro del usuario.
     */
    private static final Set<String> ESTADOS_COLA = Set.of(
            "facturado", "en_preparacion", "preparado");

    /** Espeja pedido.canal (el CHECK del script 36). */
    private static final Set<String> CANALES = Set.of("web", "tienda", "telefono");

    /** Espeja envio_estado_check. */
    private static final Set<String> ESTADOS_ENVIO = Set.of(
            "preparando", "listo", "en_transito", "entregado", "fallido", "devuelto");

    /** Espeja devolucion_estado_check: los 9 pasos del ciclo RMA (script 38). */
    private static final Set<String> ESTADOS_DEVOLUCION = Set.of(
            "solicitada", "en_revision", "aprobada", "rechazada", "en_transito",
            "recibida", "inspeccionada", "reembolsada", "cerrada");

    /** Espeja motivo_devolucion.codigo (4 motivos activos). */
    private static final Set<String> MOTIVOS_DEVOLUCION = Set.of(
            "arrepentimiento", "no_corresponde", "producto_danado", "talla_incorrecta");

    public InformesLogisticaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-LOG-01 — Cola de despacho (SOLO ESTADOS Y CANTIDADES)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Trabajo pendiente de salir por la puerta: pedidos ya facturados que están
     * en preparación o ya preparados, ordenados por antigüedad.
     *
     * El universo son los TRES estados del tramo de salida (facturado →
     * en_preparacion → preparado), no solo 'preparado': quien despacha necesita
     * ver también lo que le va a caer encima. Los que ya están listos —los
     * únicos que DESPACHO puede despachar de verdad, por la compuerta del
     * script 39— salen primero y se marcan con {@code listo_para_despachar}.
     *
     * SIN COLUMNAS DE DINERO: grp_despacho no tiene SELECT sobre
     * {@code pedido.total} ni {@code pedido.costo_envio} (script 41). El motor
     * respalda el corte; la consulta simplemente no los pide.
     *
     * Filtros: estado (dentro del tramo), canal, transportista asignado y
     * búsqueda por número de pedido o cliente. Paginado (48 pedidos en cola hoy).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> colaDespacho(String estado, String canal, String transportista,
                                            String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS_COLA, "estado");
        String can = opcion(canal, CANALES, "canal");
        String tr = texto(transportista);
        String q = texto(buscar);

        // tabla + lateral + filtro por separado: el conteo no necesita el
        // agregado de líneas, y el LATERAL tiene que ir ANTES del WHERE.
        final String tabla = """
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN direccion d ON d.id = p.direccion_envio_id
                LEFT JOIN ciudad ci ON ci.id = d.ciudad_id
                LEFT JOIN transportista t ON t.id = p.transportista_id
                LEFT JOIN metodo_envio me ON me.id = p.metodo_envio_id
                """;
        final String lineasPedido = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS lineas, COALESCE(sum(pd.cantidad), 0) AS unidades
                    FROM pedido_detalle pd WHERE pd.pedido_id = p.id) ag ON true
                """;
        /*
         * EL TRAMO SE ACOTA POR ID, NO POR CÓDIGO, Y ESO VALE 15 SEGUNDOS.
         *
         * Estaba como `WHERE ep.codigo IN ('facturado','en_preparacion',
         * 'preparado')`: el predicado cae sobre una columna de `estado_pedido`,
         * así que `pedido` (3,0 M) se recorre entero y RLS evalúa
         * `esta_en_horario()` fila a fila. Este informe era el MÁS LENTO del
         * sistema: 15.163 ms para devolver 48 filas.
         *
         * Comparando por `p.estado_pedido_id` contra un ARRAY de ids resueltos
         * con subconsultas escalares (InitPlan, una vez), el operador es
         * `int4eq` —leakproof— y baja a `Index Cond` sobre `idx_pedido_estado`.
         * Los códigos siguen siendo la fuente de verdad: se traducen a id antes
         * de tocar `pedido`, no dentro del recorrido.
         *
         * El filtro OPCIONAL de estado se traduce igual, y se añade solo si
         * viene: la guarda `(? IS NULL OR ...)` es opaca para el índice.
         */
        final String filtro = """
                WHERE p.estado_pedido_id = ANY (ARRAY[
                        (SELECT id FROM estado_pedido WHERE codigo = 'facturado'),
                        (SELECT id FROM estado_pedido WHERE codigo = 'en_preparacion'),
                        (SELECT id FROM estado_pedido WHERE codigo = 'preparado')])
                """
                + (est == null ? ""
                   : "  AND p.estado_pedido_id = (SELECT id FROM estado_pedido"
                     + " WHERE codigo = ?)\n")
                + """
                  AND (?::varchar IS NULL OR p.canal = ?::varchar)
                  AND (?::varchar IS NULL OR t.nombre ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR p.numero ILIKE '%' || ?::varchar || '%'
                       OR c.nombre ILIKE '%' || ?::varchar || '%'
                       OR c.apellido ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = est == null
                ? new Object[] { can, can, tr, tr, q, q, q, q }
                : new Object[] { est, can, can, tr, tr, q, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                       (ep.codigo = 'preparado') AS listo_para_despachar,
                       (EXTRACT(epoch FROM now() - p.fecha_pedido) / 86400)::int AS dias_en_cola,
                       NULLIF(trim(concat(c.nombre, ' ', COALESCE(c.apellido, ''))), '')
                           AS cliente,
                       ci.nombre AS ciudad, d.calle_principal AS direccion,
                       t.nombre AS transportista, me.nombre AS metodo_envio,
                       ag.lineas, ag.unidades
                """ + tabla + lineasPedido + filtro
                + " ORDER BY (ep.codigo = 'preparado') DESC, p.fecha_pedido, p.id",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS pedidos,
                       count(*) FILTER (WHERE ep.codigo = 'preparado') AS listos,
                       COALESCE(max((EXTRACT(epoch FROM now() - p.fecha_pedido) / 86400)::int), 0)
                           AS espera_maxima,
                       count(*) FILTER (WHERE p.transportista_id IS NULL) AS sin_transportista
                """ + tabla + filtro, args);

        return conResumen(res, List.of(
                kpi("Pedidos en cola", tot.get("pedidos"), "numero"),
                kpi("Listos para despachar", tot.get("listos"), "numero"),
                kpi("Espera más larga", tot.get("espera_maxima"), "dias"),
                kpi("Sin transportista asignado", tot.get("sin_transportista"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-LOG-02 — Envíos por estado y transportista (SIN DINERO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Seguimiento de los envíos: cuáles van en camino, cuáles se entregaron,
     * cuáles volvieron, con qué transportista viajan y bajo qué número de guía.
     *
     * {@code atrasado} compara la fecha prometida contra hoy SOLO para lo que
     * sigue en tránsito: un envío ya entregado tarde es materia de OTD-LOG-03
     * (compuesto), no de este tablero, que responde «qué tengo que perseguir».
     *
     * Las novedades abiertas ({@code novedad_envio.estado = 'abierta'}, script
     * 44) se cuentan aparte porque son las que BLOQUEAN la entrega: mientras
     * haya una sin resolver, {@code VentasService.entregar} rechaza el envío.
     *
     * SIN {@code envio.costo}, aunque grp_despacho lo lea: el dinero del envío
     * es OTD-LOG-11, en su propio endpoint y sin Despacho (ver javadoc de clase).
     *
     * Filtros: estado, transportista, rango de fecha de despacho y búsqueda por
     * número de envío, guía, pedido o cliente. Paginado: 2.872 envíos.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> envios(String estado, String transportista, String desde,
                                      String hasta, String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS_ENVIO, "estado");
        String tr = texto(transportista);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        String q = texto(buscar);
        exigirRangoValido(d, h);

        final String tabla = """
                FROM envio e
                JOIN pedido p ON p.id = e.pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN transportista t ON t.id = e.transportista_id
                LEFT JOIN metodo_envio me ON me.id = e.metodo_envio_id
                """;
        final String novedades = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS novedades_abiertas
                    FROM novedad_envio n
                    WHERE n.envio_id = e.id AND n.estado = 'abierta') nv ON true
                """;
        // `envio` (2,11 M) NO tiene índice por `fecha_despacho`, y la corrección
        // gana igual: 3.773 ms → 96,8 ms. Con el predicado leakproof el motor
        // descarta por fecha primero y solo llama a esta_en_horario() sobre las
        // supervivientes, en vez de sobre los 2,11 M.
        String[] ts = instantesDelDia(d, h);
        final String filtro = """
                WHERE (?::varchar IS NULL OR e.estado = ?::varchar)
                  AND (?::varchar IS NULL OR t.nombre ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR e.numero ILIKE '%' || ?::varchar || '%'
                       OR e.numero_guia ILIKE '%' || ?::varchar || '%'
                       OR p.numero ILIKE '%' || ?::varchar || '%'
                       OR c.nombre ILIKE '%' || ?::varchar || '%'
                       OR c.apellido ILIKE '%' || ?::varchar || '%')
                """ + filtroDia("e.fecha_despacho", ts);
        Object[] args = conLimites(new Object[] { est, est, tr, tr, q, q, q, q, q, q }, ts);

        Map<String, Object> res = paginarConTope("""
                SELECT e.id, e.numero, e.estado, e.numero_guia,
                       t.nombre AS transportista, me.nombre AS metodo_envio,
                       p.numero AS pedido, p.canal,
                       NULLIF(trim(concat(c.nombre, ' ', COALESCE(c.apellido, ''))), '')
                           AS cliente,
                       e.fecha_despacho, e.fecha_entrega_estimada, e.fecha_entrega_real,
                       (e.estado = 'en_transito' AND e.fecha_entrega_estimada < CURRENT_DATE)
                           AS atrasado,
                       CASE WHEN e.fecha_entrega_real IS NOT NULL AND e.fecha_despacho IS NOT NULL
                                 THEN (EXTRACT(epoch FROM e.fecha_entrega_real - e.fecha_despacho)
                                       / 86400)::int
                            WHEN e.fecha_despacho IS NOT NULL
                                 THEN (EXTRACT(epoch FROM now() - e.fecha_despacho) / 86400)::int
                       END AS dias_transito,
                       nv.novedades_abiertas
                """ + tabla + novedades + filtro
                + " ORDER BY e.fecha_despacho DESC NULLS LAST, e.id DESC",
                tabla + filtro, args, page, size);

        // Mismo tratamiento que OTD-VEN-01 y OTD-INV-03: `envio` son 2.110.096
        // filas y la pantalla sin filtros hacía DOS recorridos completos —el
        // conteo y los cinco `count(… FILTER …)`—, 20,9 s medidos aislados.
        // Los cinco KPI no se pueden acotar (un recuento de estados sobre
        // 200.000 envíos arbitrarios de 2,1 M no significa nada), así que por
        // encima del tope no se calculan y el informe dice por qué.
        if (conteoAcotado(res)) {
            res.put("salvedad", "El filtro actual abarca más de "
                    + com.retailmind.comun.Paginacion.TOPE_CONTEO
                    + " envíos, así que el total de la cabecera es un MÍNIMO y los recuentos"
                    + " por estado no se calculan. Acota por estado, transportista o fechas"
                    + " y las cinco cifras salen exactas.");
            return conResumen(res, List.of(
                    kpi("Envíos", null, "numero"),
                    kpi("En camino", null, "numero"),
                    kpi("Atrasados", null, "numero"),
                    kpi("Entregados", null, "numero"),
                    kpi("Fallidos o devueltos", null, "numero")));
        }

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS envios,
                       count(*) FILTER (WHERE e.estado = 'en_transito') AS en_camino,
                       count(*) FILTER (WHERE e.estado = 'entregado') AS entregados,
                       count(*) FILTER (WHERE e.estado IN ('fallido', 'devuelto')) AS con_problema,
                       count(*) FILTER (WHERE e.estado = 'en_transito'
                                          AND e.fecha_entrega_estimada < CURRENT_DATE)
                           AS atrasados
                """ + tabla + filtro, args);

        return conResumen(res, List.of(
                kpi("Envíos", tot.get("envios"), "numero"),
                kpi("En camino", tot.get("en_camino"), "numero"),
                kpi("Atrasados", tot.get("atrasados"), "numero"),
                kpi("Entregados", tot.get("entregados"), "numero"),
                kpi("Fallidos o devueltos", tot.get("con_problema"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-LOG-06 — Devoluciones de cliente en curso (SOLO CANTIDADES)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ciclo RMA visto de punta a punta: en qué paso va cada devolución
     * (solicitada → en_revision → aprobada|rechazada → en_transito → recibida →
     * inspeccionada → reembolsada → cerrada), por qué motivo la pidió el cliente
     * y qué salió de la inspección.
     *
     * El desglose de la inspección viene de {@code devolucion_detalle
     * .resultado_inspeccion}, que es lo que decide el destino de la mercancía
     * (script 38): solo {@code apto_reventa} reingresa al stock,
     * {@code defectuoso} cae al pool de devolución a proveedor (OTD-COM-08) y
     * {@code rechazado} no genera reembolso. Por eso las tres cantidades van en
     * columnas separadas y no en un total que las confundiría.
     *
     * SIN {@code devolucion.monto_total} ni {@code monto_reembolsado}: BODEGA y
     * DESPACHO son destinatarios y el motor no les da esas columnas (script 38);
     * el dinero devuelto es OTD-LOG-10, compuesto y fuera de este módulo.
     *
     * Filtros: estado, motivo, rango de fecha de solicitud y búsqueda por número
     * de devolución, guía de retorno, pedido o cliente. Paginado: 196
     * devoluciones.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> devoluciones(String estado, String motivo, String desde,
                                            String hasta, String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS_DEVOLUCION, "estado");
        String mot = opcion(motivo, MOTIVOS_DEVOLUCION, "motivo");
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        String q = texto(buscar);
        exigirRangoValido(d, h);

        final String tabla = """
                FROM devolucion dv
                JOIN motivo_devolucion m ON m.id = dv.motivo_devolucion_id
                JOIN pedido p ON p.id = dv.pedido_id
                JOIN cliente c ON c.id = dv.cliente_id
                LEFT JOIN transportista t ON t.id = dv.transportista_id
                LEFT JOIN bodega b ON b.id = dv.bodega_id
                """;
        // El desglose de la inspección vive en el DETALLE: la cabecera no lo
        // resume. Mismo LATERAL para la página y para los KPIs.
        final String inspeccion = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS lineas, COALESCE(sum(dd.cantidad), 0) AS unidades,
                           COALESCE(sum(dd.cantidad)
                               FILTER (WHERE dd.resultado_inspeccion = 'apto_reventa'), 0) AS aptas,
                           COALESCE(sum(dd.cantidad)
                               FILTER (WHERE dd.resultado_inspeccion = 'defectuoso'), 0)
                               AS defectuosas,
                           COALESCE(sum(dd.cantidad)
                               FILTER (WHERE dd.resultado_inspeccion = 'rechazado'), 0)
                               AS rechazadas
                    FROM devolucion_detalle dd WHERE dd.devolucion_id = dv.id) ag ON true
                """;
        // `devolucion` (145.734) tampoco tiene índice por fecha: 1.047 → 25,4 ms.
        String[] ts = instantesDelDia(d, h);
        final String filtro = """
                WHERE (?::varchar IS NULL OR dv.estado = ?::varchar)
                  AND (?::varchar IS NULL OR m.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR dv.numero ILIKE '%' || ?::varchar || '%'
                       OR dv.guia_retorno ILIKE '%' || ?::varchar || '%'
                       OR p.numero ILIKE '%' || ?::varchar || '%'
                       OR c.nombre ILIKE '%' || ?::varchar || '%'
                       OR c.apellido ILIKE '%' || ?::varchar || '%')
                """ + filtroDia("dv.fecha_creacion", ts);
        Object[] args = conLimites(new Object[] { est, est, mot, mot, q, q, q, q, q, q }, ts);

        Map<String, Object> res = paginar("""
                SELECT dv.id, dv.numero, dv.estado, m.nombre AS motivo,
                       p.numero AS pedido,
                       NULLIF(trim(concat(c.nombre, ' ', COALESCE(c.apellido, ''))), '')
                           AS cliente,
                       dv.fecha_creacion, dv.guia_retorno,
                       t.nombre AS transportista, b.nombre AS bodega,
                       (dv.estado NOT IN ('cerrada', 'rechazada')) AS en_curso,
                       (EXTRACT(epoch FROM now() - dv.fecha_creacion) / 86400)::int AS dias_abierta,
                       ag.lineas, ag.unidades, ag.aptas, ag.defectuosas, ag.rechazadas,
                       dv.descripcion
                """ + tabla + inspeccion + filtro + """
                ORDER BY (dv.estado NOT IN ('cerrada', 'rechazada')) DESC,
                         dv.fecha_creacion DESC, dv.id DESC""",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS devoluciones,
                       count(*) FILTER (WHERE dv.estado NOT IN ('cerrada', 'rechazada'))
                           AS en_curso,
                       count(*) FILTER (WHERE dv.estado IN ('aprobada', 'en_transito', 'recibida'))
                           AS por_inspeccionar,
                       COALESCE(sum(ag.unidades), 0) AS unidades,
                       COALESCE(sum(ag.aptas), 0) AS unidades_aptas
                """ + tabla + inspeccion + filtro, args);

        return conResumen(res, List.of(
                kpi("Devoluciones", tot.get("devoluciones"), "numero"),
                kpi("En curso", tot.get("en_curso"), "numero"),
                kpi("Esperan inspección", tot.get("por_inspeccionar"), "numero"),
                kpi("Unidades devueltas", tot.get("unidades"), "numero"),
                kpi("Unidades aptas para reventa", tot.get("unidades_aptas"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-LOG-11 — Costo de envío por zona y transportista (CON DINERO)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cuánto nos cuesta llevar la mercancía, agregado por zona de destino y
     * transportista, contra lo que se le cobró al cliente.
     *
     * ÚNICO informe de Logística con dinero: SecurityConfig lo cierra a
     * ADMIN/GERENTE. DESPACHO no llega a esta ruta, y aquí el corte lo hace la
     * RUTA y no el motor — grp_despacho sí tiene SELECT sobre {@code envio.costo}
     * porque lo escribe al despachar (ver javadoc de la clase). Lo que el motor
     * sí le niega es {@code pedido.costo_envio}, la otra mitad de la comparación.
     *
     * La ZONA no es una columna del envío: se resuelve desde la dirección del
     * pedido con la MISMA cadena de especificidad que usa
     * {@code VentasService.asignarEnvioPorZona} — ciudad > provincia > país —,
     * porque una zona nacional y una local pueden cubrir a la vez la misma
     * dirección y solo la más específica es la que aplicó la tarifa. Replicar
     * ese orden es lo que hace que el informe cuadre con lo que de verdad se
     * cobró; agrupar por país daría un promedio sin sentido.
     *
     * Los envíos sin zona configurada aparecen como fila propia («sin zona»):
     * son los que salieron sin tarifa aplicable y por eso quedaron en costo
     * 0.00 — un cero visible vale más que esconderlos del total.
     *
     * Pocas filas por naturaleza (3 zonas × 5 transportistas): sin paginar.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> costoEnvio(String zona, String transportista, String desde,
                                          String hasta) {
        String z = texto(zona);
        String tr = texto(transportista);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);
        String[] ts = instantesDelDia(d, h);

        final String base = """
                WITH env AS (
                    SELECT e.id, e.costo, e.peso_total_kg, e.estado,
                           COALESCE(t.nombre, '(sin transportista)') AS transportista,
                           p.costo_envio,
                           COALESCE(z.nombre, '(sin zona configurada)') AS zona
                    FROM envio e
                    JOIN pedido p ON p.id = e.pedido_id
                    LEFT JOIN transportista t ON t.id = e.transportista_id
                    LEFT JOIN direccion di ON di.id = p.direccion_envio_id
                    LEFT JOIN ciudad ci ON ci.id = di.ciudad_id
                    LEFT JOIN provincia pv ON pv.id = ci.provincia_id
                    LEFT JOIN LATERAL (
                        SELECT z2.nombre
                        FROM zona_envio z2
                        WHERE z2.activo AND z2.pais_id = pv.pais_id
                          AND (z2.provincia_id IS NULL OR z2.provincia_id = ci.provincia_id)
                          AND (z2.ciudad_id IS NULL OR z2.ciudad_id = ci.id)
                        ORDER BY (z2.ciudad_id IS NOT NULL) DESC,
                                 (z2.provincia_id IS NOT NULL) DESC, z2.id
                        LIMIT 1) z ON true
                    WHERE (?::varchar IS NULL OR t.nombre ILIKE '%' || ?::varchar || '%')
                      AND (?::varchar IS NULL OR z.nombre ILIKE '%' || ?::varchar || '%')
                """ + filtroDia("e.fecha_despacho", ts) + """
                )
                """;
        Object[] args = conLimites(new Object[] { tr, tr, z, z }, ts);

        List<Map<String, Object>> items = pg.queryForList(base + """
                SELECT zona, transportista,
                       count(*) AS envios,
                       round(sum(costo), 2) AS costo_real,
                       round(avg(costo), 2) AS costo_promedio,
                       round(sum(costo_envio), 2) AS cobrado_al_cliente,
                       round(sum(costo_envio) - sum(costo), 2) AS diferencia,
                       count(*) FILTER (WHERE costo = 0) AS sin_costo,
                       count(*) FILTER (WHERE peso_total_kg IS NOT NULL) AS con_peso
                FROM env
                GROUP BY zona, transportista
                ORDER BY costo_real DESC, zona, transportista""", args);

        Map<String, Object> tot = pg.queryForMap(base + """
                SELECT count(*) AS envios,
                       COALESCE(round(sum(costo), 2), 0) AS costo_real,
                       COALESCE(round(sum(costo_envio), 2), 0) AS cobrado,
                       COALESCE(round(sum(costo_envio) - sum(costo), 2), 0) AS diferencia,
                       COALESCE(round(avg(costo), 2), 0) AS costo_promedio
                FROM env""", args);

        return conResumen(sobre(items), List.of(
                kpi("Costo real del envío", tot.get("costo_real"), "moneda"),
                kpi("Cobrado al cliente", tot.get("cobrado"), "moneda"),
                kpi("Diferencia", tot.get("diferencia"), "moneda"),
                kpi("Costo medio por envío", tot.get("costo_promedio"), "moneda"),
                kpi("Envíos considerados", tot.get("envios"), "numero")));
    }
}
