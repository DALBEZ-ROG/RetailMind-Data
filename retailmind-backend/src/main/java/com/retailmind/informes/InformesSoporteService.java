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
        /*
         * El WHERE se arma SOLO con los filtros presentes, y no con guardas
         * `(? IS NULL OR ...)`. No es estética: esas guardas NOMBRAN los alias
         * `ct`, `u` y `c` aunque el filtro venga vacío, y mientras los nombren
         * el conteo está obligado a arrastrar sus tres JOIN — que es justo lo
         * que se quiere evitar (ver el bloque de abajo). Las piezas son
         * constantes del código y los valores viajan ligados.
         */
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> lista = new java.util.ArrayList<>();
        if (estadoConcreto != null) {
            w.append(" AND t.estado = ?\n");
            lista.add(estadoConcreto);
        }
        if (soloPendientes) {
            w.append(" AND t.estado NOT IN ('resuelto', 'cerrado')\n");
        }
        if (pri != null) {
            w.append(" AND t.prioridad = ?\n");
            lista.add(pri);
        }
        if (cat != null) {
            w.append(" AND ct.nombre ILIKE '%' || ? || '%'\n");
            lista.add(cat);
        }
        if (ag != null) {
            w.append(" AND (u.nombre ILIKE '%' || ? || '%'"
                   + " OR u.apellido ILIKE '%' || ? || '%'"
                   + " OR u.email ILIKE '%' || ? || '%')\n");
            lista.add(ag); lista.add(ag); lista.add(ag);
        }
        if (q != null) {
            w.append(" AND (t.numero ILIKE '%' || ? || '%'"
                   + " OR t.asunto ILIKE '%' || ? || '%'"
                   + " OR c.nombre ILIKE '%' || ? || '%'"
                   + " OR c.apellido ILIKE '%' || ? || '%')\n");
            lista.add(q); lista.add(q); lista.add(q); lista.add(q);
        }
        final String filtro = w.toString();
        Object[] args = lista.toArray();

        /*
         * EL CONTEO SOLO ARRASTRA LOS JOIN QUE ALGÚN FILTRO NECESITA.
         *
         * Sin filtros, contar la bandeja con las tres uniones colgando cuesta
         * 1.083 ms; sin ellas, 378 ms. La diferencia es que `cliente` (50.072)
         * y `usuario` (50.182) también tienen RLS, así que el motor evalúa
         * `esta_en_horario()` también sobre sus filas para un JOIN cuyo único
         * efecto sobre el número es ninguno.
         *
         * Es seguro dejarlos fuera:
         *   · los dos LEFT JOIN no pueden cambiar el número de filas, nunca;
         *   · el JOIN a `cliente` es INNER, pero `ticket_soporte.cliente_id` es
         *     NOT NULL con FK y hoy hay 0 huérfanos (verificado), así que
         *     tampoco lo cambia.
         * Es el mismo recorte que ya aplica `VentasService.listarPedidos`.
         */
        final String tablaConteo = "FROM ticket_soporte t"
                + (q != null   ? " JOIN cliente c ON c.id = t.cliente_id\n" : "")
                + (cat != null ? " LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id\n" : "")
                + (ag != null  ? " LEFT JOIN usuario u ON u.id = t.asignado_usuario_id\n" : "");

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
                "SELECT count(*) " + tablaConteo + filtro, args, page, size);

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

        // `ticket_soporte` (179.851) no tiene índice por fecha, y la corrección
        // gana igual: 663 → 29,1 ms, porque esta_en_horario() deja de llamarse
        // fila a fila. `filtroDia` produce cláusulas «AND ...», que es
        // exactamente lo que hace falta tanto en un ON como en un WHERE.
        String[] ts = instantesDelDia(d, h);

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
                """ + filtroDia("t.fecha_creacion", ts) + """
                GROUP BY ct.id, ct.nombre, ct.prioridad_defecto
                ORDER BY tickets DESC, ct.nombre""",
                conLimites(new Object[0], ts));

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS tickets,
                       count(*) FILTER (WHERE t.estado NOT IN ('resuelto', 'cerrado'))
                           AS sin_resolver,
                       count(DISTINCT t.categoria_ticket_id) AS categorias_con_tickets,
                       count(*) FILTER (WHERE t.categoria_ticket_id IS NULL) AS sin_categoria
                FROM ticket_soporte t
                WHERE 1 = 1
                """ + filtroDia("t.fecha_creacion", ts),
                conLimites(new Object[0], ts));

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

        String[] ts = instantesDelDia(d, h);
        final String filtro = " WHERE 1 = 1\n" + filtroDia("t.fecha_creacion", ts);
        Object[] args = conLimites(new Object[0], ts);

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
