package com.retailmind.inventario;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Caso de uso: TRANSFERENCIA ENTRE BODEGAS (grp_bodega o admin).
 *
 * Nota de diseño: transferencia_bodega es SOLO cabecera (no tiene detalle en
 * el esquema); la variante y la cantidad quedan registradas en los DOS
 * movimientos del kardex (salida_transferencia en origen y
 * entrada_transferencia en destino) con referencia_tipo='transferencia_bodega'
 * y en la observación de la cabecera.
 */
@Service
public class InventarioService {

    private final JdbcTemplate pg;
    private final StockService stock;

    public InventarioService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg, StockService stock) {
        this.pg = pg;
        this.stock = stock;
    }

    @Transactional
    public Map<String, Object> transferir(long varianteId, long bodegaOrigenId,
                                          long bodegaDestinoId, int cantidad, String observacion) {
        if (bodegaOrigenId == bodegaDestinoId) {
            throw new IllegalArgumentException("La bodega origen y destino deben ser distintas");
        }
        String sku = pg.queryForObject(
                "SELECT sku FROM producto_variante WHERE id = ?", String.class, varianteId);

        Long transferenciaId = pg.queryForObject("""
                INSERT INTO transferencia_bodega
                    (bodega_origen_id, bodega_destino_id, usuario_solicita_id, estado,
                     fecha_envio, fecha_recepcion, observacion)
                VALUES (?, ?, ?, 'recibida', now(), now(), ?)
                RETURNING id""",
                Long.class, bodegaOrigenId, bodegaDestinoId, usuarioActualId(),
                "[" + sku + " x" + cantidad + "] " + (observacion != null ? observacion : ""));

        // Salida en origen (valida stock suficiente con FOR UPDATE) y entrada en destino
        int stockOrigen = stock.mover(varianteId, bodegaOrigenId, "salida_transferencia",
                cantidad, "transferencia_bodega", transferenciaId, null, usuarioActualId(), null);
        int stockDestino = stock.mover(varianteId, bodegaDestinoId, "entrada_transferencia",
                cantidad, "transferencia_bodega", transferenciaId, null, usuarioActualId(), null);

        return Map.of("id", transferenciaId, "sku", sku, "cantidad", cantidad,
                "stockOrigen", stockOrigen, "stockDestino", stockDestino, "estado", "recibida");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarTransferencias() {
        return pg.queryForList("""
                SELECT t.id, t.estado, t.fecha_envio, t.observacion,
                       bo.codigo AS bodega_origen, bd.codigo AS bodega_destino,
                       u.email AS solicitado_por
                FROM transferencia_bodega t
                JOIN bodega bo ON bo.id = t.bodega_origen_id
                JOIN bodega bd ON bd.id = t.bodega_destino_id
                LEFT JOIN usuario u ON u.id = t.usuario_solicita_id
                ORDER BY t.id DESC""");
    }

    // ── Niveles mín/máx de existencias (cierre de brechas OTD-INV-08) ────

    /**
     * Fija stock_minimo y stock_maximo del registro de inventario de una
     * variante en una bodega (BODEGA/ADMIN). El máximo es opcional (null =
     * sin tope definido, como las filas históricas); si se informa debe ser
     * mayor que el mínimo. No toca stock_actual: eso es del kardex.
     */
    @Transactional
    public Map<String, Object> actualizarNiveles(long varianteId, long bodegaId,
                                                 int stockMinimo, Integer stockMaximo) {
        if (stockMinimo < 0) {
            throw new IllegalArgumentException("El stock mínimo no puede ser negativo");
        }
        if (stockMaximo != null && stockMaximo <= stockMinimo) {
            throw new IllegalArgumentException("El stock máximo (" + stockMaximo
                    + ") debe ser mayor que el stock mínimo (" + stockMinimo + ")");
        }
        int filas = pg.update("""
                UPDATE inventario SET stock_minimo = ?, stock_maximo = ?::int
                WHERE producto_variante_id = ? AND bodega_id = ?""",
                stockMinimo, stockMaximo, varianteId, bodegaId);
        if (filas == 0) {
            throw new NoSuchElementException(
                    "No existe registro de inventario para esa variante en esa bodega");
        }
        return pg.queryForMap("""
                SELECT i.producto_variante_id, pv.sku, i.bodega_id, b.nombre AS bodega,
                       i.stock_actual, i.stock_minimo, i.stock_maximo
                FROM inventario i
                JOIN producto_variante pv ON pv.id = i.producto_variante_id
                JOIN bodega b ON b.id = i.bodega_id
                WHERE i.producto_variante_id = ? AND i.bodega_id = ?""",
                varianteId, bodegaId);
    }


    // ── Existencias: qué hay y cuánto hay ────────────────────────────────

    /**
     * Filtros de estado admitidos, con su condición sobre el agregado.
     *
     * Es una LISTA BLANCA y el valor que llega por la petición solo sirve para
     * BUSCAR en ella: lo que se concatena en el SQL es el texto escrito aquí,
     * nunca el del usuario (regla 2 del proyecto).
     */
    private static final Map<String, String> ESTADOS_EXISTENCIA = Map.of(
            "todos",        "TRUE",
            "con_stock",    "COALESCE(sum(i.stock_actual), 0) > 0",
            "sin_stock",    "COALESCE(sum(i.stock_actual), 0) = 0",
            // El mínimo se suma igual que el stock: con una bodega elegida la
            // comparación es exacta y, con todas, es la reposición agregada.
            "bajo_minimo",  "COALESCE(sum(i.stock_minimo), 0) > 0 "
                          + "AND COALESCE(sum(i.stock_actual), 0) <= COALESCE(sum(i.stock_minimo), 0)",
            "sobre_maximo", "sum(i.stock_maximo) IS NOT NULL "
                          + "AND COALESCE(sum(i.stock_actual), 0) > sum(i.stock_maximo)");

    /** Ordenaciones admitidas. Misma regla: lista blanca, no texto del usuario. */
    private static final Map<String, String> ORDENES_EXISTENCIA = Map.of(
            "producto",   "pr.nombre, pv.sku",
            "sku",        "pv.sku",
            "stock_desc", "COALESCE(sum(i.stock_actual), 0) DESC, pr.nombre",
            "stock_asc",  "COALESCE(sum(i.stock_actual), 0), pr.nombre");

    /**
     * Existencias por VARIANTE, sumando sus posiciones de inventario.
     *
     * <h3>Por qué se parte de la variante y no del inventario</h3>
     * Una variante sin una sola fila en {@code inventario} —recién dada de
     * alta, o retirada de todas las bodegas— existe y su stock es CERO, que es
     * justo el caso que hay que ver. Partiendo de {@code inventario} esas
     * variantes desaparecen del listado, y entonces «no aparece» se confunde
     * con «no tengo»: hay 6.224 variantes y 11.407 posiciones, y no todas las
     * variantes tienen una.
     *
     * <h3>Sin un solo importe</h3>
     * La pantalla la abren BODEGA y ANALISTA además de ADMIN y GERENTE, así que
     * la consulta no selecciona precio ni costo. La barrera es ÉSTA y no la
     * ruta, el mismo mecanismo de OTD-COM-08 y de OTD-LOG-11.
     *
     * <h3>Y tampoco toca ninguna tabla que BODEGA no pueda leer</h3>
     * Que un rol tenga la ruta abierta no basta: el motor manda. `grp_bodega`
     * no tiene SELECT sobre `marca`, y con el JOIN puesto esta pantalla le
     * respondía 403 —un 42501 traducido— mientras funcionaba con los otros
     * tres roles.
     *
     * <h3>El filtro de bodega va en el JOIN, no en el WHERE</h3>
     * En el WHERE convertiría el LEFT JOIN en interno y volveríamos a perder
     * las variantes sin stock en esa bodega, que son exactamente las que se
     * buscan cuando alguien filtra por una bodega.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> existencias(String q, Long bodegaId, String estado,
                                           String orden, int page, int size) {
        String condicion = ESTADOS_EXISTENCIA.get(
                estado == null || estado.isBlank() ? "todos" : estado);
        if (condicion == null) {
            throw new IllegalArgumentException("Estado de existencias no válido: " + estado
                    + ". Admitidos: " + ESTADOS_EXISTENCIA.keySet());
        }
        String porOrden = ORDENES_EXISTENCIA.get(
                orden == null || orden.isBlank() ? "producto" : orden);
        if (porOrden == null) {
            throw new IllegalArgumentException("Orden no válido: " + orden
                    + ". Admitidos: " + ORDENES_EXISTENCIA.keySet());
        }
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String filtro = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";

        // NO se une `marca`, y no es un olvido: `grp_bodega` no tiene SELECT
        // sobre esa tabla, así que el JOIN devolvía 42501 y la pantalla
        // respondía 403 A BODEGA —el rol que más la necesita— mientras
        // funcionaba con los otros tres. Es la misma trampa que ya obligó a
        // dejar `categoria` fuera de OTD-INV-07. La búsqueda va por SKU y por
        // nombre de producto; la marca no se ofrece porque no se puede leer
        // con todos los roles que abren esto.
        String desde = """
                FROM producto_variante pv
                JOIN producto pr ON pr.id = pv.producto_id
                LEFT JOIN inventario i ON i.producto_variante_id = pv.id
                     AND (?::bigint IS NULL OR i.bodega_id = ?::bigint)
                WHERE (?::text IS NULL OR pv.sku ILIKE ?::text OR pr.nombre ILIKE ?::text)
                GROUP BY pv.id, pv.sku, pv.activo, pr.nombre
                HAVING""" + " " + condicion;   // ojo: el bloque de texto RECORTA el espacio final de la línea
        Object[] filtros = { bodegaId, bodegaId, filtro, filtro, filtro };

        Long total = pg.queryForObject(
                "SELECT count(*) FROM (SELECT 1 " + desde + ") t", Long.class, filtros);

        Object[] args = new Object[filtros.length + 2];
        System.arraycopy(filtros, 0, args, 0, filtros.length);
        args[filtros.length] = limit;
        args[filtros.length + 1] = offset;
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT pv.id AS variante_id, pv.sku, pv.activo,
                       pr.nombre AS producto,
                       COALESCE(sum(i.stock_actual), 0)    AS stock_actual,
                       COALESCE(sum(i.stock_reservado), 0) AS reservado,
                       COALESCE(sum(i.stock_actual), 0)
                         - COALESCE(sum(i.stock_reservado), 0) AS disponible,
                       COALESCE(sum(i.stock_minimo), 0)    AS stock_minimo,
                       sum(i.stock_maximo)                 AS stock_maximo,
                       count(i.id)                         AS bodegas
                """ + desde + """

                ORDER BY""" + " " + porOrden + """

                LIMIT ? OFFSET ?""", args);

        // Los indicadores se miden sobre el conjunto FILTRADO ENTERO y no sobre
        // la página: «12 variantes sin stock» de las 25 que se ven no es un
        // dato, es una coincidencia del tamaño de página.
        Map<String, Object> resumen = pg.queryForMap("""
                SELECT count(*)                                   AS variantes,
                       COALESCE(sum(t.stock_actual), 0)           AS unidades,
                       count(*) FILTER (WHERE t.stock_actual = 0) AS sin_stock,
                       count(*) FILTER (WHERE t.stock_minimo > 0
                                          AND t.stock_actual <= t.stock_minimo) AS bajo_minimo
                FROM (SELECT COALESCE(sum(i.stock_actual), 0) AS stock_actual,
                             COALESCE(sum(i.stock_minimo), 0) AS stock_minimo
                      """ + desde + """
                     ) t""", filtros);

        return Map.of("items", items, "total", total == null ? 0 : total,
                      "page", Math.max(page, 0), "size", limit, "resumen", resumen);
    }

    /**
     * Desglose por bodega de UNA variante: dónde está y cuánto hay en cada
     * sitio. Es la segunda mitad de la pregunta «cuánto tengo» —la primera la
     * responde el agregado— y la que hace falta para transferir o ajustar.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> existenciasPorBodega(long varianteId) {
        return pg.queryForList("""
                SELECT i.bodega_id, b.codigo AS bodega_codigo, b.nombre AS bodega,
                       i.stock_actual, i.stock_reservado,
                       i.stock_actual - i.stock_reservado AS disponible,
                       i.stock_minimo, i.stock_maximo, i.fecha_actualizacion
                FROM inventario i
                JOIN bodega b ON b.id = i.bodega_id
                WHERE i.producto_variante_id = ?
                ORDER BY b.nombre""", varianteId);
    }

    // ── Ajuste de inventario (CU-O-16) ───────────────────────────────────

    /**
     * Ajuste manual por conteo físico o merma (grp_bodega o admin).
     * ajuste_inventario es SOLO cabecera (como transferencia_bodega): la
     * variante y la cantidad quedan en el movimiento del kardex con
     * referencia_tipo='ajuste_inventario' y en el texto del motivo.
     * tipo de la cabecera: 'positivo' (entrada) / 'negativo' (salida),
     * según el check constraint de la tabla.
     */
    @Transactional
    public Map<String, Object> registrarAjuste(long varianteId, long bodegaId, String tipo,
                                               int cantidad, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo del ajuste es obligatorio");
        }
        boolean esEntrada = "entrada".equals(tipo);
        if (!esEntrada && !"salida".equals(tipo)) {
            throw new IllegalArgumentException(
                    "El tipo de ajuste debe ser 'entrada' o 'salida'");
        }
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        }
        String sku = pg.queryForObject(
                "SELECT sku FROM producto_variante WHERE id = ?", String.class, varianteId);

        Long ajusteId = pg.queryForObject("""
                INSERT INTO ajuste_inventario
                    (bodega_id, usuario_id, tipo, estado, motivo, fecha_aplicacion)
                VALUES (?, ?, ?, 'aplicado', ?, now())
                RETURNING id""",
                Long.class, bodegaId, usuarioActualId(),
                esEntrada ? "positivo" : "negativo",
                "[" + sku + " x" + cantidad + "] " + motivo.trim());

        // Kardex + stock (valida stock suficiente en salidas con FOR UPDATE)
        int stockNuevo = stock.mover(varianteId, bodegaId,
                esEntrada ? "entrada_ajuste" : "salida_ajuste", cantidad,
                "ajuste_inventario", ajusteId, null, usuarioActualId(), motivo.trim());

        return Map.of("id", ajusteId, "sku", sku, "tipo", tipo, "cantidad", cantidad,
                "stockResultante", stockNuevo, "estado", "aplicado");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarAjustes() {
        // Sin JOIN a usuario: grp_bodega no tiene SELECT sobre esa tabla
        return pg.queryForList("""
                SELECT a.id, a.tipo, a.estado, a.motivo, a.fecha_aplicacion,
                       b.nombre AS bodega
                FROM ajuste_inventario a
                JOIN bodega b ON b.id = a.bodega_id
                ORDER BY a.id DESC""");
    }

    /**
     * Anula un ajuste APLICADO revirtiendo su movimiento de kardex con un
     * contramovimiento (entrada⇄salida). El CHECK de la tabla ya contemplaba
     * 'anulado' pero no existía flujo. El estado 'borrador' sigue sin flujo:
     * la cabecera no persiste variante/cantidad (viven solo en el kardex),
     * así que aplicar un borrador exigiría una tabla de detalle que el
     * esquema no tiene — queda como mejora futura documentada.
     */
    @Transactional
    public Map<String, Object> anularAjuste(long ajusteId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo de la anulación es obligatorio");
        }
        List<Map<String, Object>> filas = pg.queryForList(
                "SELECT estado FROM ajuste_inventario WHERE id = ? FOR UPDATE", ajusteId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el ajuste " + ajusteId);
        }
        String estado = (String) filas.get(0).get("estado");
        if (!"aplicado".equals(estado)) {
            throw new IllegalStateException("Solo se puede anular un ajuste en estado "
                    + "'aplicado' (estado actual: '" + estado + "')");
        }
        // Contramovimiento de cada movimiento original del ajuste. El estado
        // 'anulado' (guardia de arriba) garantiza que esto corre UNA sola vez,
        // por lo que aquí solo existen los movimientos originales.
        List<Map<String, Object>> movimientos = pg.queryForList("""
                SELECT m.producto_variante_id, m.bodega_id, m.cantidad,
                       m.costo_unitario, tm.codigo
                FROM movimiento_inventario m
                JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
                WHERE m.referencia_tipo = 'ajuste_inventario' AND m.referencia_id = ?""",
                ajusteId);
        if (movimientos.isEmpty()) {
            throw new IllegalStateException(
                    "El ajuste " + ajusteId + " no tiene movimientos de kardex que revertir");
        }
        for (Map<String, Object> m : movimientos) {
            boolean fueEntrada = "entrada_ajuste".equals(m.get("codigo"));
            // La reversión de una entrada valida stock suficiente (FOR UPDATE)
            stock.mover(((Number) m.get("producto_variante_id")).longValue(),
                    ((Number) m.get("bodega_id")).longValue(),
                    fueEntrada ? "salida_ajuste" : "entrada_ajuste",
                    ((Number) m.get("cantidad")).intValue(),
                    "ajuste_inventario", ajusteId,
                    (java.math.BigDecimal) m.get("costo_unitario"),
                    usuarioActualId(), "Anulación del ajuste #" + ajusteId + ": " + motivo.trim());
        }
        pg.update("""
                UPDATE ajuste_inventario
                SET estado = 'anulado', motivo = motivo || ' · ANULADO: ' || ?
                WHERE id = ?""", motivo.trim(), ajusteId);
        return Map.of("id", ajusteId, "estado", "anulado",
                "movimientosRevertidos", movimientos.size());
    }

    // ── Kardex (CU-O-17) ─────────────────────────────────────────────────

    /**
     * Kardex de movimiento_inventario, filtrable por variante y/o bodega.
     * Solo tablas con SELECT para bodega/gerente/analista/admin: sin JOIN a
     * usuario ni subconsulta a devolucion (grp_bodega no las ve; un 42501
     * abortaria la transaccion).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> kardex(Long varianteId, Long bodegaId) {
        return pg.queryForList("""
                SELECT m.id, m.fecha_creacion, pv.sku, pr.nombre AS producto,
                       b.nombre AS bodega, tm.nombre AS tipo_movimiento, tm.naturaleza,
                       m.cantidad, m.stock_anterior, m.stock_nuevo, m.costo_unitario,
                       m.referencia_tipo, m.referencia_id,
                       CASE m.referencia_tipo
                           WHEN 'recepcion_mercancia' THEN 'Recepcion ' || COALESCE(
                               (SELECT r.numero FROM recepcion_mercancia r WHERE r.id = m.referencia_id),
                               '#' || m.referencia_id)
                           WHEN 'pedido' THEN 'Pedido ' || COALESCE(
                               (SELECT p2.numero FROM pedido p2 WHERE p2.id = m.referencia_id),
                               '#' || m.referencia_id)
                           WHEN 'devolucion' THEN 'Devolucion #' || m.referencia_id
                           WHEN 'transferencia_bodega' THEN 'Transferencia #' || m.referencia_id
                           WHEN 'ajuste_inventario' THEN 'Ajuste #' || m.referencia_id
                           ELSE COALESCE(m.referencia_tipo || ' #' || m.referencia_id, '—')
                       END AS referencia,
                       m.observacion
                FROM movimiento_inventario m
                JOIN producto_variante pv ON pv.id = m.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                JOIN bodega b ON b.id = m.bodega_id
                JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
                WHERE (?::bigint IS NULL OR m.producto_variante_id = ?)
                  AND (?::bigint IS NULL OR m.bodega_id = ?)
                ORDER BY m.id DESC
                LIMIT 500""",
                varianteId, varianteId, bodegaId, bodegaId);
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
