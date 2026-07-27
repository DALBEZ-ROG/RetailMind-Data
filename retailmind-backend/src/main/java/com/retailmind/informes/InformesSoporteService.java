package com.retailmind.informes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DEL DEPARTAMENTO DE SOPORTE — los tres objetivos del
 * catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §8) que se
 * resuelven con una consulta directa a PostgreSQL:
 *
 * <ul>
 *   <li><b>OTD-SOP-01</b> {@link #bandeja} — bandeja de tickets por estado,
 *       prioridad, categoría y agente asignado.</li>
 *   <li><b>OTD-SOP-04</b> {@link #porCategoria} — distribución de tickets por
 *       categoría, para atacar la causa de fondo.</li>
 *   <li><b>OTD-SOP-05</b> {@link #porAgente} — carga y cierre por agente.</li>
 * </ul>
 *
 * Los COMPUESTOS de Soporte (cumplimiento del SLA por período — SOP-02, tiempo
 * de resolución por categoría — SOP-03, satisfacción — SOP-06, tiempo por
 * agente — SOP-07, reapertura — SOP-08) NO viven aquí: pertenecen a la fase
 * ETL → ClickHouse. Lo que sí cabe en el nivel relacional es la FOTO de hoy:
 * cuántos vencieron el plazo, no cuánto tardamos en promedio por período.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * SEGREGACIÓN: ningún informe de Soporte lleva dinero, así que aquí no hay
 * corte financiero que declarar. Lo que sí condiciona los JOIN son los GRANTs
 * del destinatario: grp_soporte tiene SELECT sobre {@code usuario} (columnas),
 * {@code usuario_rol}, {@code rol}, {@code cliente} y {@code categoria_ticket}
 * (script 37), que es exactamente lo que estas consultas unen — ni una tabla
 * más. La RLS de {@code ticket_soporte} (pol_soporte + pol_horario) deja a
 * SOPORTE ver toda la bandeja dentro de su ventana horaria.
 *
 * NOTA SOBRE EL PLAZO: {@code ticket_soporte.fecha_limite} está persistida
 * desde el script 49 (antes se calculaba al vuelo), así que el «VENCIDO» de
 * estos informes es una comparación contra una columna real y no una regla
 * duplicada en Java.
 */
@Service
public class InformesSoporteService extends InformeServiceBase {

    /** Espeja ticket_soporte_estado_check (script 37). */
    private static final Set<String> ESTADOS = Set.of(
            "abierto", "en_proceso", "esperando_cliente", "resuelto", "cerrado");

    /**
     * Estado sintético de la BANDEJA: «pendientes» son los tres estados en los
     * que el ticket sigue vivo. No es una columna: es la pregunta que se hace
     * quien abre la bandeja, y por eso es el valor por defecto del filtro.
     */
    private static final String PENDIENTES = "pendientes";

    private static final Set<String> ESTADOS_FILTRO = Set.of(
            "abierto", "en_proceso", "esperando_cliente", "resuelto", "cerrado", PENDIENTES);

    /** Espeja ticket_soporte_prioridad_check. */
    private static final Set<String> PRIORIDADES = Set.of("baja", "media", "alta", "urgente");

    public InformesSoporteService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-SOP-01 — Bandeja de tickets
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Qué reclamos y consultas hay sobre la mesa: en qué estado están, qué tan
     * urgentes son, de qué categoría y quién los atiende.
     *
     * El orden NO es cronológico sino de urgencia operativa: primero lo que ya
     * pasó su fecha límite, después lo que vence antes. Un ticket sin asignar
     * se marca con {@code sin_asignar} porque es el hueco real de la bandeja:
     * nadie lo está mirando.
     *
     * El «vencido» compara {@code fecha_limite} (columna persistida, script 49)
     * contra ahora y SOLO para los tickets vivos: un ticket cerrado tarde es
     * materia de OTD-SOP-02, que es compuesto y no vive aquí.
     *
     * Filtros: estado (incluido el sintético «pendientes», que es el valor por
     * defecto), prioridad, categoría, agente asignado y búsqueda por número,
     * asunto o cliente. Paginado (248 tickets, 128 vivos).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> bandeja(String estado, String prioridad, String categoria,
                                       String agente, String buscar, int page, int size) {
        String est = opcion(estado, ESTADOS_FILTRO, "estado");
        String pri = opcion(prioridad, PRIORIDADES, "prioridad");
        String cat = texto(categoria);
        String ag = texto(agente);
        String q = texto(buscar);

        // «pendientes» no es un estado de la tabla: se traduce a la negación de
        // los dos terminales. Se pasa como bandera aparte para que el SQL siga
        // siendo constante y el valor del usuario viaje solo en los parámetros.
        String estadoConcreto = PENDIENTES.equals(est) ? null : est;
        boolean soloPendientes = PENDIENTES.equals(est);

        final String tabla = """
                FROM ticket_soporte t
                JOIN cliente c ON c.id = t.cliente_id
                LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
                LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR t.estado = ?::varchar)
                  AND (?::boolean IS NOT TRUE OR t.estado NOT IN ('resuelto', 'cerrado'))
                  AND (?::varchar IS NULL OR t.prioridad = ?::varchar)
                  AND (?::varchar IS NULL OR ct.nombre ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR u.nombre ILIKE '%' || ?::varchar || '%'
                       OR u.apellido ILIKE '%' || ?::varchar || '%'
                       OR u.email ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR t.numero ILIKE '%' || ?::varchar || '%'
                       OR t.asunto ILIKE '%' || ?::varchar || '%'
                       OR c.nombre ILIKE '%' || ?::varchar || '%'
                       OR c.apellido ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { estadoConcreto, estadoConcreto, soloPendientes, pri, pri, cat, cat,
                ag, ag, ag, ag, q, q, q, q, q };

        Map<String, Object> res = paginar("""
                SELECT t.id, t.numero, t.estado, t.prioridad, t.asunto,
                       ct.nombre AS categoria,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '')
                           AS agente,
                       (t.asignado_usuario_id IS NULL) AS sin_asignar,
                       NULLIF(trim(concat(c.nombre, ' ', COALESCE(c.apellido, ''))), '')
                           AS cliente,
                       t.fecha_creacion, t.fecha_limite, t.fecha_cierre,
                       (t.estado NOT IN ('resuelto', 'cerrado')) AS vivo,
                       (t.estado NOT IN ('resuelto', 'cerrado')
                        AND t.fecha_limite IS NOT NULL AND t.fecha_limite < now()) AS vencido,
                       (EXTRACT(epoch FROM COALESCE(t.fecha_cierre, now()) - t.fecha_creacion)
                        / 86400)::int AS dias_abierto
                """ + tabla + filtro + """
                ORDER BY (t.estado NOT IN ('resuelto', 'cerrado')
                          AND t.fecha_limite IS NOT NULL AND t.fecha_limite < now()) DESC,
                         t.fecha_limite NULLS LAST, t.id DESC""",
                "SELECT count(*) " + tabla + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS tickets,
                       count(*) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado')) AS vivos,
                       count(*) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado')
                                          AND t.fecha_limite IS NOT NULL
                                          AND t.fecha_limite < now()) AS vencidos,
                       count(*) FILTER (WHERE t.asignado_usuario_id IS NULL
                                          AND t.estado NOT IN ('resuelto', 'cerrado'))
                           AS sin_asignar,
                       count(*) FILTER (WHERE t.prioridad IN ('urgente', 'alta')
                                          AND t.estado NOT IN ('resuelto', 'cerrado'))
                           AS criticos
                """ + tabla + filtro, args);

        return conResumen(res, List.of(
                kpi("Tickets", tot.get("tickets"), "numero"),
                kpi("Sin resolver", tot.get("vivos"), "numero"),
                kpi("Fuera de plazo", tot.get("vencidos"), "numero"),
                kpi("Sin asignar", tot.get("sin_asignar"), "numero"),
                kpi("Urgentes o altos", tot.get("criticos"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-SOP-04 — Tickets por categoría
    // ─────────────────────────────────────────────────────────────────────

    /**
     * De qué se queja la gente: distribución de tickets por categoría, para
     * atacar la causa y no el síntoma.
     *
     * Parte de {@code categoria_ticket} con LEFT JOIN y no de los tickets: una
     * categoría SIN reclamos en el período es información —significa que ese
     * frente está tranquilo— y agrupar por los tickets la haría desaparecer.
     * El {@code porcentaje} se calcula sobre el total del MISMO período, no
     * sobre la tabla completa, o el filtro de fechas mentiría.
     *
     * {@code prioridad_defecto} viaja en la fila porque explica por qué una
     * categoría concentra críticos: la prioridad del ticket es automática desde
     * el script 37 y sale de aquí, no de lo que escriba el cliente.
     *
     * Filtro: rango de fecha de creación. Sin paginar (8 categorías).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> porCategoria(String desde, String hasta) {
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        // El mismo filtro de período se aplica DENTRO del LEFT JOIN (no en el
        // WHERE) para no perder las categorías sin tickets en la ventana.
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT ct.id, ct.nombre AS categoria, ct.prioridad_defecto,
                       count(t.id) AS tickets,
                       count(t.id) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado'))
                           AS sin_resolver,
                       count(t.id) FILTER (WHERE t.estado IN ('resuelto', 'cerrado'))
                           AS resueltos,
                       count(t.id) FILTER (WHERE t.prioridad IN ('urgente', 'alta')) AS criticos,
                       count(t.id) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado')
                                             AND t.fecha_limite IS NOT NULL
                                             AND t.fecha_limite < now()) AS vencidos,
                       round(100.0 * count(t.id) / NULLIF(sum(count(t.id)) OVER (), 0), 1)
                           AS porcentaje,
                       round(avg(EXTRACT(epoch FROM COALESCE(t.fecha_cierre, now())
                                                  - t.fecha_creacion) / 86400)::numeric, 1)
                           AS dias_promedio_abierto
                FROM categoria_ticket ct
                LEFT JOIN ticket_soporte t ON t.categoria_ticket_id = ct.id
                     AND (?::date IS NULL OR t.fecha_creacion >= ?::date)
                     AND (?::date IS NULL OR t.fecha_creacion <  (?::date + 1))
                GROUP BY ct.id, ct.nombre, ct.prioridad_defecto
                ORDER BY tickets DESC, ct.nombre""",
                new Object[] { d, d, h, h });

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS tickets,
                       count(*) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado'))
                           AS sin_resolver,
                       count(DISTINCT t.categoria_ticket_id) AS categorias_con_tickets,
                       count(*) FILTER (WHERE t.categoria_ticket_id IS NULL) AS sin_categoria
                FROM ticket_soporte t
                WHERE (?::date IS NULL OR t.fecha_creacion >= ?::date)
                  AND (?::date IS NULL OR t.fecha_creacion <  (?::date + 1))""",
                new Object[] { d, d, h, h });

        return conResumen(sobre(items), List.of(
                kpi("Tickets del período", tot.get("tickets"), "numero"),
                kpi("Sin resolver", tot.get("sin_resolver"), "numero"),
                kpi("Categorías con reclamos", tot.get("categorias_con_tickets"), "numero"),
                kpi("Sin categoría asignada", tot.get("sin_categoria"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-SOP-05 — Carga por agente
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cómo está repartido el trabajo del equipo: cuántos tickets tiene cada
     * agente, en qué estado y cuántos ha cerrado.
     *
     * Los tickets SIN asignar salen como una fila propia «(sin asignar)» y no
     * se esconden: son la cola que nadie tomó, el dato más accionable del
     * informe. Por eso el agregado parte de {@code ticket_soporte} con LEFT
     * JOIN a {@code usuario} y no al revés — un agente sin tickets en el
     * período no es noticia, pero una cola sin dueño sí.
     *
     * El ROL viaja en la fila porque la asignación no está restringida a
     * grp_soporte: en los datos reales hay tickets asignados a GERENTE y a
     * DESPACHO, y verlos mezclados con el equipo de soporte es justamente lo
     * que un jefe necesita detectar.
     *
     * Filtro: rango de fecha de creación. Sin paginar (7 filas: 4 agentes +
     * la cola sin asignar + 2 asignaciones fuera del equipo).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> porAgente(String desde, String hasta) {
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        final String filtro = """
                WHERE (?::date IS NULL OR t.fecha_creacion >= ?::date)
                  AND (?::date IS NULL OR t.fecha_creacion <  (?::date + 1))
                """;
        Object[] args = { d, d, h, h };

        List<Map<String, Object>> items = pg.queryForList("""
                SELECT COALESCE(NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), ''),
                                '(sin asignar)') AS agente,
                       u.email,
                       -- El rol NUNCA viaja NULL: la pantalla pinta «—» para un
                       -- valor vacío ANTES de aplicar la etiqueta, y la fila de
                       -- la cola sin dueño se quedaría sin explicar.
                       COALESCE(r.codigo, CASE WHEN t.asignado_usuario_id IS NULL
                                               THEN 'SIN_DUENO' ELSE 'SIN_ROL' END) AS rol,
                       (t.asignado_usuario_id IS NULL) AS cola_sin_dueno,
                       count(*) AS asignados,
                       count(*) FILTER (WHERE t.estado = 'abierto') AS abiertos,
                       count(*) FILTER (WHERE t.estado = 'en_proceso') AS en_proceso,
                       count(*) FILTER (WHERE t.estado = 'esperando_cliente')
                           AS esperando_cliente,
                       count(*) FILTER (WHERE t.estado IN ('resuelto', 'cerrado')) AS resueltos,
                       count(*) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado')
                                          AND t.fecha_limite IS NOT NULL
                                          AND t.fecha_limite < now()) AS vencidos,
                       count(*) FILTER (WHERE t.prioridad IN ('urgente', 'alta')
                                          AND t.estado NOT IN ('resuelto', 'cerrado'))
                           AS criticos_vivos,
                       round(100.0 * count(*) FILTER (WHERE t.estado IN ('resuelto', 'cerrado'))
                             / NULLIF(count(*), 0), 1) AS tasa_resolucion
                FROM ticket_soporte t
                LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
                LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
                LEFT JOIN rol r ON r.id = ur.rol_id
                """ + filtro + """
                GROUP BY t.asignado_usuario_id, u.nombre, u.apellido, u.email, r.codigo
                ORDER BY (t.asignado_usuario_id IS NULL) DESC, asignados DESC""", args);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS tickets,
                       count(DISTINCT t.asignado_usuario_id) AS agentes,
                       count(*) FILTER (WHERE t.asignado_usuario_id IS NULL) AS sin_asignar,
                       count(*) FILTER (WHERE t.estado IN ('resuelto', 'cerrado')) AS resueltos,
                       round(100.0 * count(*) FILTER (WHERE t.estado IN ('resuelto', 'cerrado'))
                             / NULLIF(count(*), 0), 1) AS tasa
                FROM ticket_soporte t
                """ + filtro, args);

        return conResumen(sobre(items), List.of(
                kpi("Tickets del período", tot.get("tickets"), "numero"),
                kpi("Agentes con carga", tot.get("agentes"), "numero"),
                kpi("En la cola sin dueño", tot.get("sin_asignar"), "numero"),
                kpi("Resueltos o cerrados", tot.get("resueltos"), "numero"),
                kpi("Tasa de resolución", tot.get("tasa"), "porcentaje")));
    }
}
