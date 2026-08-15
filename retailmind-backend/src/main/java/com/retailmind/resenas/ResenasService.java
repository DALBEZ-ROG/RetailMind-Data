package com.retailmind.resenas;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;
import com.retailmind.auth.AppUserPrincipal;

/**
 * Módulo de reseñas y preguntas de producto sobre PostgreSQL
 * (resena, resena_util, reporte_resena, pregunta_producto, respuesta_pregunta).
 *
 * Todo dentro de @Transactional para que PgSessionRoleAspect asuma el rol de
 * grupo (grp_administrador/grp_gerente moderan y responden; grp_cliente crea
 * reseñas/preguntas, vota utilidad y reporta abusos). El autor SIEMPRE sale
 * del JWT, nunca del body.
 *
 * Seguridad de motor (script 32_grants_resenas.sql):
 *  - resena tiene RLS pol_cliente_propio (el cliente solo ve/escribe SUS filas)
 *    más la política nueva pol_resena_publica que le deja LEER las aprobadas
 *    de cualquier cliente (listado público y guardias de voto/reporte).
 *  - resena_util / reporte_resena / pregunta_producto / respuesta_pregunta NO
 *    tienen RLS: el aislamiento del cliente se aplica aquí por propiedad
 *    (cliente_id del JWT), como en soporte.
 *  - grp_cliente no tiene SELECT sobre usuario: las consultas del cliente no
 *    tocan esa tabla (las respuestas oficiales se firman como "RetailMind") y
 *    el nombre de otros clientes se degrada a "Cliente" (RLS sobre cliente).
 *
 * Nunca se escriben fecha_creacion (default) ni fecha_actualizacion (trigger
 * touch). Reseñar EXIGE compra verificada: solo se acepta la reseña si existe
 * un pedido pagado/entregado del propio cliente que contenga el producto
 * (ESTADOS_COMPRA); compra_verificada queda siempre en true para las nuevas.
 */
@Service
public class ResenasService {

    /** Espeja el CHECK reporte_resena_motivo_check. */
    private static final Set<String> MOTIVOS_REPORTE = Set.of("ofensivo", "spam", "falso", "otro");

    /**
     * Estados de pedido que cuentan como COMPRA para reseñar (compra
     * verificada): pagado en adelante, incluido 'devuelto' (compró y devolvió:
     * su opinión sigue siendo legítima). Excluye pendiente/confirmado (aún no
     * paga) y cancelado. Lista blanca embebida en SQL, nunca input del usuario.
     */
    private static final String ESTADOS_COMPRA =
            "('pagado','facturado','en_preparacion','preparado','despachado','entregado','devuelto')";

    /** Transiciones de moderación de reseña (CHECK: pendiente/aprobada/rechazada). */
    private static final Map<String, Set<String>> TRANSICIONES_RESENA = Map.of(
            "pendiente", Set.of("aprobada", "rechazada"),
            "aprobada", Set.of("rechazada"),
            "rechazada", Set.of("aprobada"));

    /** Transiciones de moderación de pregunta (CHECK: pendiente/publicada/rechazada). */
    private static final Map<String, Set<String>> TRANSICIONES_PREGUNTA = Map.of(
            "pendiente", Set.of("publicada", "rechazada"),
            "publicada", Set.of("rechazada"),
            "rechazada", Set.of("publicada"));

    /** Transiciones del reporte (CHECK: pendiente/atendido/descartado; ambos terminales). */
    private static final Map<String, Set<String>> TRANSICIONES_REPORTE = Map.of(
            "pendiente", Set.of("atendido", "descartado"),
            "atendido", Set.of(),
            "descartado", Set.of());

    private final JdbcTemplate pg;
    private final AuditoriaService auditoria;

    public ResenasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                          AuditoriaService auditoria) {
        this.pg = pg;
        this.auditoria = auditoria;
    }

    // ── Reseñas ──────────────────────────────────────────────────────────

    private static final String SEL_RESENAS = """
            SELECT r.id, r.producto_id, p.nombre AS producto, r.cliente_id,
                   c.nombre AS cliente, r.calificacion, r.titulo, r.comentario,
                   r.compra_verificada, r.estado, r.fecha_creacion,
                   (SELECT count(*) FROM resena_util v WHERE v.resena_id = r.id AND v.es_util)     AS utiles,
                   (SELECT count(*) FROM resena_util v WHERE v.resena_id = r.id AND NOT v.es_util) AS no_utiles,
                   (SELECT count(*) FROM reporte_resena rr
                    WHERE rr.resena_id = r.id AND rr.estado = 'pendiente') AS reportes_pendientes
            """;

    private static final String JOIN_RESENAS = """
            FROM resena r
            JOIN producto p ON p.id = r.producto_id
            LEFT JOIN cliente c ON c.id = r.cliente_id
            """;

    /** Traducción del filtro «reportadas» de la pantalla (con / sin). */
    private static final String W_CON_REPORTES = """
             AND EXISTS (SELECT 1 FROM reporte_resena rr
                         WHERE rr.resena_id = r.id AND rr.estado = 'pendiente')
            """;
    private static final String W_SIN_REPORTES = """
             AND NOT EXISTS (SELECT 1 FROM reporte_resena rr
                             WHERE rr.resena_id = r.id AND rr.estado = 'pendiente')
            """;

    private static final java.util.Set<String> REPORTADAS = java.util.Set.of("todos", "con", "sin");

    /**
     * Bandeja de moderación de reseñas, PAGINADA EN EL SERVIDOR y con SUS
     * CUATRO CRITERIOS EN SQL.
     *
     * <h3>Por qué dejó de devolver una lista</h3>
     * Devolvía las 263.077 reseñas: **82,07 MB medidos**, el listado más grande
     * del sistema, en cada apertura de pantalla.
     *
     * <h3>Los cuatro criterios vivían en el navegador</h3>
     * `resenas.component.aplicarFiltros()` recorría el array completo con
     * estado, calificación, «reportadas» y un buscador de texto libre sobre
     * producto / cliente / título / comentario. El buscador es el caso más
     * claro del fallo silencioso: escribir el nombre de un producto que no está
     * entre las 25 primeras reseñas habría devuelto «sin resultados» con toda
     * naturalidad. Los cuatro se evalúan aquí, contra las 263.077, y `total`
     * cuenta el conjunto filtrado.
     *
     * <h3>El desempate del ORDER BY</h3>
     * `fecha_creacion` no es única en 263.077 filas; sin desempate por `r.id`
     * la misma reseña puede salir en dos páginas y otra en ninguna.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarResenas(String estado, Integer calificacion,
                                             String reportadas, String q,
                                             Integer page, Integer size, Boolean conTotal) {
        // El WHERE se arma SOLO con los filtros presentes; las piezas son
        // constantes del código y los valores viajan como parámetros ligados.
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> args = new java.util.ArrayList<>();

        if (estado != null && !estado.isBlank() && !"todos".equals(estado)) {
            validarEnLista(estado, TRANSICIONES_RESENA.keySet(), "estado de la reseña");
            w.append(" AND r.estado = ?\n");
            args.add(estado);
        }
        if (calificacion != null) {
            if (calificacion < 1 || calificacion > 5) {
                throw new IllegalArgumentException(
                        "La calificación del filtro debe estar entre 1 y 5: " + calificacion);
            }
            w.append(" AND r.calificacion = ?\n");
            args.add(calificacion);
        }
        if (reportadas != null && !reportadas.isBlank() && !"todos".equals(reportadas)) {
            validarEnLista(reportadas, REPORTADAS, "filtro de reportadas");
            w.append("con".equals(reportadas) ? W_CON_REPORTES : W_SIN_REPORTES);
        }
        String busq = (q == null || q.isBlank()) ? null : q.trim();
        boolean conTexto = busq != null;
        if (conTexto) {
            // Las MISMAS cuatro columnas que miraba el buscador del navegador.
            w.append(" AND (p.nombre ILIKE ? OR c.nombre ILIKE ?"
                   + " OR r.titulo ILIKE ? OR r.comentario ILIKE ?)\n");
            for (int i = 0; i < 4; i++) { args.add("%" + busq + "%"); }
        }
        String where = w.toString();
        Object[] a = args.toArray();

        String sqlItems = SEL_RESENAS + JOIN_RESENAS + where
                        + " ORDER BY r.fecha_creacion DESC, r.id DESC";

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }
        // El conteo solo arrastra los joins que el filtro necesita: sin
        // buscador de texto, ni `producto` ni `cliente` cambian el número.
        String sqlCount = "SELECT count(*) "
                + (conTexto ? JOIN_RESENAS : "FROM resena r") + where;
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, sqlCount, a, pag, tam);
    }

    /**
     * Listado público de un producto: SOLO reseñas aprobadas, con conteos de
     * utilidad. Para CLIENTE la política pol_resena_publica le deja leer las
     * aprobadas ajenas; su voto previo viaja como mi_voto y el nombre de otros
     * clientes se degrada a "Cliente" (RLS sobre cliente lo oculta).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarResenasProducto(long productoId) {
        Long clienteId = esCliente() ? clienteActualId() : null;
        return pg.queryForList("""
                SELECT r.id, r.calificacion, r.titulo, r.comentario, r.compra_verificada,
                       r.fecha_creacion, COALESCE(c.nombre, 'Cliente') AS cliente,
                       (r.cliente_id = ?::bigint) AS es_mia,
                       (SELECT count(*) FROM resena_util v WHERE v.resena_id = r.id AND v.es_util)     AS utiles,
                       (SELECT count(*) FROM resena_util v WHERE v.resena_id = r.id AND NOT v.es_util) AS no_utiles,
                       (SELECT v.es_util FROM resena_util v
                        WHERE v.resena_id = r.id AND v.cliente_id = ?::bigint) AS mi_voto
                FROM resena r
                LEFT JOIN cliente c ON c.id = r.cliente_id
                WHERE r.producto_id = ? AND r.estado = 'aprobada'
                ORDER BY r.fecha_creacion DESC""", clienteId, clienteId, productoId);
    }

    /** Reseñas del cliente autenticado en cualquier estado (RLS refuerza la propiedad). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarMisResenas() {
        return pg.queryForList("""
                SELECT r.id, r.producto_id, p.nombre AS producto, r.calificacion, r.titulo,
                       r.comentario, r.compra_verificada, r.estado, r.fecha_creacion,
                       (SELECT count(*) FROM resena_util v WHERE v.resena_id = r.id AND v.es_util) AS utiles
                FROM resena r
                JOIN producto p ON p.id = r.producto_id
                WHERE r.cliente_id = ?
                ORDER BY r.fecha_creacion DESC""", clienteActualId());
    }

    /**
     * El CLIENTE crea una reseña de un producto (una por producto, lo exige
     * uq_resena_producto_cliente). compra_verificada y pedido_id salen de sus
     * pedidos no cancelados que contengan el producto; nace 'pendiente' y no
     * es visible en el listado público hasta que moderación la apruebe.
     */
    @Transactional
    public long crearResena(Long productoId, Integer calificacion, String titulo,
                            String comentario) {
        if (productoId == null) {
            throw new IllegalArgumentException("Debe indicar el producto a reseñar");
        }
        if (calificacion == null || calificacion < 1 || calificacion > 5) {
            throw new IllegalArgumentException("La calificación debe estar entre 1 y 5");
        }
        long clienteId = clienteActualId();
        Integer existeProducto = pg.queryForObject(
                "SELECT count(*) FROM producto WHERE id = ? AND activo",
                Integer.class, productoId);
        if (existeProducto == null || existeProducto == 0) {
            throw new IllegalArgumentException("El producto no existe o está inactivo");
        }
        Integer repetida = pg.queryForObject(
                "SELECT count(*) FROM resena WHERE producto_id = ? AND cliente_id = ?",
                Integer.class, productoId, clienteId);
        if (repetida != null && repetida > 0) {
            throw new IllegalStateException("Ya escribiste una reseña de este producto");
        }
        // COMPRA VERIFICADA obligatoria: solo se reseña un producto contenido
        // en un pedido PAGADO del propio cliente (ESTADOS_COMPRA; RLS limita a
        // sus pedidos). Sin compra no hay reseña.
        List<Long> pedidos = pg.queryForList("""
                SELECT p.id
                FROM pedido p
                JOIN pedido_detalle pd ON pd.pedido_id = p.id
                JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.cliente_id = ? AND pv.producto_id = ?
                  AND ep.codigo IN """ + ESTADOS_COMPRA + """

                ORDER BY p.fecha_pedido DESC, p.id DESC
                LIMIT 1""", Long.class, clienteId, productoId);
        if (pedidos.isEmpty()) {
            throw new IllegalStateException("Solo puedes reseñar productos que has comprado");
        }
        Long pedidoId = pedidos.get(0);
        return idDe(pg.queryForObject("""
                INSERT INTO resena (producto_id, cliente_id, pedido_id, calificacion,
                                    titulo, comentario, compra_verificada)
                VALUES (?, ?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), true)
                RETURNING id""",
                Long.class, productoId, clienteId, pedidoId, calificacion,
                titulo == null ? null : titulo.trim(),
                comentario == null ? null : comentario.trim()));
    }

    /** Moderación (personal): aprobar/rechazar validando la transición. */
    @Transactional
    public void moderarResena(long id, String estado) {
        validarEnLista(estado, TRANSICIONES_RESENA.keySet(), "estado de la reseña");
        String actual = estadoDe("resena", "la reseña", id);
        if (estado.equals(actual)) {
            throw new IllegalStateException("La reseña ya está en estado '" + estado + "'");
        }
        Set<String> permitidas = TRANSICIONES_RESENA.get(actual);
        if (!permitidas.contains(estado)) {
            throw new IllegalStateException("Transición inválida: '" + actual + "' → '"
                    + estado + "'. Permitidas: " + String.join(", ", permitidas.stream().sorted().toList()));
        }
        // moderado_por/fecha_moderacion = autor del JWT (trazabilidad, script
        // 42); fecha_actualizacion NO se escribe (trigger touch).
        pg.update("""
                UPDATE resena SET estado = ?, moderado_por = ?, fecha_moderacion = now()
                WHERE id = ?""", estado, usuarioActualId(), id);
        auditoria.registrar("resena", id, "UPDATE",
                Map.of("estado", actual), Map.of("estado", estado));
    }

    // ── Votos de utilidad ────────────────────────────────────────────────

    /**
     * El CLIENTE vota una reseña aprobada como útil / no útil. Un voto por
     * cliente por reseña (uq_resena_util; guardia previa con mensaje claro) y
     * nunca sobre la propia reseña.
     */
    @Transactional
    public Map<String, Object> votarResena(long resenaId, Boolean esUtil) {
        if (esUtil == null) {
            throw new IllegalArgumentException("Debe indicar si la reseña le resultó útil o no");
        }
        long clienteId = clienteActualId();
        // Visible para el cliente = suya o aprobada (RLS); exigimos aprobada.
        List<Map<String, Object>> filas = pg.queryForList(
                "SELECT estado, cliente_id FROM resena WHERE id = ?", resenaId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe la reseña " + resenaId);
        }
        if (!"aprobada".equals(filas.get(0).get("estado"))) {
            throw new IllegalStateException("Solo se pueden votar reseñas aprobadas");
        }
        if (Long.valueOf(clienteId).equals(((Number) filas.get(0).get("cliente_id")).longValue())) {
            throw new IllegalStateException("No puedes votar tu propia reseña");
        }
        Integer yaVoto = pg.queryForObject(
                "SELECT count(*) FROM resena_util WHERE resena_id = ? AND cliente_id = ?",
                Integer.class, resenaId, clienteId);
        if (yaVoto != null && yaVoto > 0) {
            throw new IllegalStateException("Ya votaste esta reseña");
        }
        long id = idDe(pg.queryForObject("""
                INSERT INTO resena_util (resena_id, cliente_id, es_util)
                VALUES (?, ?, ?) RETURNING id""",
                Long.class, resenaId, clienteId, esUtil));
        Map<String, Object> conteos = pg.queryForMap("""
                SELECT count(*) FILTER (WHERE es_util)     AS utiles,
                       count(*) FILTER (WHERE NOT es_util) AS no_utiles
                FROM resena_util WHERE resena_id = ?""", resenaId);
        Map<String, Object> out = new HashMap<>(conteos);
        out.put("id", id);
        return out;
    }

    // ── Reportes de reseña ───────────────────────────────────────────────

    /** El CLIENTE reporta una reseña aprobada como abusiva (un reporte por cliente). */
    @Transactional
    public long reportarResena(long resenaId, String motivo, String comentario) {
        validarEnLista(motivo, MOTIVOS_REPORTE, "motivo del reporte");
        long clienteId = clienteActualId();
        List<String> estados = pg.queryForList(
                "SELECT estado FROM resena WHERE id = ?", String.class, resenaId);
        if (estados.isEmpty()) {
            throw new NoSuchElementException("No existe la reseña " + resenaId);
        }
        if (!"aprobada".equals(estados.get(0))) {
            throw new IllegalStateException("Solo se pueden reportar reseñas aprobadas");
        }
        Integer yaReporto = pg.queryForObject(
                "SELECT count(*) FROM reporte_resena WHERE resena_id = ? AND cliente_id = ?",
                Integer.class, resenaId, clienteId);
        if (yaReporto != null && yaReporto > 0) {
            throw new IllegalStateException("Ya reportaste esta reseña");
        }
        return idDe(pg.queryForObject("""
                INSERT INTO reporte_resena (resena_id, cliente_id, motivo, comentario)
                VALUES (?, ?, ?, NULLIF(?, '')) RETURNING id""",
                Long.class, resenaId, clienteId, motivo,
                comentario == null ? null : comentario.trim()));
    }

    /** Bandeja de reportes (personal), con la reseña reportada al lado. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarReportes(String estado) {
        if (estado != null && !estado.isBlank()) {
            validarEnLista(estado, TRANSICIONES_REPORTE.keySet(), "estado del reporte");
        }
        return pg.queryForList("""
                SELECT rr.id, rr.resena_id, rr.motivo, rr.comentario, rr.estado,
                       rr.fecha_creacion, c.nombre AS reportado_por,
                       r.titulo AS resena_titulo, r.comentario AS resena_comentario,
                       r.calificacion, r.estado AS resena_estado, p.nombre AS producto
                FROM reporte_resena rr
                JOIN resena r ON r.id = rr.resena_id
                JOIN producto p ON p.id = r.producto_id
                LEFT JOIN cliente c ON c.id = rr.cliente_id
                WHERE rr.estado = COALESCE(NULLIF(?, ''), rr.estado)
                ORDER BY rr.fecha_creacion DESC""", estado);
    }

    /** Cierra un reporte: atendido (procedía) o descartado. Terminal. */
    @Transactional
    public void resolverReporte(long id, String estado) {
        validarEnLista(estado, TRANSICIONES_REPORTE.keySet(), "estado del reporte");
        String actual = estadoDe("reporte_resena", "el reporte", id);
        if (!TRANSICIONES_REPORTE.get(actual).contains(estado)) {
            throw new IllegalStateException("El reporte ya fue resuelto ('" + actual + "')");
        }
        pg.update("UPDATE reporte_resena SET estado = ? WHERE id = ?", estado, id);
    }

    // ── Preguntas y respuestas ───────────────────────────────────────────

    /** Bandeja de preguntas (personal): todas, con filtro por estado y sus respuestas. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPreguntas(String estado) {
        if (estado != null && !estado.isBlank()) {
            validarEnLista(estado, TRANSICIONES_PREGUNTA.keySet(), "estado de la pregunta");
        }
        List<Map<String, Object>> preguntas = pg.queryForList("""
                SELECT q.id, q.producto_id, p.nombre AS producto, q.cliente_id,
                       c.nombre AS cliente, q.pregunta, q.estado, q.fecha_creacion
                FROM pregunta_producto q
                JOIN producto p ON p.id = q.producto_id
                LEFT JOIN cliente c ON c.id = q.cliente_id
                WHERE q.estado = COALESCE(NULLIF(?, ''), q.estado)
                ORDER BY q.fecha_creacion DESC""", estado);
        anexarRespuestas(preguntas, true);
        return preguntas;
    }

    /**
     * Preguntas de un producto con sus respuestas. El CLIENTE ve las publicadas
     * de cualquiera más las suyas en cualquier estado (propiedad por servicio:
     * pregunta_producto no tiene RLS); el personal ve todas.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPreguntasProducto(long productoId) {
        List<Map<String, Object>> preguntas;
        if (esCliente()) {
            preguntas = pg.queryForList("""
                    SELECT q.id, q.pregunta, q.estado, q.fecha_creacion,
                           (q.cliente_id = ?) AS es_mia
                    FROM pregunta_producto q
                    WHERE q.producto_id = ? AND (q.estado = 'publicada' OR q.cliente_id = ?)
                    ORDER BY q.fecha_creacion DESC""",
                    clienteActualId(), productoId, clienteActualId());
        } else {
            preguntas = pg.queryForList("""
                    SELECT q.id, q.pregunta, q.estado, q.fecha_creacion,
                           c.nombre AS cliente, false AS es_mia
                    FROM pregunta_producto q
                    LEFT JOIN cliente c ON c.id = q.cliente_id
                    WHERE q.producto_id = ?
                    ORDER BY q.fecha_creacion DESC""", productoId);
        }
        anexarRespuestas(preguntas, !esCliente());
        return preguntas;
    }

    /** El CLIENTE pregunta sobre un producto; nace 'pendiente' hasta moderarse. */
    @Transactional
    public long crearPregunta(Long productoId, String pregunta) {
        if (productoId == null) {
            throw new IllegalArgumentException("Debe indicar el producto de la pregunta");
        }
        exigirTexto(pregunta, "La pregunta no puede estar vacía");
        Integer existe = pg.queryForObject(
                "SELECT count(*) FROM producto WHERE id = ? AND activo",
                Integer.class, productoId);
        if (existe == null || existe == 0) {
            throw new IllegalArgumentException("El producto no existe o está inactivo");
        }
        return idDe(pg.queryForObject("""
                INSERT INTO pregunta_producto (producto_id, cliente_id, pregunta)
                VALUES (?, ?, ?) RETURNING id""",
                Long.class, productoId, clienteActualId(), pregunta.trim()));
    }

    /**
     * El personal responde una pregunta (respuesta oficial, autor del JWT).
     * Responder una pregunta pendiente la publica: contestar implica aprobar.
     * Una pregunta rechazada exige re-publicarla antes.
     */
    @Transactional
    public long responderPregunta(long preguntaId, String respuesta) {
        exigirTexto(respuesta, "La respuesta no puede estar vacía");
        String estado = estadoDe("pregunta_producto", "la pregunta", preguntaId);
        if ("rechazada".equals(estado)) {
            throw new IllegalStateException(
                    "La pregunta está rechazada: publícala antes de responderla");
        }
        long id = idDe(pg.queryForObject("""
                INSERT INTO respuesta_pregunta (pregunta_producto_id, usuario_id, respuesta, es_oficial)
                VALUES (?, ?, ?, true) RETURNING id""",
                Long.class, preguntaId, usuarioActualId(), respuesta.trim()));
        if ("pendiente".equals(estado)) {
            // Contestar implica aprobar: el que responde queda como moderador
            pg.update("""
                    UPDATE pregunta_producto
                    SET estado = 'publicada', moderado_por = ?, fecha_moderacion = now()
                    WHERE id = ?""", usuarioActualId(), preguntaId);
            auditoria.registrar("pregunta_producto", preguntaId, "UPDATE",
                    Map.of("estado", "pendiente"), Map.of("estado", "publicada"));
        }
        return id;
    }

    /** Moderación de la pregunta (publicar/rechazar) validando la transición. */
    @Transactional
    public void moderarPregunta(long id, String estado) {
        validarEnLista(estado, TRANSICIONES_PREGUNTA.keySet(), "estado de la pregunta");
        String actual = estadoDe("pregunta_producto", "la pregunta", id);
        if (estado.equals(actual)) {
            throw new IllegalStateException("La pregunta ya está en estado '" + estado + "'");
        }
        Set<String> permitidas = TRANSICIONES_PREGUNTA.get(actual);
        if (!permitidas.contains(estado)) {
            throw new IllegalStateException("Transición inválida: '" + actual + "' → '"
                    + estado + "'. Permitidas: " + String.join(", ", permitidas.stream().sorted().toList()));
        }
        pg.update("""
                UPDATE pregunta_producto
                SET estado = ?, moderado_por = ?, fecha_moderacion = now()
                WHERE id = ?""", estado, usuarioActualId(), id);
        auditoria.registrar("pregunta_producto", id, "UPDATE",
                Map.of("estado", actual), Map.of("estado", estado));
    }

    // ── Referencias ──────────────────────────────────────────────────────

    /** Productos activos para los selectores (cliente incluido). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProductosRef() {
        return pg.queryForList(
                "SELECT id, nombre FROM producto WHERE activo ORDER BY nombre");
    }

    /**
     * Productos que el CLIENTE autenticado ha COMPRADO (mismo criterio
     * ESTADOS_COMPRA de crearResena): alimenta el selector del formulario de
     * reseña para ofrecer solo lo reseñable. RLS sobre pedido refuerza la
     * propiedad.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProductosComprados() {
        return pg.queryForList("""
                SELECT DISTINCT p.id, p.nombre
                FROM producto p
                JOIN producto_variante pv ON pv.producto_id = p.id
                JOIN pedido_detalle pd ON pd.producto_variante_id = pv.id
                JOIN pedido pe ON pe.id = pd.pedido_id
                JOIN estado_pedido ep ON ep.id = pe.estado_pedido_id
                WHERE pe.cliente_id = ? AND p.activo
                  AND ep.codigo IN """ + ESTADOS_COMPRA + """

                ORDER BY p.nombre""", clienteActualId());
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    /**
     * Cuelga las respuestas de cada pregunta. El cliente no tiene SELECT sobre
     * usuario: su vista firma lo oficial como "RetailMind" sin tocar esa tabla.
     */
    private void anexarRespuestas(List<Map<String, Object>> preguntas, boolean conUsuario) {
        for (Map<String, Object> q : preguntas) {
            long preguntaId = ((Number) q.get("id")).longValue();
            q.put("respuestas", conUsuario
                    ? pg.queryForList("""
                            SELECT a.id, a.respuesta, a.es_oficial, a.fecha_creacion,
                                   CASE WHEN a.usuario_id IS NOT NULL
                                        THEN trim(concat(u.nombre, ' ', COALESCE(u.apellido, '')))
                                        ELSE COALESCE(cl.nombre, 'Cliente') END AS autor
                            FROM respuesta_pregunta a
                            LEFT JOIN usuario u ON u.id = a.usuario_id
                            LEFT JOIN cliente cl ON cl.id = a.cliente_id
                            WHERE a.pregunta_producto_id = ?
                            ORDER BY a.fecha_creacion, a.id""", preguntaId)
                    : pg.queryForList("""
                            SELECT a.id, a.respuesta, a.es_oficial, a.fecha_creacion,
                                   CASE WHEN a.es_oficial THEN 'RetailMind'
                                        ELSE COALESCE(cl.nombre, 'Cliente') END AS autor
                            FROM respuesta_pregunta a
                            LEFT JOIN cliente cl ON cl.id = a.cliente_id
                            WHERE a.pregunta_producto_id = ?
                            ORDER BY a.fecha_creacion, a.id""", preguntaId));
        }
    }

    /** Estado actual de la fila o 404 con mensaje claro. */
    private String estadoDe(String tabla, String etiqueta, long id) {
        List<String> estados = pg.queryForList(
                "SELECT estado FROM " + tabla + " WHERE id = ?", String.class, id);
        if (estados.isEmpty()) {
            throw new NoSuchElementException("No existe " + etiqueta + " " + id);
        }
        return estados.get(0);
    }

    private static void validarEnLista(String valor, Set<String> validos, String campo) {
        if (valor == null || !validos.contains(valor)) {
            throw new IllegalArgumentException("Valor inválido para " + campo
                    + ". Permitidos: " + String.join(", ", validos.stream().sorted().toList()));
        }
    }

    private static void exigirTexto(String valor, String mensaje) {
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException(mensaje);
    }

    private static long idDe(Long id) {
        if (id == null) throw new IllegalStateException("INSERT no devolvio id");
        return id;
    }

    private boolean esCliente() {
        return "CLIENTE".equalsIgnoreCase(rolActual());
    }

    /** El cliente autenticado; para CLIENTE nunca es null (lo garantiza el login). */
    private Long clienteActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getClienteId();
        }
        return null;
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }

    private String rolActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getRolCodigo();
        }
        return null;
    }
}
