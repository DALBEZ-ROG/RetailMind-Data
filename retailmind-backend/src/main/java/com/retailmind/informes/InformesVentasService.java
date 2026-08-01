package com.retailmind.informes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DEL DEPARTAMENTO DE VENTAS — los cinco objetivos SIMPLES
 * del catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §3):
 *
 * <ul>
 *   <li><b>OTD-VEN-01</b> {@link #carteraPedidos} — cartera de pedidos por estado.</li>
 *   <li><b>OTD-VEN-02</b> {@link #ventasPorVendedor} — cumplimiento individual del equipo.</li>
 *   <li><b>OTD-VEN-08</b> {@link #carritosAbandonados} — carritos dejados a medias.</li>
 *   <li><b>OTD-VEN-10</b> {@link #colaModeracion} — voz del cliente en espera.</li>
 *   <li><b>OTD-VEN-15</b> {@link #avanceMeta} — venta acumulada contra la meta del período.</li>
 *   <li><b>OTD-VEN-16</b> {@link #participacionCanal} — participación de cada canal en la venta.</li>
 * </ul>
 *
 * Los COMPUESTOS de Ventas (tendencias, top de productos por período, ticket
 * promedio) NO viven aquí: pertenecen a la fase ETL → ClickHouse. En
 * particular, OTD-VEN-13 —la EVOLUCIÓN MENSUAL de la participación por canal—
 * es el par compuesto de OTD-VEN-16 y no se resuelve en PostgreSQL.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * Segregación financiera: los informes con dinero (VEN-01, VEN-02, VEN-08,
 * VEN-15) los cierra SecurityConfig a ADMIN/GERENTE/VENDEDOR — Bodega y
 * Despacho no entran, y el motor lo respalda (no tienen SELECT sobre
 * pedido.total ni sobre carrito/meta_venta).
 */
@Service
public class InformesVentasService extends InformeServiceBase {

    /** Espeja estado_pedido.codigo (11 estados). Filtro de VEN-01. */
    private static final Set<String> ESTADOS = Set.of(
            "pendiente", "confirmado", "pagado", "facturado", "en_preparacion",
            "preparado", "despachado", "entregado", "cancelado", "devuelto", "no_entregado");

    /** Espeja pedido.canal: 'web' = tienda en línea; 'tienda'/'telefono' = interno. */
    private static final Set<String> CANALES = Set.of("web", "tienda", "telefono");

    /** Estado del carrito que se considera «a medias» (VEN-08). */
    private static final Set<String> ESTADOS_CARRITO = Set.of("abandonado", "activo", "ambos");

    /** Tipo de elemento de la cola de moderación (VEN-10). */
    private static final Set<String> TIPOS_MODERACION = Set.of("resena", "pregunta", "ambos");

    /** Departamentos de meta_venta cuyo avance se calcula contra la venta facturada. */
    private static final Set<String> DEPARTAMENTOS_META = Set.of("ventas", "general");

    public InformesVentasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-01 — Cartera de pedidos por estado
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toda la cartera con el paso del proceso en que está cada pedido hoy.
     * Filtros: estado (código), canal, rango de fecha del pedido y búsqueda
     * por número de pedido o cliente. Paginado (4.083 pedidos hoy).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> carteraPedidos(String estado, String canal, String desde,
                                              String hasta, String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS, "estado");
        String can = opcion(canal, CANALES, "canal");
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);
        String q = texto(buscar);

        // Guarda NULL por parámetro: el mismo valor va dos veces (IS NULL +
        // comparación). Cero concatenación de datos del usuario.
        final String from = """
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                LEFT JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN usuario v ON v.id = p.vendedor_id
                WHERE (?::varchar IS NULL OR ep.codigo = ?::varchar)
                  AND (?::varchar IS NULL OR p.canal  = ?::varchar)
                  AND (?::date    IS NULL OR p.fecha_pedido >= ?::date)
                  AND (?::date    IS NULL OR p.fecha_pedido <  (?::date + 1))
                  AND (?::varchar IS NULL OR p.numero ILIKE '%' || ?::varchar || '%'
                       OR concat(c.nombre, ' ', c.apellido) ILIKE '%' || ?::varchar || '%'
                       OR c.email ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { est, est, can, can, d, d, h, h, q, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT p.id, p.numero, p.fecha_pedido, p.canal,
                       ep.codigo AS estado_codigo, ep.nombre AS estado, ep.orden AS estado_orden,
                       NULLIF(trim(concat(c.nombre, ' ', COALESCE(c.apellido, ''))), '') AS cliente,
                       c.email AS cliente_email,
                       NULLIF(trim(concat(v.nombre, ' ', COALESCE(v.apellido, ''))), '') AS vendedor,
                       p.subtotal, p.monto_descuento, p.costo_envio, p.total
                """ + from + " ORDER BY p.fecha_pedido DESC, p.id DESC",
                "SELECT count(*) " + from, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS pedidos,
                       COALESCE(sum(p.total), 0) AS monto,
                       COALESCE(sum(p.total) FILTER (WHERE ep.codigo NOT IN
                            ('entregado', 'cancelado', 'devuelto', 'no_entregado')), 0) AS en_curso
                """ + from, args);

        return conResumen(res, List.of(
                kpi("Pedidos en el filtro", tot.get("pedidos"), "numero"),
                kpi("Monto total", tot.get("monto"), "moneda"),
                kpi("Monto aún en proceso", tot.get("en_curso"), "moneda")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-02 — Ventas por vendedor
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Pedidos y monto por vendedor en el período, para evaluar el cumplimiento
     * individual. Los pedidos cancelados NO cuentan como venta pero se
     * muestran aparte, y los pedidos ONLINE (vendedor_id NULL por diseño,
     * script 42) salen como una fila propia «Tienda en línea» para que el
     * total del período cuadre con la cartera.
     *
     * RECORTE POR ROL: un VENDEDOR solo ve su propia fila (y no la de la
     * tienda en línea, que no es suya); Gerencia y Administración ven a todos.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ventasPorVendedor(String desde, String hasta) {
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        // El propio vendedor queda acotado a lo suyo; el resto de roles ven todo.
        boolean soloPropio = "VENDEDOR".equals(rolActual());
        Long propio = soloPropio ? usuarioActualId() : null;

        final String from = """
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                LEFT JOIN usuario u ON u.id = p.vendedor_id
                WHERE (?::date   IS NULL OR p.fecha_pedido >= ?::date)
                  AND (?::date   IS NULL OR p.fecha_pedido <  (?::date + 1))
                  AND (?::bigint IS NULL OR p.vendedor_id = ?::bigint)
                """;
        Object[] args = { d, d, h, h, propio, propio };

        List<Map<String, Object>> items = pg.queryForList("""
                SELECT p.vendedor_id,
                       COALESCE(NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), ''),
                                'Tienda en línea (sin vendedor)') AS vendedor,
                       count(*) FILTER (WHERE ep.codigo <> 'cancelado') AS pedidos,
                       COALESCE(sum(p.total) FILTER (WHERE ep.codigo <> 'cancelado'), 0) AS monto_total,
                       round(COALESCE(avg(p.total) FILTER (WHERE ep.codigo <> 'cancelado'), 0), 2)
                           AS ticket_promedio,
                       count(*) FILTER (WHERE ep.codigo = 'cancelado') AS cancelados,
                       max(p.fecha_pedido) AS ultima_venta
                """ + from + """
                GROUP BY p.vendedor_id, u.nombre, u.apellido
                ORDER BY monto_total DESC""", args);

        // Participación de cada vendedor sobre el total del período mostrado.
        BigDecimal total = items.stream()
                .map(r -> new BigDecimal(String.valueOf(r.get("monto_total"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map<String, Object> fila : items) {
            BigDecimal monto = new BigDecimal(String.valueOf(fila.get("monto_total")));
            fila.put("participacion_pct", total.signum() == 0 ? BigDecimal.ZERO
                    : monto.multiply(BigDecimal.valueOf(100))
                           .divide(total, 2, RoundingMode.HALF_UP));
        }

        long pedidos = items.stream()
                .mapToLong(r -> ((Number) r.get("pedidos")).longValue()).sum();

        Map<String, Object> res = sobre(items);
        res.put("alcance", soloPropio ? "propio" : "equipo");
        return conResumen(res, List.of(
                kpi(soloPropio ? "Mis pedidos" : "Pedidos del equipo", pedidos, "numero"),
                kpi(soloPropio ? "Mi venta" : "Venta del período", total, "moneda"),
                kpi("Vendedores con venta", items.size(), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-08 — Carritos abandonados
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carritos que el cliente dejó a medias, con su antigüedad y contenido.
     *
     * La antigüedad se mide sobre la ÚLTIMA ACTIVIDAD del carrito, que es
     * {@code COALESCE(fecha_actualizacion, fecha_creacion)}: el carrito solo
     * recibe el touch del trigger cuando se le mueve algo, así que los
     * abandonados históricos tienen fecha_actualizacion NULL y sin el COALESCE
     * quedarían fuera del informe.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> carritosAbandonados(String estado, Integer diasMinimos,
                                                   int page, int size) {
        String est = opcion(estado, ESTADOS_CARRITO, "estado");
        String filtroEstado = est == null || "ambos".equals(est) ? null : est;
        int dias = entero(diasMinimos, 0, 3650, 0, "diasMinimos");

        // El FROM se arma con tres constantes del código (tabla, agregado del
        // contenido y filtro); lo del usuario viaja solo en args.
        final String tabla = """
                FROM carrito ca
                LEFT JOIN cliente cl ON cl.id = ca.cliente_id
                """;
        final String contenido = """
                LEFT JOIN LATERAL (
                    SELECT count(*) AS lineas,
                           COALESCE(sum(ci.cantidad), 0) AS unidades,
                           COALESCE(sum(ci.cantidad * ci.precio_unitario), 0) AS valor,
                           string_agg(pr.nombre || ' ×' || ci.cantidad, ', ' ORDER BY ci.id)
                               AS contenido
                    FROM carrito_item ci
                    JOIN producto_variante pv ON pv.id = ci.producto_variante_id
                    JOIN producto pr ON pr.id = pv.producto_id
                    WHERE ci.carrito_id = ca.id) ag ON true
                """;
        final String filtro = """
                WHERE ca.estado IN ('abandonado', 'activo')
                  AND (?::varchar IS NULL OR ca.estado = ?::varchar)
                  AND COALESCE(ca.fecha_actualizacion, ca.fecha_creacion)
                      <= now() - make_interval(days => ?::int)
                """;
        Object[] args = { filtroEstado, filtroEstado, dias };

        Map<String, Object> res = paginar("""
                SELECT ca.id, ca.estado, ca.fecha_creacion,
                       COALESCE(ca.fecha_actualizacion, ca.fecha_creacion) AS ultima_actividad,
                       (EXTRACT(epoch FROM now()
                            - COALESCE(ca.fecha_actualizacion, ca.fecha_creacion)) / 86400)::int
                           AS dias_inactivo,
                       NULLIF(trim(concat(cl.nombre, ' ', COALESCE(cl.apellido, ''))), '') AS cliente,
                       cl.email AS cliente_email,
                       ag.lineas, ag.unidades, ag.valor, ag.contenido
                """ + tabla + contenido + filtro + " ORDER BY dias_inactivo DESC, ca.id DESC",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS carritos,
                       COALESCE(sum(ag.valor), 0) AS valor,
                       COALESCE(round(avg((EXTRACT(epoch FROM now()
                            - COALESCE(ca.fecha_actualizacion, ca.fecha_creacion)) / 86400))), 0)
                           AS dias_promedio
                """ + tabla + contenido + filtro, args);

        return conResumen(res, List.of(
                kpi("Carritos a medias", tot.get("carritos"), "numero"),
                kpi("Venta no concretada", tot.get("valor"), "moneda"),
                kpi("Antigüedad promedio", tot.get("dias_promedio"), "dias")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-10 — Cola de moderación (voz del cliente)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reseñas en espera de aprobación y preguntas de producto sin atender,
     * con su antigüedad. Una pregunta entra a la cola por dos motivos:
     * pendiente de aprobación, o ya publicada pero SIN respuesta del equipo
     * (la tabla {@code respuesta_pregunta} es la que responde esa pregunta).
     *
     * Informe de moderadores: SecurityConfig lo cierra a ADMIN/GERENTE, que
     * son los únicos con SELECT sobre resena y pregunta_producto en el motor.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> colaModeracion(String tipo, Integer diasMinimos,
                                              int page, int size) {
        String tp = opcion(tipo, TIPOS_MODERACION, "tipo");
        String filtroTipo = tp == null || "ambos".equals(tp) ? null : tp;
        int dias = entero(diasMinimos, 0, 3650, 0, "diasMinimos");

        // Cola unificada: la UNION la escribe el desarrollador; los filtros
        // viajan como parámetros y se aplican una sola vez, por fuera.
        final String cola = """
                FROM (
                    SELECT 'resena' AS tipo, r.id, r.fecha_creacion, r.estado,
                           'pendiente_aprobacion' AS motivo,
                           r.producto_id, r.titulo AS asunto, r.comentario AS detalle,
                           r.calificacion, r.compra_verificada, r.cliente_id
                    FROM resena r
                    WHERE r.estado = 'pendiente'
                    UNION ALL
                    SELECT 'pregunta', pp.id, pp.fecha_creacion, pp.estado,
                           CASE WHEN pp.estado = 'pendiente' THEN 'pendiente_aprobacion'
                                ELSE 'sin_respuesta' END,
                           pp.producto_id, NULL, pp.pregunta, NULL, NULL, pp.cliente_id
                    FROM pregunta_producto pp
                    WHERE pp.estado = 'pendiente'
                       OR (pp.estado = 'publicada'
                           AND NOT EXISTS (SELECT 1 FROM respuesta_pregunta rp
                                           WHERE rp.pregunta_producto_id = pp.id))
                ) q
                LEFT JOIN producto pr ON pr.id = q.producto_id
                LEFT JOIN cliente cl ON cl.id = q.cliente_id
                WHERE (?::varchar IS NULL OR q.tipo = ?::varchar)
                  AND q.fecha_creacion <= now() - make_interval(days => ?::int)
                """;
        Object[] args = { filtroTipo, filtroTipo, dias };

        Map<String, Object> res = paginar("""
                SELECT q.tipo, q.id, q.fecha_creacion, q.estado, q.motivo,
                       (EXTRACT(epoch FROM now() - q.fecha_creacion) / 86400)::int AS dias_espera,
                       q.producto_id, pr.nombre AS producto,
                       q.asunto, q.detalle, q.calificacion, q.compra_verificada,
                       NULLIF(trim(concat(cl.nombre, ' ', COALESCE(cl.apellido, ''))), '') AS cliente
                """ + cola + " ORDER BY q.fecha_creacion ASC",
                "SELECT count(*) " + cola, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) FILTER (WHERE q.tipo = 'resena') AS resenas,
                       count(*) FILTER (WHERE q.tipo = 'pregunta') AS preguntas,
                       COALESCE(max((EXTRACT(epoch FROM now() - q.fecha_creacion) / 86400)::int), 0)
                           AS espera_maxima
                """ + cola, args);

        return conResumen(res, List.of(
                kpi("Reseñas por aprobar", tot.get("resenas"), "numero"),
                kpi("Preguntas por atender", tot.get("preguntas"), "numero"),
                kpi("Espera más antigua", tot.get("espera_maxima"), "dias")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-15 — Venta contra la meta del período
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Meta vigente del período contra la venta real acumulada, con el
     * porcentaje de avance y el ritmo que exige lo que falta del mes.
     *
     * La venta real es la MISMA definición que usa MetasVentaService (script
     * 48): facturas de venta no anuladas emitidas dentro del mes. Solo los
     * departamentos 'ventas' y 'general' tienen ese cálculo, que es
     * exactamente el alcance de este informe (OTD-VEN-15).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> avanceMeta(Integer anio, Integer mes) {
        LocalDate hoy = LocalDate.now();
        int a = entero(anio, 2000, 2100, hoy.getYear(), "anio");
        int m = entero(mes, 1, 12, hoy.getMonthValue(), "mes");

        List<Map<String, Object>> items = pg.queryForList("""
                WITH periodo AS (
                    SELECT make_date(?, ?, 1) AS inicio,
                           (make_date(?, ?, 1) + interval '1 month')::date AS fin
                )
                SELECT mv.id, mv.anio, mv.mes, mv.departamento, mv.monto_meta, mv.notas,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '')
                           AS fijada_por,
                       vr.venta_real,
                       vr.facturas,
                       round(vr.venta_real * 100 / NULLIF(mv.monto_meta, 0), 2) AS avance_pct,
                       greatest(mv.monto_meta - vr.venta_real, 0) AS faltante,
                       -- Días ya corridos del período (tope: el propio período,
                       -- para que un mes cerrado no muestre más días que los suyos)
                       greatest(least(current_date, (SELECT fin FROM periodo) - 1)
                                - (SELECT inicio FROM periodo) + 1, 0) AS dias_transcurridos,
                       ((SELECT fin FROM periodo) - (SELECT inicio FROM periodo))
                           AS dias_del_periodo
                FROM meta_venta mv
                LEFT JOIN usuario u ON u.id = mv.fijada_por
                CROSS JOIN LATERAL (
                    SELECT COALESCE(sum(fv.total), 0) AS venta_real, count(*) AS facturas
                    FROM factura_venta fv
                    WHERE fv.estado <> 'anulada'
                      AND fv.fecha_emision >= (SELECT inicio FROM periodo)
                      AND fv.fecha_emision <  (SELECT fin FROM periodo)
                ) vr
                WHERE mv.activo AND mv.anio = ? AND mv.mes = ?
                  AND mv.departamento IN ('ventas', 'general')
                ORDER BY mv.departamento""",
                a, m, a, m, a, m);

        if (items.isEmpty()) {
            // Sin meta fijada el informe no puede mentir: 409 con el motivo.
            throw new IllegalStateException("No hay meta de ventas vigente para " + m + "/" + a
                    + ". Fíjala en Gerencia → Metas de Venta para poder medir el avance.");
        }

        // Fila de referencia para las tarjetas: la meta del departamento Ventas
        // (o la primera disponible si solo existe la general).
        Map<String, Object> ref = items.stream()
                .filter(r -> "ventas".equals(r.get("departamento")))
                .findFirst().orElse(items.get(0));

        List<Map<String, Object>> kpis = new ArrayList<>(List.of(
                kpi("Meta del período (" + ref.get("departamento") + ")",
                        ref.get("monto_meta"), "moneda"),
                kpi("Venta real acumulada", ref.get("venta_real"), "moneda"),
                kpi("Avance", ref.get("avance_pct"), "porcentaje"),
                kpi("Falta para la meta", ref.get("faltante"), "moneda")));

        Map<String, Object> res = sobre(items);
        res.put("anio", a);
        res.put("mes", m);
        return conResumen(res, kpis);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-VEN-16 — Participación de la venta por canal
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Foto de la composición de la venta: cuántos pedidos, cuánto dinero, qué
     * ticket promedio y qué porcentaje de participación pone cada CANAL DE
     * ENTRADA en el período consultado. Sostiene el objetivo estratégico OE-06
     * (Consolidación de la Experiencia Omnicanal) mostrando de dónde viene hoy
     * el ingreso.
     *
     * <b>Alcance: SIMPLE.</b> Agrega sobre el presente sin comparar períodos,
     * como OTD-VEN-02 o OTD-SOP-04. La EVOLUCIÓN MENSUAL de esta misma
     * participación es OTD-VEN-13, COMPUESTO, y va a ClickHouse por el ETL.
     *
     * <b>ESTE INFORME MIDE EL MEDIO DE ENTRADA, NO EL TIPO DE CLIENTE.</b>
     * {@code pedido.canal} solo admite 'web', 'tienda' y 'telefono' (CHECK del
     * motor): es la vía por la que entró el pedido. Agruparlo y rotularlo
     * «B2B vs. B2C» sería una lectura falsa del dato (regla vinculante en
     * {@code docs/tactico/PATRON_INFORMES.md} §12).
     *
     * <b>Y esa clasificación tampoco existe por otra vía.</b> El diagnóstico
     * {@code docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md} (2026-07-30,
     * solo lectura) la descartó con veredicto (c) POBLACIÓN HOMOGÉNEA: no está
     * capturada —{@code grupo_cliente}, {@code segmento_cliente} y
     * {@code cliente_segmento} con 0 filas, los 72 clientes con
     * {@code grupo_cliente_id} NULL y {@code tipo_identificacion='cedula'}, y
     * las 3.887 facturas de venta con identificación de 10 dígitos (sin RUC)—
     * y tampoco es DERIVABLE del comportamiento: el 99,94 % de las 10.384
     * líneas de pedido pide entre 1 y 4 unidades (techo histórico 12 por línea
     * y 24 por pedido), de modo que no existe compra de volumen, y ninguna de
     * las siete dimensiones analizadas (ticket, unidades, líneas, categorías,
     * método de pago, canal, regularidad) separa dos poblaciones.
     *
     * <b>Por eso la columna {@code clientes_negocio} se conserva pero se rotula
     * «Clientes con segmento registrado».</b> Es
     * {@code count(DISTINCT cliente_id) FILTER (WHERE c.grupo_cliente_id IS NOT NULL)}
     * y vale 0 en los tres canales: MIDE la ausencia de segmentación registrada
     * en vez de disimularla. NO se rotula «B2B» porque ese nombre prometía un
     * segmento pendiente de llenarse; poblar {@code grupo_cliente} hoy no
     * capturaría un dato, pondría una etiqueta arbitraria sobre una población
     * que se comporta como una sola. El propuesto OTD-VEN-17 quedó DESCARTADO,
     * no pospuesto.
     *
     * Los pedidos cancelados NO cuentan como venta —igual que en OTD-VEN-02—
     * pero se muestran en columna propia para que no desaparezcan del análisis.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> participacionCanal(String canal, String desde, String hasta) {
        String can = opcion(canal, CANALES, "canal");
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        // La participación se calcula con sum(...) OVER () sobre los canales
        // que quedan DENTRO del filtro: si se acota a un canal, ese canal es el
        // 100 % de lo mostrado, que es la lectura correcta de «lo filtrado».
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT p.canal,
                       count(*) FILTER (WHERE ep.codigo <> 'cancelado') AS pedidos,
                       COALESCE(sum(p.total) FILTER (WHERE ep.codigo <> 'cancelado'), 0)
                           AS monto_vendido,
                       round(COALESCE(avg(p.total) FILTER (WHERE ep.codigo <> 'cancelado'), 0), 2)
                           AS ticket_promedio,
                       round(count(*) FILTER (WHERE ep.codigo <> 'cancelado') * 100.0
                             / NULLIF(sum(count(*) FILTER (WHERE ep.codigo <> 'cancelado'))
                                      OVER (), 0), 2) AS participacion_pedidos_pct,
                       round(COALESCE(sum(p.total) FILTER (WHERE ep.codigo <> 'cancelado'), 0)
                                 * 100.0
                             / NULLIF(sum(COALESCE(sum(p.total)
                                   FILTER (WHERE ep.codigo <> 'cancelado'), 0)) OVER (), 0), 2)
                           AS participacion_monto_pct,
                       count(DISTINCT p.cliente_id) AS clientes,
                       -- Medida honesta de la ausencia: clientes con ALGÚN
                       -- segmento registrado. Hoy 0 en los tres canales.
                       count(DISTINCT p.cliente_id) FILTER (WHERE c.grupo_cliente_id IS NOT NULL)
                           AS clientes_negocio,
                       count(*) FILTER (WHERE ep.codigo = 'cancelado') AS cancelados,
                       max(p.fecha_pedido) AS ultima_venta
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                LEFT JOIN cliente c ON c.id = p.cliente_id
                WHERE (?::varchar IS NULL OR p.canal = ?::varchar)
                  AND (?::date    IS NULL OR p.fecha_pedido >= ?::date)
                  AND (?::date    IS NULL OR p.fecha_pedido <  (?::date + 1))
                GROUP BY p.canal
                HAVING count(*) FILTER (WHERE ep.codigo <> 'cancelado') > 0
                    OR count(*) FILTER (WHERE ep.codigo = 'cancelado') > 0
                ORDER BY monto_vendido DESC""",
                can, can, d, d, h, h);

        BigDecimal monto = BigDecimal.ZERO;
        long pedidos = 0;
        long negocio = 0;
        for (Map<String, Object> fila : items) {
            monto = monto.add(new BigDecimal(String.valueOf(fila.get("monto_vendido"))));
            pedidos += ((Number) fila.get("pedidos")).longValue();
            negocio += ((Number) fila.get("clientes_negocio")).longValue();
        }

        // El líder es la primera fila: la consulta ya ordena por monto.
        String lider = items.isEmpty() ? "—"
                : NOMBRE_CANAL.getOrDefault(String.valueOf(items.get(0).get("canal")),
                                            String.valueOf(items.get(0).get("canal")));
        BigDecimal ticket = pedidos == 0 ? BigDecimal.ZERO
                : monto.divide(BigDecimal.valueOf(pedidos), 2, RoundingMode.HALF_UP);

        return conResumen(sobre(items), List.of(
                kpi("Pedidos del período", pedidos, "numero"),
                kpi("Monto vendido", monto, "moneda"),
                kpi("Ticket promedio", ticket, "moneda"),
                kpi("Canal líder", lider, "texto"),
                // Medida de la ausencia de segmentación: se informa el 0, no se
                // esconde. Sin etiqueta B2B: esa clasificación no existe.
                kpi("Clientes con segmento registrado", negocio, "numero")));
    }

    /** Nombre de negocio de cada canal, para el KPI de canal líder. */
    private static final Map<String, String> NOMBRE_CANAL = Map.of(
            "web", "Tienda en línea",
            "tienda", "Mostrador",
            "telefono", "Teléfono");

    /** Lista blanca expuesta al frontend para poblar los selects sin adivinar. */
    static Set<String> departamentosMeta() {
        return DEPARTAMENTOS_META;
    }
}
