package com.retailmind.informes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INFORMES TÁCTICOS DE GERENCIA / DIRECCIÓN — los cinco objetivos del catálogo
 * ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §9) que se resuelven con
 * una consulta directa a PostgreSQL:
 *
 * <ul>
 *   <li><b>OTD-GER-01</b> {@link #fotoDia} — pedidos, cobros y pendientes que
 *       necesitan decisión.</li>
 *   <li><b>OTD-GER-04</b> {@link #cupones} — cupones y sus usos restantes.</li>
 *   <li><b>OTD-GER-06</b> {@link #marketing} — promociones, campañas y banners
 *       con su vigencia.</li>
 *   <li><b>OTD-GER-08</b> {@link #auditoria} — quién hizo qué (SENSIBLE).</li>
 *   <li><b>OTD-GER-09</b> {@link #accesos} — intentos de entrada al sistema
 *       (SENSIBLE).</li>
 * </ul>
 *
 * Los COMPUESTOS de Gerencia (balanza ingresos/egresos por mes — GER-02,
 * ganancia por categoría — GER-03, descuento otorgado por cupón — GER-05,
 * efecto de las promociones en la venta — GER-07, margen por período — GER-10,
 * costo de los descuentos — GER-11) NO viven aquí: pertenecen a la fase
 * ETL → ClickHouse.
 *
 * TODO método va en {@code @Transactional(readOnly = true)} para que
 * PgSessionRoleAspect asuma el grp_* del usuario: sin transacción la consulta
 * corre como retailmind_app (sin privilegios) y se saltaría RLS y horario.
 *
 * <h2>Datos sensibles de seguridad: el corte más estricto del sistema</h2>
 *
 * OTD-GER-08 y OTD-GER-09 no son informes de negocio sino de SEGURIDAD: dicen
 * quién tocó qué registro y quién intentó entrar al sistema. SecurityConfig los
 * cierra a ADMIN y GERENTE, y en el motor:
 * <ul>
 *   <li>{@code log_acceso}: solo grp_administrador y grp_gerente tienen SELECT.
 *       La RUTA y el MOTOR dicen lo mismo — cualquier otro rol que llegara al
 *       endpoint recibiría 42501 → 403.</li>
 *   <li>{@code log_auditoria}: grp_analista TAMBIÉN tiene SELECT (script 19),
 *       así que para ANALISTA la barrera es la RUTA y no el motor. Queda
 *       declarado aquí para que nadie amplíe la lista de roles del endpoint
 *       «porque la BD lo deja»: el catálogo asigna este objetivo a
 *       Administración y Gerencia, y punto.</li>
 * </ul>
 * Ninguna de las dos tablas tiene RLS (no la necesitan: son de solo lectura
 * para quien puede verlas), y por eso el control vive entero en esas dos capas.
 */
@Service
public class InformesGerenciaService extends InformeServiceBase {

    /** Situación calculada HOY de un cupón; no es una columna de la tabla. */
    private static final Set<String> SITUACIONES_CUPON = Set.of(
            "vigente", "programado", "vencido", "agotado", "inactivo");

    /** Espeja cupon_tipo_descuento_check. */
    private static final Set<String> TIPOS_DESCUENTO = Set.of(
            "porcentaje", "monto_fijo", "envio_gratis");

    /** Las tres piezas de marketing que el panel de vigencias reúne. */
    private static final Set<String> TIPOS_MARKETING = Set.of("promocion", "campana", "banner");

    /** Vigencia calculada HOY sobre fecha_inicio/fecha_fin + activo. */
    private static final Set<String> VIGENCIAS = Set.of(
            "vigente", "programado", "finalizado", "inactivo");

    /** Espeja log_auditoria_accion_check. */
    private static final Set<String> ACCIONES = Set.of(
            "insert", "update", "delete", "login", "logout", "otro");

    /**
     * Tablas que AuditoriaService escribe hoy (script 42 + fases 44/45). Es
     * lista blanca de filtro, no de visualización: si mañana se audita una
     * tabla más, el informe la muestra igual — solo no se podrá filtrar por
     * ella hasta añadirla aquí.
     */
    private static final Set<String> TABLAS_AUDITADAS = Set.of(
            "pedido", "envio", "orden_compra", "factura_compra", "resena",
            "pregunta_producto", "novedad_envio", "devolucion_proveedor",
            "item_defectuoso", "producto_proveedor");

    /**
     * Resultado de un intento de acceso: los dos desenlaces + los cuatro
     * motivos de fallo que escribe el login (script 53, LoginFallidoException).
     */
    private static final Set<String> RESULTADOS_ACCESO = Set.of(
            "exitoso", "fallido",
            "password_incorrecto", "email_no_registrado", "fuera_horario", "usuario_inactivo");

    public InformesGerenciaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        super(pg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-GER-01 — Foto del día
    // ─────────────────────────────────────────────────────────────────────

    /**
     * La foto del día: qué se pidió, qué se cobró y qué está esperando una
     * decisión.
     *
     * El informe son CUATRO bloques de agregados en una sola tabla
     * (bloque / concepto / cantidad / monto), porque la pregunta de dirección
     * no es «dame la lista de pedidos» sino «cómo vamos hoy»:
     * <ol>
     *   <li><b>Pedidos del día</b>: los pedidos creados en la fecha
     *       consultada, abiertos por estado.</li>
     *   <li><b>Cobros del día</b>: el dinero que entró de verdad (pagos
     *       {@code completado}) por método, más los intentos rechazados.</li>
     *   <li><b>Facturación del día</b>: facturas de venta emitidas, sin las
     *       anuladas.</li>
     *   <li><b>Pendientes que necesitan decisión</b>: la cola de mando AL
     *       MOMENTO —no de la fecha consultada—, porque una aprobación de
     *       compra pendiente lo está hoy, mire uno el día que mire. Va
     *       etiquetado como tal en la columna «nota» para que no se lea como
     *       parte del día.</li>
     * </ol>
     *
     * LÍMITE TEMPORAL DE LOS DATOS: la fecha por defecto es HOY, pero la carga
     * de datos del sistema llega hasta el 2026-07-22 en pedidos y el 2026-07-23
     * en cobros. Consultar un día posterior devuelve los bloques 1-3 vacíos, y
     * eso es correcto: no hubo actividad. Para que el usuario no lo confunda
     * con una avería, el resumen SIEMPRE incluye «Último día con pedidos», que
     * es la fecha a la que conviene mover el filtro.
     *
     * Filtro: fecha (AAAA-MM-DD). Sin paginar: son agregados, no un listado.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> fotoDia(String fecha) {
        String f = fecha(fecha, "fecha");

        // Un solo parámetro para toda la consulta: el día se resuelve una vez
        // en la CTE y los tres bloques del día lo cruzan.
        List<Map<String, Object>> items = pg.queryForList("""
                WITH d AS (SELECT COALESCE(?::date, CURRENT_DATE) AS f)
                SELECT * FROM (
                    -- Fila 0: el día no tuvo NADA. Se dice explícitamente en vez
                    -- de dejar la tabla con solo pendientes, que se leería como
                    -- si el informe estuviera roto.
                    SELECT 0 AS orden, 'Día sin movimiento' AS bloque,
                           'Sin pedidos, cobros ni facturas en la fecha consultada' AS concepto,
                           0::bigint AS cantidad, NULL::numeric AS monto,
                           'El resumen indica el último día con pedidos' AS nota
                    FROM d
                    WHERE NOT EXISTS (SELECT 1 FROM pedido p
                                       WHERE p.fecha_pedido >= d.f AND p.fecha_pedido < d.f + 1)
                      AND NOT EXISTS (SELECT 1 FROM pago pg
                                       WHERE pg.estado = 'completado'
                                         AND pg.fecha_pago >= d.f AND pg.fecha_pago < d.f + 1)
                      AND NOT EXISTS (SELECT 1 FROM factura_venta fv
                                       WHERE fv.estado <> 'anulada'
                                         AND fv.fecha_emision >= d.f
                                         AND fv.fecha_emision < d.f + 1)

                    UNION ALL
                    SELECT 1, 'Pedidos del día',
                           ep.nombre AS concepto, count(*) AS cantidad,
                           round(sum(p.total), 2) AS monto,
                           'Pedidos creados en la fecha consultada' AS nota
                    FROM pedido p
                    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                    CROSS JOIN d
                    WHERE p.fecha_pedido >= d.f AND p.fecha_pedido < d.f + 1
                    GROUP BY ep.nombre

                    UNION ALL
                    SELECT 2, 'Cobros del día', mp.nombre, count(*),
                           round(sum(pg.monto), 2), 'Dinero efectivamente cobrado'
                    FROM pago pg
                    JOIN metodo_pago mp ON mp.id = pg.metodo_pago_id
                    CROSS JOIN d
                    WHERE pg.estado = 'completado'
                      AND pg.fecha_pago >= d.f AND pg.fecha_pago < d.f + 1
                    GROUP BY mp.nombre

                    UNION ALL
                    SELECT 2, 'Cobros del día', 'Intentos de pago rechazados', count(*), NULL,
                           'No entraron a caja'
                    FROM pago pg CROSS JOIN d
                    WHERE pg.estado = 'fallido'
                      AND pg.fecha_creacion >= d.f AND pg.fecha_creacion < d.f + 1
                    HAVING count(*) > 0

                    UNION ALL
                    SELECT 3, 'Facturación del día', 'Facturas de venta emitidas', count(*),
                           round(sum(fv.total), 2), 'Excluye las anuladas'
                    FROM factura_venta fv CROSS JOIN d
                    WHERE fv.estado <> 'anulada'
                      AND fv.fecha_emision >= d.f AND fv.fecha_emision < d.f + 1
                    HAVING count(*) > 0

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Órdenes de compra por aprobar',
                           count(*), round(COALESCE(sum(oc.total), 0), 2),
                           'Al momento · sin aprobar no se recibe'
                    FROM orden_compra oc WHERE oc.estado IN ('borrador', 'enviada')

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Cuentas por pagar vencidas',
                           count(*), round(COALESCE(sum(cp.saldo_pendiente), 0), 2),
                           'Al momento · pasadas de su fecha de vencimiento'
                    FROM cuenta_por_pagar cp
                    WHERE cp.saldo_pendiente > 0 AND cp.fecha_vencimiento < CURRENT_DATE

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión',
                           'Devoluciones esperando validación', count(*), NULL,
                           'Al momento · RMA solicitada o en revisión'
                    FROM devolucion dv WHERE dv.estado IN ('solicitada', 'en_revision')

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión',
                           'Devoluciones listas para reembolso', count(*), NULL,
                           'Al momento · inspeccionadas, deciden Gerencia o Administración'
                    FROM devolucion dv WHERE dv.estado = 'inspeccionada'

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Pedidos preparados sin salir',
                           count(*), NULL, 'Al momento · listos en bodega'
                    FROM pedido p
                    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                    WHERE ep.codigo = 'preparado'

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Envíos con novedad abierta',
                           count(*), NULL, 'Al momento · bloquean la entrega'
                    FROM novedad_envio n WHERE n.estado = 'abierta'

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Tickets críticos sin resolver',
                           count(*), NULL, 'Al momento · prioridad urgente o alta'
                    FROM ticket_soporte t
                    WHERE t.estado NOT IN ('resuelto', 'cerrado')
                      AND t.prioridad IN ('urgente', 'alta')

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión', 'Reseñas por moderar',
                           count(*), NULL, 'Al momento · sin publicar'
                    FROM resena r WHERE r.estado = 'pendiente'

                    UNION ALL
                    SELECT 4, 'Pendientes que necesitan decisión',
                           'Defectuosos sin devolver al proveedor', count(*), NULL,
                           'Al momento · pool de ítems defectuosos'
                    FROM item_defectuoso i WHERE i.estado = 'pendiente'
                ) foto
                WHERE cantidad > 0 OR orden IN (0, 4)
                ORDER BY orden, cantidad DESC, concepto""", f);

        // Las dos fechas del resumen viajan ya FORMATEADAS como texto: un `date`
        // puro se serializa «2026-07-26» y el formateador de la pantalla lo
        // interpreta como medianoche UTC, restando un día en América/Guayaquil.
        // Las columnas de fecha de los demás informes son timestamptz y no
        // tienen ese problema; aquí el dato ES un día, no un instante.
        Map<String, Object> tot = pg.queryForMap("""
                WITH d AS (SELECT COALESCE(?::date, CURRENT_DATE) AS f)
                SELECT to_char(d.f, 'DD/MM/YYYY') AS dia,
                       (SELECT count(*) FROM pedido p
                         WHERE p.fecha_pedido >= d.f AND p.fecha_pedido < d.f + 1) AS pedidos,
                       (SELECT COALESCE(round(sum(p.total), 2), 0) FROM pedido p
                         WHERE p.fecha_pedido >= d.f AND p.fecha_pedido < d.f + 1)
                           AS monto_pedidos,
                       (SELECT COALESCE(round(sum(pg.monto), 2), 0) FROM pago pg
                         WHERE pg.estado = 'completado'
                           AND pg.fecha_pago >= d.f AND pg.fecha_pago < d.f + 1) AS cobrado,
                       (SELECT to_char(max(p.fecha_pedido), 'DD/MM/YYYY') FROM pedido p)
                           AS ultimo_dia
                FROM d""", f);

        return conResumen(sobre(items), List.of(
                kpi("Día consultado", tot.get("dia"), "texto"),
                kpi("Pedidos del día", tot.get("pedidos"), "numero"),
                kpi("Valor de los pedidos", tot.get("monto_pedidos"), "moneda"),
                kpi("Cobrado en el día", tot.get("cobrado"), "moneda"),
                kpi("Último día con pedidos", tot.get("ultimo_dia"), "texto")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-GER-04 — Cupones y usos restantes
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Qué cupones están sueltos ahí fuera: cuánto descuentan, cuántos usos les
     * quedan y cuándo dejan de valer.
     *
     * {@code situacion} NO es una columna: se recalcula ahora mismo con las
     * TRES condiciones que {@code DescuentosService} exige al canjear un cupón
     * —activo, dentro de la ventana y por debajo de {@code usos_maximos}—, en
     * ese mismo orden de precedencia. Así el informe dice lo que diría el
     * checkout, y no «activo = true» que engañaría con un cupón caducado o
     * agotado. El filtro arranca en «vigente», que es la pregunta de dirección.
     *
     * {@code usos_restantes} queda NULL cuando el cupón no tiene tope: un cero
     * ahí se leería como agotado, que es justo lo contrario.
     *
     * Filtros: situación, tipo de descuento y búsqueda por código o
     * descripción. Paginado (33 cupones; 7 vigentes hoy).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cupones(String situacion, String tipo, String buscar,
                                       int page, int size) {
        String sit = opcion(situacion, SITUACIONES_CUPON, "situacion");
        String tp = opcion(tipo, TIPOS_DESCUENTO, "tipo");
        String q = texto(buscar);

        // La situación se calcula UNA vez en la CTE y se filtra fuera: repetir
        // el CASE en el WHERE sería la misma regla escrita dos veces.
        final String base = """
                WITH cup AS (
                    SELECT c.id, c.codigo, c.descripcion, c.tipo_descuento, c.valor,
                           c.monto_minimo_pedido, c.usos_maximos, c.usos_actuales,
                           c.usos_por_cliente, c.fecha_inicio, c.fecha_fin, c.activo,
                           CASE WHEN c.usos_maximos IS NULL THEN NULL
                                ELSE greatest(c.usos_maximos - c.usos_actuales, 0) END
                               AS usos_restantes,
                           CASE WHEN c.fecha_fin IS NULL THEN NULL
                                ELSE (c.fecha_fin::date - CURRENT_DATE) END AS dias_para_vencer,
                           CASE WHEN NOT c.activo THEN 'inactivo'
                                WHEN c.fecha_inicio > now() THEN 'programado'
                                WHEN c.fecha_fin IS NOT NULL AND c.fecha_fin < now()
                                     THEN 'vencido'
                                WHEN c.usos_maximos IS NOT NULL
                                     AND c.usos_actuales >= c.usos_maximos THEN 'agotado'
                                ELSE 'vigente' END AS situacion
                    FROM cupon c
                )
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR situacion = ?::varchar)
                  AND (?::varchar IS NULL OR tipo_descuento = ?::varchar)
                  AND (?::varchar IS NULL OR codigo ILIKE '%' || ?::varchar || '%'
                       OR descripcion ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { sit, sit, tp, tp, q, q, q };

        Map<String, Object> res = paginar(
                base + "SELECT * FROM cup " + filtro
                     + " ORDER BY (situacion = 'vigente') DESC, fecha_fin NULLS LAST, codigo",
                base + "SELECT count(*) FROM cup " + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap(base + """
                SELECT count(*) AS cupones,
                       count(*) FILTER (WHERE situacion = 'vigente') AS vigentes,
                       count(*) FILTER (WHERE situacion = 'vigente'
                                          AND dias_para_vencer IS NOT NULL
                                          AND dias_para_vencer <= 7) AS vencen_pronto,
                       COALESCE(sum(usos_actuales), 0) AS usos,
                       COALESCE(sum(usos_restantes) FILTER (WHERE situacion = 'vigente'), 0)
                           AS usos_disponibles
                FROM cup""" + " " + filtro, args);

        return conResumen(res, List.of(
                kpi("Cupones", tot.get("cupones"), "numero"),
                kpi("Vigentes hoy", tot.get("vigentes"), "numero"),
                kpi("Vencen en 7 días o menos", tot.get("vencen_pronto"), "numero"),
                kpi("Usos ya consumidos", tot.get("usos"), "numero"),
                kpi("Usos aún disponibles", tot.get("usos_disponibles"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-GER-06 — Marketing vigente
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Qué está corriendo ahora mismo en marketing: promociones (y cuántos
     * productos abarcan), campañas y banners, con sus fechas.
     *
     * Las tres tablas se unen en un solo listado porque la pregunta de
     * dirección es «qué tengo en la calle hoy», no «enséñame la tabla
     * promoción». La {@code vigencia} se recalcula igual para las tres —
     * activo + ventana de fechas — y por eso son comparables: sin ese
     * denominador común, {@code campana.estado = 'activa'} y
     * {@code banner.activo} dirían cosas distintas.
     *
     * Detalles por tipo, en la columna {@code alcance}: la promoción trae
     * cuántos productos cubre (de {@code promocion_producto}, que es lo que
     * decide si el descuento se aplica a una línea) y la campaña cuántos
     * banners cuelgan de ella. {@code campana.presupuesto} es el único monto
     * del informe y solo llega a ADMIN/GERENTE, que son sus únicos
     * destinatarios.
     *
     * Filtros: tipo de pieza y vigencia (arranca en «vigente»). Paginado
     * (65 piezas en total; 20 vigentes hoy).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> marketing(String tipo, String vigencia, String buscar,
                                         int page, int size) {
        String tp = opcion(tipo, TIPOS_MARKETING, "tipo");
        String vig = opcion(vigencia, VIGENCIAS, "vigencia");
        String q = texto(buscar);

        final String base = """
                WITH mk AS (
                    SELECT 'promocion' AS tipo, p.id, p.nombre, p.descripcion AS detalle,
                           p.tipo_descuento, p.valor, p.fecha_inicio, p.fecha_fin, p.activo,
                           (SELECT count(*) FROM promocion_producto pp
                             WHERE pp.promocion_id = p.id) AS alcance,
                           'productos alcanzados' AS alcance_nota,
                           NULL::varchar AS canal, NULL::numeric AS presupuesto,
                           p.prioridad::int AS prioridad, p.acumulable
                    FROM promocion p
                    UNION ALL
                    SELECT 'campana', c.id, c.nombre, c.descripcion, NULL, NULL,
                           c.fecha_inicio::timestamptz, c.fecha_fin::timestamptz,
                           (c.estado = 'activa'),
                           (SELECT count(*) FROM banner b WHERE b.campana_id = c.id),
                           'banners de la campaña', c.canal, c.presupuesto, NULL, NULL
                    FROM campana c
                    UNION ALL
                    SELECT 'banner', b.id, b.titulo, b.posicion, NULL, NULL,
                           b.fecha_inicio, b.fecha_fin, b.activo,
                           b.orden, 'orden en la posición', NULL, NULL, NULL, NULL
                    FROM banner b
                ), mkv AS (
                    SELECT mk.*,
                           CASE WHEN NOT activo THEN 'inactivo'
                                WHEN fecha_inicio > now() THEN 'programado'
                                WHEN fecha_fin IS NOT NULL AND fecha_fin < now()
                                     THEN 'finalizado'
                                ELSE 'vigente' END AS vigencia,
                           CASE WHEN fecha_fin IS NULL THEN NULL
                                ELSE (fecha_fin::date - CURRENT_DATE) END AS dias_restantes
                    FROM mk
                )
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR tipo = ?::varchar)
                  AND (?::varchar IS NULL OR vigencia = ?::varchar)
                  AND (?::varchar IS NULL OR nombre ILIKE '%' || ?::varchar || '%'
                       OR detalle ILIKE '%' || ?::varchar || '%')
                """;
        Object[] args = { tp, tp, vig, vig, q, q, q };

        Map<String, Object> res = paginar(
                base + "SELECT * FROM mkv " + filtro
                     + " ORDER BY (vigencia = 'vigente') DESC, fecha_inicio DESC, tipo, nombre",
                base + "SELECT count(*) FROM mkv " + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap(base + """
                SELECT count(*) FILTER (WHERE vigencia = 'vigente') AS vigentes,
                       count(*) FILTER (WHERE vigencia = 'vigente' AND tipo = 'promocion')
                           AS promociones,
                       count(*) FILTER (WHERE vigencia = 'vigente' AND tipo = 'campana')
                           AS campanas,
                       count(*) FILTER (WHERE vigencia = 'vigente' AND tipo = 'banner')
                           AS banners,
                       COALESCE(round(sum(presupuesto) FILTER (WHERE vigencia = 'vigente'), 2), 0)
                           AS presupuesto
                FROM mkv""" + " " + filtro, args);

        return conResumen(res, List.of(
                kpi("Piezas vigentes hoy", tot.get("vigentes"), "numero"),
                kpi("Promociones", tot.get("promociones"), "numero"),
                kpi("Campañas", tot.get("campanas"), "numero"),
                kpi("Banners", tot.get("banners"), "numero"),
                kpi("Presupuesto en campañas vigentes", tot.get("presupuesto"), "moneda")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-GER-08 — Auditoría del sistema (SENSIBLE: ADMIN / GERENTE)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Quién hizo qué: aprobaciones de compra, despachos, registros de factura y
     * moderaciones, con autor, fecha, registro afectado y el antes/después del
     * cambio.
     *
     * DATO SENSIBLE DE SEGURIDAD — solo ADMIN y GERENTE (ver javadoc de clase).
     * Para ANALISTA la barrera es la RUTA: el motor le daría el SELECT.
     *
     * {@code datos_anteriores} y {@code datos_nuevos} son jsonb y se devuelven
     * como TEXTO (`::text`): el informe los muestra tal cual, sin interpretar,
     * porque cada acción guarda su propia forma y aplanarlos a columnas fijas
     * perdería justo el detalle por el que existe la auditoría.
     *
     * El autor sale de la FK a {@code usuario} (el id del JWT, script 42) y
     * queda «(sistema)» cuando es NULL: el checkout online no escribe auditoría
     * a propósito —grp_cliente no tiene INSERT— y ahí el autor es el cliente,
     * trazado por {@code pedido.cliente_id} y su historial.
     *
     * Filtros: usuario (nombre o correo), tabla, acción y rango de fechas.
     * Paginado (7.073 registros).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> auditoria(String usuario, String tabla, String accion,
                                         String desde, String hasta, int page, int size) {
        String us = texto(usuario);
        String tb = opcion(tabla, TABLAS_AUDITADAS, "tabla");
        String ac = opcion(accion, ACCIONES, "accion");
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        // La acción se guarda en MAYÚSCULAS (CHECK del script 42) y la lista
        // blanca normaliza a minúsculas: se compara en upper, no se concatena.
        final String tablaSql = """
                FROM log_auditoria a
                LEFT JOIN usuario u ON u.id = a.usuario_id
                """;
        final String filtro = """
                WHERE (?::varchar IS NULL OR u.nombre ILIKE '%' || ?::varchar || '%'
                       OR u.apellido ILIKE '%' || ?::varchar || '%'
                       OR u.email ILIKE '%' || ?::varchar || '%')
                  AND (?::varchar IS NULL OR a.tabla = ?::varchar)
                  AND (?::varchar IS NULL OR a.accion = upper(?::varchar))
                  AND (?::date IS NULL OR a.fecha_creacion >= ?::date)
                  AND (?::date IS NULL OR a.fecha_creacion <  (?::date + 1))
                """;
        Object[] args = { us, us, us, us, tb, tb, ac, ac, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT a.id, a.fecha_creacion, a.accion, a.tabla, a.registro_id,
                       COALESCE(NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), ''),
                                '(sistema)') AS autor,
                       u.email AS correo,
                       a.ip_origen::text AS ip,
                       a.datos_anteriores::text AS antes,
                       a.datos_nuevos::text AS despues
                """ + tablaSql + filtro
                + " ORDER BY a.fecha_creacion DESC, a.id DESC",
                "SELECT count(*) " + tablaSql + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS registros,
                       count(DISTINCT a.usuario_id) AS autores,
                       count(DISTINCT a.tabla) AS tablas,
                       count(*) FILTER (WHERE a.accion = 'INSERT') AS altas,
                       count(*) FILTER (WHERE a.accion = 'UPDATE') AS cambios
                """ + tablaSql + filtro, args);

        return conResumen(res, List.of(
                kpi("Acciones registradas", tot.get("registros"), "numero"),
                kpi("Autores distintos", tot.get("autores"), "numero"),
                kpi("Tablas afectadas", tot.get("tablas"), "numero"),
                kpi("Altas", tot.get("altas"), "numero"),
                kpi("Modificaciones", tot.get("cambios"), "numero")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // OTD-GER-09 — Intentos de acceso (SENSIBLE: ADMIN / GERENTE)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Quién entró y quién no pudo: inicios de sesión exitosos y fallidos, con
     * el correo que se intentó, la IP de origen y el motivo del rechazo.
     *
     * DATO SENSIBLE DE SEGURIDAD — solo ADMIN y GERENTE. Aquí la RUTA y el
     * MOTOR coinciden: solo grp_administrador y grp_gerente tienen SELECT sobre
     * {@code log_acceso} (script 53), así que cualquier otro rol que llegara al
     * endpoint recibiría 42501 → 403 igualmente.
     *
     * El filtro {@code resultado} admite los dos desenlaces («exitoso»,
     * «fallido») y también un motivo concreto de fallo, porque son la misma
     * pregunta a distinta profundidad: {@code fuera_horario} no es un error de
     * credenciales sino la ventana horaria del grupo bloqueando el login
     * (script 53), y separarlo del resto es lo que hace útil el informe.
     *
     * {@code email_intentado} se muestra tal cual se tecleó y {@code usuario}
     * solo aparece cuando ese correo existía: un intento contra un correo no
     * registrado no tiene usuario que mostrar, y ese hueco ES el dato.
     *
     * Filtros: resultado, correo y rango de fechas. Paginado (1.495 intentos).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> accesos(String resultado, String correo, String desde,
                                       String hasta, int page, int size) {
        String r = opcion(resultado, RESULTADOS_ACCESO, "resultado");
        String c = texto(correo);
        String d = fecha(desde, "desde");
        String h = fecha(hasta, "hasta");
        exigirRangoValido(d, h);

        final String tablaSql = """
                FROM log_acceso l
                LEFT JOIN usuario u ON u.id = l.usuario_id
                """;
        // Un solo parámetro cubre desenlace y motivo: 'exitoso'/'fallido'
        // resuelven por la bandera y cualquier otro valor de la lista blanca
        // cae contra motivo_fallo.
        final String filtro = """
                WHERE (?::varchar IS NULL
                       OR (?::varchar = 'exitoso' AND l.exitoso)
                       OR (?::varchar = 'fallido' AND NOT l.exitoso)
                       OR l.motivo_fallo = ?::varchar)
                  AND (?::varchar IS NULL OR l.email_intentado ILIKE '%' || ?::varchar || '%')
                  AND (?::date IS NULL OR l.fecha_creacion >= ?::date)
                  AND (?::date IS NULL OR l.fecha_creacion <  (?::date + 1))
                """;
        Object[] args = { r, r, r, r, c, c, d, d, h, h };

        Map<String, Object> res = paginar("""
                SELECT l.id, l.fecha_creacion, l.exitoso, l.motivo_fallo,
                       l.email_intentado,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '')
                           AS usuario,
                       l.ip_origen::text AS ip, l.user_agent
                """ + tablaSql + filtro
                + " ORDER BY l.fecha_creacion DESC, l.id DESC",
                "SELECT count(*) " + tablaSql + filtro, args, page, size);

        Map<String, Object> tot = pg.queryForMap("""
                SELECT count(*) AS intentos,
                       count(*) FILTER (WHERE l.exitoso) AS exitosos,
                       count(*) FILTER (WHERE NOT l.exitoso) AS fallidos,
                       count(*) FILTER (WHERE l.motivo_fallo = 'password_incorrecto')
                           AS password_malo,
                       count(*) FILTER (WHERE l.motivo_fallo = 'fuera_horario') AS fuera_horario,
                       count(DISTINCT l.email_intentado) AS correos
                """ + tablaSql + filtro, args);

        return conResumen(res, List.of(
                kpi("Intentos", tot.get("intentos"), "numero"),
                kpi("Exitosos", tot.get("exitosos"), "numero"),
                kpi("Fallidos", tot.get("fallidos"), "numero"),
                kpi("Contraseña incorrecta", tot.get("password_malo"), "numero"),
                kpi("Bloqueados por horario", tot.get("fuera_horario"), "numero"),
                kpi("Correos distintos", tot.get("correos"), "numero")));
    }
}
