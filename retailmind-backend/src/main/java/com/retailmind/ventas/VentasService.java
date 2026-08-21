package com.retailmind.ventas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;
import com.retailmind.auth.AppUserPrincipal;
import com.retailmind.inventario.StockService;
import com.retailmind.marketing.DescuentosService;

/**
 * Ciclo de venta (Order-to-Cash) con compuertas enforzadas en backend:
 *
 *   confirmado -> [pago(s) del cliente] -> pagado -> facturado (AUTOMÁTICO si
 *   canal 'web'; manual VENDEDOR/ADMIN si interno) -> en_preparacion ->
 *   preparado (BODEGA hace picking/empaque) -> despachado (DESPACHO, con el
 *   transportista asignado por zona u override manual) -> entregado ->
 *   [devolución] -> devuelto
 *
 * Cada paso valida el estado anterior (mensajes claros vía IllegalState/
 * IllegalArgument -> GlobalExceptionHandler). Réplica del patrón de compras/:
 *  - subtotales de detalle GENERATED y totales de cabecera por trigger: la app
 *    inserta sin ellos y LEE el total después.
 *  - stock via StockService (upsert + FOR UPDATE + kardex + update).
 *  - Decisión documentada: el pedido DESCUENTA stock directo (salida_venta)
 *    al confirmarse — más simple de demostrar que reservar; la devolución lo
 *    reingresa (entrada_devolucion_cliente).
 */
@Service
public class VentasService {

    private static final BigDecimal IVA_DEFECTO = new BigDecimal("15");

    private final JdbcTemplate pg;
    private final StockService stock;
    private final DescuentosService descuentos;
    private final AuditoriaService auditoria;

    public VentasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg, StockService stock,
                         DescuentosService descuentos, AuditoriaService auditoria) {
        this.pg = pg;
        this.stock = stock;
        this.descuentos = descuentos;
        this.auditoria = auditoria;
    }

    // ── Caso 7: realizar pedido ──────────────────────────────────────────

    public record ItemPedido(long varianteId, int cantidad) {}

    @Transactional
    public Map<String, Object> crearPedido(long clienteId, long bodegaId, String canal,
                                           List<ItemPedido> items) {
        return crearPedido(clienteId, bodegaId, canal, items, null);
    }

    @Transactional
    public Map<String, Object> crearPedido(long clienteId, long bodegaId, String canal,
                                           List<ItemPedido> items, Long direccionEnvioId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("El pedido requiere al menos un item");
        }
        String numero = siguienteNumero("PED");
        String canalFinal = canal != null && List.of("web", "tienda", "telefono").contains(canal)
                ? canal : "web";
        // Trazabilidad (script 42): vendedor_id = usuario del JWT que crea el
        // pedido INTERNO. En el checkout online (rol CLIENTE) queda NULL: el
        // autor es el cliente y ya está trazado por cliente_id + canal 'web'
        // + la primera fila del historial.
        Long vendedorId = "CLIENTE".equalsIgnoreCase(rolActual()) ? null : usuarioActualId();
        Long pedidoId = pg.queryForObject("""
                INSERT INTO pedido (numero, cliente_id, estado_pedido_id, moneda_id, canal,
                                    direccion_envio_id, vendedor_id)
                VALUES (?, ?, (SELECT id FROM estado_pedido WHERE codigo = 'confirmado'),
                        (SELECT id FROM moneda WHERE es_base LIMIT 1), ?, ?::bigint, ?::bigint)
                RETURNING id""",
                Long.class, numero, clienteId, canalFinal, direccionEnvioId, vendedorId);
        if (vendedorId != null) {
            auditoria.registrar("pedido", pedidoId, "INSERT", null,
                    Map.of("numero", numero, "canal", canalFinal,
                           "cliente_id", clienteId, "vendedor_id", vendedorId));
        }

        List<String> promosAplicadas = new java.util.ArrayList<>();
        for (ItemPedido it : items) {
            if (it.cantidad() <= 0) {
                throw new IllegalArgumentException(
                        "La cantidad de cada producto debe ser mayor a cero");
            }
            // Snapshot de nombre, sku y precio de venta vigente
            List<Map<String, Object>> variantes = pg.queryForList("""
                    SELECT pv.sku, pv.precio, pr.nombre
                    FROM producto_variante pv JOIN producto pr ON pr.id = pv.producto_id
                    WHERE pv.id = ? AND pv.activo""", it.varianteId());
            if (variantes.isEmpty()) {
                throw new IllegalArgumentException("El producto (variante "
                        + it.varianteId() + ") no existe o esta inactivo");
            }
            Map<String, Object> v = variantes.get(0);
            BigDecimal precio = (BigDecimal) v.get("precio");

            // Promoción vigente del producto: descuento AUTOMÁTICO por línea
            // (script 40). El IVA se calcula sobre la base ya rebajada.
            Map<String, Object> promo = descuentos.descuentoPromocional(
                    it.varianteId(), precio, it.cantidad());
            BigDecimal descPromo = (BigDecimal) promo.get("monto");
            BigDecimal baseLinea = precio.multiply(BigDecimal.valueOf(it.cantidad()))
                    .subtract(descPromo);
            BigDecimal impuesto = baseLinea.multiply(IVA_DEFECTO)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            // subtotal NO se inserta (columna generada)
            pg.update("""
                    INSERT INTO pedido_detalle
                        (pedido_id, producto_variante_id, nombre_producto, sku,
                         cantidad, precio_unitario, monto_descuento, monto_impuesto)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    pedidoId, it.varianteId(), v.get("nombre"), v.get("sku"),
                    it.cantidad(), precio, descPromo, impuesto);
            if (descPromo.signum() > 0) {
                promosAplicadas.add(v.get("nombre") + ": " + promo.get("promocion")
                        + " (−$" + descPromo + ")");
            }

            // Descuento directo de stock con kardex (decisión documentada arriba)
            stock.mover(it.varianteId(), bodegaId, "salida_venta", it.cantidad(),
                    "pedido", pedidoId, precio, usuarioActualId(), null);
        }

        registrarHistorial(pedidoId, "confirmado", "Pedido creado y stock descontado");
        for (String nota : promosAplicadas) {
            registrarHistorial(pedidoId, "confirmado", "Promoción aplicada — " + nota);
        }
        asignarEnvioPorZona(pedidoId, clienteId, direccionEnvioId);
        return obtenerPedido(pedidoId);
    }

    /**
     * Asignación AUTOMÁTICA de transportista/método de envío por ZONA
     * (script 39): la dirección del pedido (o la predeterminada del cliente)
     * resuelve la zona por especificidad ciudad > provincia > país; la tarifa
     * activa más barata de esa zona define el método y su transportista. El
     * cliente solo lo VE (no lo elige); DESPACHO puede cambiarlo al despachar.
     * Sin dirección o sin zona configurada el pedido queda sin asignar y
     * despacho decide manualmente.
     */
    private void asignarEnvioPorZona(long pedidoId, long clienteId, Long direccionEnvioId) {
        List<Map<String, Object>> asignaciones = pg.queryForList("""
                WITH dir AS (
                    SELECT ci.id AS ciudad_id, ci.provincia_id, pr.pais_id
                    FROM direccion d
                    JOIN ciudad ci ON ci.id = d.ciudad_id
                    JOIN provincia pr ON pr.id = ci.provincia_id
                    WHERE d.id = COALESCE(?::bigint,
                          (SELECT d2.id FROM direccion d2
                           JOIN cliente c ON c.usuario_id = d2.usuario_id
                           WHERE c.id = ? AND d2.activo
                           ORDER BY d2.es_predeterminada DESC, d2.id LIMIT 1))
                )
                SELECT z.nombre AS zona, me.id AS metodo_envio_id, me.nombre AS metodo,
                       me.dias_entrega_min, me.dias_entrega_max,
                       t.id AS transportista_id, t.nombre AS transportista
                FROM dir
                JOIN zona_envio z ON z.activo AND z.pais_id = dir.pais_id
                     AND (z.provincia_id IS NULL OR z.provincia_id = dir.provincia_id)
                     AND (z.ciudad_id IS NULL OR z.ciudad_id = dir.ciudad_id)
                JOIN tarifa_envio tf ON tf.zona_envio_id = z.id AND tf.activo
                JOIN metodo_envio me ON me.id = tf.metodo_envio_id AND me.activo
                JOIN transportista t ON t.id = me.transportista_id AND t.activo
                ORDER BY (z.ciudad_id IS NOT NULL) DESC,
                         (z.provincia_id IS NOT NULL) DESC, tf.costo_base
                LIMIT 1""", direccionEnvioId, clienteId);
        if (asignaciones.isEmpty()) return;
        Map<String, Object> a = asignaciones.get(0);
        pg.update("UPDATE pedido SET metodo_envio_id = ?, transportista_id = ? WHERE id = ?",
                ((Number) a.get("metodo_envio_id")).longValue(),
                ((Number) a.get("transportista_id")).longValue(), pedidoId);
        registrarHistorial(pedidoId, "confirmado", "Transportista asignado por zona "
                + a.get("zona") + ": " + a.get("transportista") + " — " + a.get("metodo")
                + " (" + a.get("dias_entrega_min") + "-" + a.get("dias_entrega_max")
                + " días hábiles)");
    }

    /**
     * Peso total del envío: Σ producto_variante.peso_kg × cantidad de las
     * líneas del pedido. TODO-O-NADA: si alguna línea no tiene peso (> 0)
     * devuelve null — un total parcial distorsionaría el costo por kg en
     * silencio; null = «peso desconocido» y el costo aplica solo costo_base.
     * (Cobertura verificada vía MCP 2026-07-22: peso_kg está NULL en las
     * 1221 variantes, así que hoy todo envío nace con peso NULL; el cálculo
     * queda listo para cuando el catálogo capture pesos. grp_despacho lee
     * SOLO id y peso_kg de producto_variante — script 47.)
     */
    private BigDecimal pesoTotalPedido(long pedidoId) {
        return pg.queryForObject("""
                SELECT CASE WHEN COUNT(*) > 0 AND COUNT(*) FILTER
                            (WHERE pv.peso_kg IS NULL OR pv.peso_kg <= 0) = 0
                       THEN SUM(pv.peso_kg * pd.cantidad) END
                FROM pedido_detalle pd
                JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                WHERE pd.pedido_id = ?""", BigDecimal.class, pedidoId);
    }

    /**
     * Costo REAL del envío (OTD-LOG-11): tarifa activa de la zona de la
     * dirección del pedido — misma cadena ciudad > provincia > país que la
     * asignación de transportista — para el MÉTODO con el que se despacha
     * (el asignado por zona o el override de despacho). Con peso conocido el
     * costo es costo_base + costo_por_kg × peso; con peso desconocido
     * (pesoTotalKg null) aplica solo costo_base, el comportamiento previo.
     * Sin dirección, zona o tarifa aplicable devuelve
     * 0.00 EXPLÍCITO (queda auditado en log_auditoria como costo_envio 0):
     * se prefiere un cero visible en el informe táctico a inventar un costo.
     */
    private BigDecimal costoEnvioPorTarifa(long pedidoId, long metodoEnvioId,
                                           BigDecimal pesoTotalKg) {
        List<Map<String, Object>> tarifas = pg.queryForList("""
                WITH dir AS (
                    SELECT ci.id AS ciudad_id, ci.provincia_id, pr.pais_id
                    FROM pedido p
                    JOIN direccion d ON d.id = COALESCE(p.direccion_envio_id,
                          (SELECT d2.id FROM direccion d2
                           JOIN cliente c ON c.usuario_id = d2.usuario_id
                           WHERE c.id = p.cliente_id AND d2.activo
                           ORDER BY d2.es_predeterminada DESC, d2.id LIMIT 1))
                    JOIN ciudad ci ON ci.id = d.ciudad_id
                    JOIN provincia pr ON pr.id = ci.provincia_id
                    WHERE p.id = ?
                )
                SELECT tf.costo_base, tf.costo_por_kg
                FROM dir
                JOIN zona_envio z ON z.activo AND z.pais_id = dir.pais_id
                     AND (z.provincia_id IS NULL OR z.provincia_id = dir.provincia_id)
                     AND (z.ciudad_id IS NULL OR z.ciudad_id = dir.ciudad_id)
                JOIN tarifa_envio tf ON tf.zona_envio_id = z.id AND tf.activo
                     AND tf.metodo_envio_id = ?
                ORDER BY (z.ciudad_id IS NOT NULL) DESC,
                         (z.provincia_id IS NOT NULL) DESC, tf.costo_base
                LIMIT 1""", pedidoId, metodoEnvioId);
        if (tarifas.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = (BigDecimal) tarifas.get(0).get("costo_base");
        BigDecimal porKg = (BigDecimal) tarifas.get(0).get("costo_por_kg");
        if (pesoTotalKg == null || porKg == null || porKg.signum() == 0) {
            return base;
        }
        return base.add(porKg.multiply(pesoTotalKg)).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerPedido(long pedidoId) {
        Map<String, Object> pedido = pg.queryForMap("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                       p.subtotal, p.monto_descuento, p.monto_impuesto, p.costo_envio, p.total,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente, c.email AS cliente_email,
                       t.nombre AS transportista, me.nombre AS metodo_envio,
                       me.dias_entrega_min, me.dias_entrega_max
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN transportista t ON t.id = p.transportista_id
                LEFT JOIN metodo_envio me ON me.id = p.metodo_envio_id
                WHERE p.id = ?""", pedidoId);
        // producto_id (del catálogo) viaja por línea para que Mis Pedidos pueda
        // ofrecer "Reseñar" sobre productos comprados (reseña de compra verificada)
        pedido.put("detalles", pg.queryForList("""
                SELECT pd.id, pd.sku, pd.nombre_producto, pd.cantidad, pd.precio_unitario,
                       pd.subtotal, pd.monto_descuento, pd.monto_impuesto,
                       pv.producto_id
                FROM pedido_detalle pd
                JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                WHERE pd.pedido_id = ? ORDER BY pd.id""", pedidoId));
        // Cupón aplicado en el checkout (script 40): todos los roles que llegan
        // aquí (ADMIN/GERENTE/VENDEDOR/CLIENTE) tienen SELECT sobre uso_cupon.
        List<Map<String, Object>> cupones = pg.queryForList("""
                SELECT cu.codigo, uc.monto_descontado
                FROM uso_cupon uc JOIN cupon cu ON cu.id = uc.cupon_id
                WHERE uc.pedido_id = ?""", pedidoId);
        pedido.put("cupon", cupones.isEmpty() ? null : cupones.get(0));
        // grp_cliente ya tiene SELECT sobre historial_estado_pedido con RLS de
        // propiedad (script 30): la misma consulta sirve para todos los roles y
        // al cliente el motor lo limita al historial de SUS pedidos.
        pedido.put("historial", pg.queryForList("""
                SELECT ep.codigo AS estado, h.comentario, h.fecha_creacion
                FROM historial_estado_pedido h
                JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
                WHERE h.pedido_id = ? ORDER BY h.id""", pedidoId));
        pedido.put("notas", listarNotas(pedidoId));

        // Documentos encadenados: factura y envío del pedido (si existen), para
        // que el detalle muestre el proceso completo y las acciones siguientes.
        List<Map<String, Object>> facturas = pg.queryForList("""
                SELECT id, numero, estado FROM factura_venta
                WHERE pedido_id = ? ORDER BY id DESC""", pedidoId);
        pedido.put("factura", facturas.isEmpty() ? null : facturas.get(0));
        List<Map<String, Object>> envios = pg.queryForList("""
                SELECT id, numero, numero_guia, estado, fecha_despacho, fecha_entrega_real
                FROM envio WHERE pedido_id = ? ORDER BY id DESC""", pedidoId);
        pedido.put("envio", envios.isEmpty() ? null : envios.get(0));

        // Pagos del cliente: solo personal (grp_cliente no tiene SELECT sobre
        // pago; su vista del proceso es el historial + factura + envío)
        if (!"CLIENTE".equalsIgnoreCase(rolActual())) {
            List<Map<String, Object>> pagos = pg.queryForList("""
                    SELECT pa.id, pa.monto, pa.estado, pa.referencia_externa, pa.fecha_pago,
                           mp.nombre AS metodo
                    FROM pago pa JOIN metodo_pago mp ON mp.id = pa.metodo_pago_id
                    WHERE pa.pedido_id = ? ORDER BY pa.id""", pedidoId);
            pedido.put("pagos", pagos);
            BigDecimal total = (BigDecimal) pedido.get("total");
            BigDecimal pagado = totalPagado(pedidoId);
            pedido.put("total_pagado", pagado);
            pedido.put("saldo_pendiente", total.subtract(pagado));
        } else {
            pedido.put("pagos", List.of());
        }
        return pedido;
    }

    // ── Notas / observaciones del pedido (nota_pedido, script 31) ────────

    /**
     * CLIENTE: solo notas de sus pedidos marcadas es_visible_cliente (RLS lo
     * refuerza) y sin autor (grp_cliente no lee usuario). Personal: todas,
     * con autor.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarNotas(long pedidoId) {
        if ("CLIENTE".equalsIgnoreCase(rolActual())) {
            return pg.queryForList("""
                    SELECT id, nota, fecha_creacion
                    FROM nota_pedido
                    WHERE pedido_id = ? AND es_visible_cliente ORDER BY id""", pedidoId);
        }
        return pg.queryForList("""
                SELECT n.id, n.nota, n.es_visible_cliente, n.fecha_creacion,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS autor
                FROM nota_pedido n
                LEFT JOIN usuario u ON u.id = n.usuario_id
                WHERE n.pedido_id = ? ORDER BY n.id""", pedidoId);
    }

    /** Nota de bitácora del personal sobre un pedido; el autor sale del JWT. */
    @Transactional
    public Map<String, Object> crearNota(long pedidoId, String nota, boolean esVisibleCliente) {
        if (nota == null || nota.isBlank()) {
            throw new IllegalArgumentException("La nota no puede estar vacía");
        }
        estadoPedido(pedidoId); // 400 con mensaje claro si el pedido no existe
        Long id = pg.queryForObject("""
                INSERT INTO nota_pedido (pedido_id, usuario_id, nota, es_visible_cliente)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, pedidoId, usuarioActualId(), nota.trim(), esVisibleCliente);
        return Map.of("id", id);
    }

    /** Estados que admite el filtro. Fuera de esta lista → 400, nunca al SQL. */
    private static final java.util.Set<String> ESTADOS_PEDIDO = java.util.Set.of(
            "pendiente", "confirmado", "pagado", "facturado", "en_preparacion",
            "preparado", "despachado", "entregado", "devuelto", "no_entregado", "cancelado");

    private static final java.util.Set<String> CANALES_PEDIDO =
            java.util.Set.of("web", "tienda", "telefono");

    // Cabecera con dinero y sin dinero. La segregación financiera es la de
    // siempre: BODEGA/DESPACHO no tienen SELECT sobre pedido.total en el motor,
    // así que su consulta ni lo nombra.
    // OJO: aquí NO se proyecta `tiene_factura`.
    //
    // Era un `EXISTS (SELECT 1 FROM factura_venta ...)` en la lista del SELECT, y
    // bajo RLS eso no es una búsqueda por índice: la política de `factura_venta`
    // lleva una función que no es «leakproof», así que PostgreSQL no puede
    // empujar la correlación `fv.pedido_id = p.id` por debajo del filtro de
    // seguridad y resuelve el subplan recorriendo las 2.855.378 facturas
    // ENTERAS — medido: 2.878.568 buffers y 4,3 s por petición, aunque la página
    // sean 25 filas. El índice `idx_factura_venta_pedido` existe y no se puede
    // usar. Su único consumidor era el selector de pedidos facturables, y esa
    // regla vive ahora en el WHERE (`facturables=true`), donde sí se resuelve
    // como anti-join sobre el conjunto ya acotado.
    private static final String SEL_PEDIDOS_COMPLETO = """
            SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.total, p.fecha_pedido,
                   c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                   t.nombre AS transportista
            """;

    private static final String SEL_PEDIDOS_LOGISTICO = """
            SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                   c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                   t.nombre AS transportista
            """;

    private static final String JOIN_BASE = """
            FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            """;

    /** Solo hace falta para PINTAR la fila y para buscar por nombre de cliente. */
    private static final String JOIN_CLIENTE = " JOIN cliente c ON c.id = p.cliente_id\n";

    /** LEFT JOIN: nunca cambia el número de filas, así que el conteo lo omite. */
    private static final String JOIN_TRANSPORTISTA =
            " LEFT JOIN transportista t ON t.id = p.transportista_id\n";

    private static final String W_FACTURABLES = """
             AND ep.codigo IN ('pagado', 'en_preparacion', 'despachado', 'entregado')
             AND NOT EXISTS (SELECT 1 FROM factura_venta fv WHERE fv.pedido_id = p.id)
            """;

    /**
     * Listado de pedidos, PAGINADO EN EL SERVIDOR.
     *
     * <h3>Por qué dejó de devolver una lista</h3>
     * Devolvía {@code List<Map>} con la tabla entera. Con 2.999.993 pedidos eso
     * no era un endpoint lento: el montón subía a 2,03 GiB, saltaba
     * {@code OutOfMemoryError: Java heap space} y moría el hilo
     * {@code http-nio-8080-Poller} de Tomcat, con lo que el backend COMPLETO
     * dejaba de responder hasta reiniciar el contenedor. Ahora devuelve el sobre
     * {@code {items, total, page, size}} y jamás más de
     * {@link com.retailmind.comun.Paginacion#MAX_PAGINA} filas, aunque se pidan.
     *
     * <h3>Los filtros van AQUÍ y no en la pantalla</h3>
     * Las pantallas de facturación y despacho no listan pedidos: eligen UNO
     * entre los que cumplen una condición («facturable», «preparado»). Antes se
     * traían la tabla completa y filtraban en el navegador; con el listado
     * paginado ese filtro habría mirado solo la página visible y habría dado un
     * resultado plausible y FALSO. Por eso el filtro se evalúa contra el
     * conjunto completo, en SQL, y `total` cuenta ese mismo conjunto.
     *
     * @param facturables atajo del predicado exacto que aplicaba la pantalla de
     *                    facturas: estado en (pagado, en_preparacion, despachado,
     *                    entregado) y sin factura. Es la MISMA regla, movida de
     *                    sitio, no una regla nueva.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarPedidos(Integer page, Integer size, String estado,
                                             String canal, String q, Boolean facturables,
                                             Boolean conTotal) {
        String est = filtroDeLista(estado, ESTADOS_PEDIDO, "estado");
        String can = filtroDeLista(canal, CANALES_PEDIDO, "canal");
        String busq = (q == null || q.isBlank()) ? null : q.trim();
        boolean soloFacturables = Boolean.TRUE.equals(facturables);

        // El WHERE se arma SOLO con los filtros presentes. Las piezas son
        // constantes del código y los valores viajan como parámetros ligados:
        // aquí no se concatena nada que venga del usuario. Además evita el plan
        // genérico que producen las guardas `(? IS NULL OR ...)` cuando no hay
        // filtro, que es justo el caso normal de esta pantalla.
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> args = new java.util.ArrayList<>();
        if (est != null) { w.append(" AND ep.codigo = ?\n"); args.add(est); }
        if (can != null) { w.append(" AND p.canal = ?\n"); args.add(can); }
        if (busq != null) {
            w.append(" AND (p.numero ILIKE ?"
                   + " OR (c.nombre || ' ' || COALESCE(c.apellido,'')) ILIKE ?)\n");
            args.add("%" + busq + "%");
            args.add("%" + busq + "%");
        }
        if (soloFacturables) { w.append(W_FACTURABLES); }

        String where = w.toString();
        Object[] a = args.toArray();

        String select = esRolLogistico() ? SEL_PEDIDOS_LOGISTICO : SEL_PEDIDOS_COMPLETO;
        // ORDENA POR FECHA Y NO POR ID. Parecen lo mismo y aquí NO lo son: la
        // carga masiva escribió sus 3.000.000 de pedidos en bandas de id
        // reservadas (hasta 2.100.055.830) con fechas de hasta 2034, mientras
        // que la secuencia real va por 4.343. Con `ORDER BY p.id DESC` un
        // pedido hecho HOY nace con id ~4.344 y aparece en la ÚLTIMA página,
        // cientos de páginas detrás del seed: el cliente no encontraba su
        // compra recién hecha en «Mis Pedidos», y el vendedor tampoco veía en
        // el back-office los pedidos del día. El `id` se conserva como
        // desempate para que el orden sea total y la paginación estable
        // (hay pedidos que comparten `fecha_pedido` al microsegundo).
        String sqlItems = select + JOIN_BASE + JOIN_CLIENTE + JOIN_TRANSPORTISTA + where
                        + " ORDER BY p.fecha_pedido DESC, p.id DESC";

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }

        // El conteo NO arrastra los joins que solo sirven para pintar la fila.
        // Medido bajo `grp_administrador` con RLS: el conteo de facturables pasa
        // de 57,7 s con `cliente` y `transportista` colgando a 8,8 s sin ellos.
        //
        // Y va CON TOPE: sin filtro son 2.999.993 pedidos y contarlos cuesta
        // 4,3 s porque RLS evalúa `esta_en_horario()` por fila. Con el tope el
        // total es exacto hasta 200.000 y por encima viaja declarado como
        // mínimo (`totalEsMinimo`), que la pantalla pinta como «más de».
        String cuerpoConteo = JOIN_BASE + (busq != null ? JOIN_CLIENTE : "") + where;

        return com.retailmind.comun.Paginacion.paginarConTope(
                pg, sqlItems, cuerpoConteo, a, pag, tam);
    }

    /** Valida un filtro contra su lista blanca: fuera de ella, 400 legible. */
    private static String filtroDeLista(String valor, java.util.Set<String> permitidos,
                                        String nombre) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.trim();
        if (!permitidos.contains(v)) {
            throw new IllegalArgumentException("Valor no admitido para el filtro «" + nombre
                    + "»: " + v);
        }
        return v;
    }

    // ── Pago del cliente (pago + transaccion_pago) ───────────────────────

    /**
     * Registra el cobro de un pedido (efectivo/transferencia). Compuerta:
     * solo pedidos pendientes/confirmados con saldo; al cubrir el total el
     * pedido pasa a 'pagado' (lo que habilita facturar y despachar).
     * Admite abonos parciales; monto null = saldo completo.
     */
    @Transactional
    public Map<String, Object> registrarPago(long pedidoId, long metodoPagoId,
                                             BigDecimal monto, String referencia) {
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT p.id, p.numero, p.total, p.moneda_id, p.canal, ep.codigo AS estado
                FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.id = ? FOR UPDATE OF p""", pedidoId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el pedido " + pedidoId);
        }
        Map<String, Object> ped = filas.get(0);
        String estado = (String) ped.get("estado");
        String numero = (String) ped.get("numero");
        // Un pedido ONLINE (canal web) se paga en el checkout de la tienda y
        // nace 'pagado'; el cobro manual queda reservado a pedidos internos.
        if ("web".equals(ped.get("canal"))) {
            throw new IllegalStateException("El pedido " + numero
                    + " es de la tienda online: el cliente lo paga en el checkout. "
                    + "El cobro manual solo aplica a pedidos internos (tienda/telefono)");
        }
        switch (estado) {
            case "pagado", "facturado", "en_preparacion", "preparado",
                 "despachado", "entregado" ->
                    throw new IllegalStateException("El pedido " + numero
                            + " ya esta pagado; no admite mas cobros");
            case "cancelado", "devuelto" -> throw new IllegalStateException(
                    "No se puede cobrar un pedido en estado '" + estado + "'");
            default -> { } // pendiente / confirmado: cobrables
        }
        List<String> metodos = pg.queryForList(
                "SELECT nombre FROM metodo_pago WHERE id = ? AND activo", String.class, metodoPagoId);
        if (metodos.isEmpty()) {
            throw new IllegalArgumentException("El metodo de pago no existe o esta inactivo");
        }

        BigDecimal total = (BigDecimal) ped.get("total");
        BigDecimal saldo = total.subtract(totalPagado(pedidoId));
        if (monto == null) monto = saldo;
        if (monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto del pago debe ser mayor a cero");
        }
        if (monto.compareTo(saldo) > 0) {
            throw new IllegalArgumentException("El pago (" + monto
                    + ") excede el saldo pendiente del pedido (" + saldo + ")");
        }

        Long pagoId = pg.queryForObject("""
                INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado,
                                  referencia_externa, fecha_pago)
                VALUES (?, ?, ?, ?, 'completado', NULLIF(?, ''), now())
                RETURNING id""",
                Long.class, pedidoId, metodoPagoId,
                ((Number) ped.get("moneda_id")).longValue(), monto, referencia);
        pg.update("""
                INSERT INTO transaccion_pago (pago_id, tipo, estado, monto)
                VALUES (?, 'captura', 'exitosa', ?)""", pagoId, monto);

        BigDecimal nuevoSaldo = saldo.subtract(monto);
        boolean cubierto = nuevoSaldo.signum() == 0;
        if (cubierto) {
            cambiarEstadoPedido(pedidoId, "pagado",
                    "Pago del cliente registrado (" + metodos.get(0) + ") — total cubierto");
        } else {
            registrarHistorial(pedidoId, estado, "Abono del cliente por " + monto
                    + " (" + metodos.get(0) + ") — saldo pendiente " + nuevoSaldo);
        }
        return Map.of("pagoId", pagoId, "totalPagado", total.subtract(nuevoSaldo),
                "saldoPendiente", nuevoSaldo, "estadoPedido", cubierto ? "pagado" : estado);
    }

    /**
     * Pago SIMULADO del checkout online: se registra en la MISMA transacción
     * que acaba de crear el pedido (no requiere FOR UPDATE: la fila aún no es
     * visible para nadie más) y lo deja 'pagado' por el total. Corre bajo
     * grp_cliente (INSERT en pago/transaccion_pago, script 36). El detalle de
     * tarjeta llega ya SANITIZADO por el caller: marca + últimos 4, nunca el
     * número completo ni el CVV.
     */
    @Transactional
    public Map<String, Object> pagarCheckoutOnline(long pedidoId, long metodoPagoId,
                                                   String referencia, String codigoAutorizacion,
                                                   String detalleJson) {
        Map<String, Object> ped = pg.queryForMap("""
                SELECT p.numero, p.total, p.moneda_id, ep.codigo AS estado
                FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.id = ?""", pedidoId);
        if (!"confirmado".equals(ped.get("estado"))) {
            throw new IllegalStateException("El pedido " + ped.get("numero")
                    + " no admite el pago del checkout (estado '" + ped.get("estado") + "')");
        }
        List<String> metodos = pg.queryForList(
                "SELECT nombre FROM metodo_pago WHERE id = ? AND activo", String.class, metodoPagoId);
        if (metodos.isEmpty()) {
            throw new IllegalArgumentException("El metodo de pago no existe o esta inactivo");
        }
        BigDecimal total = (BigDecimal) ped.get("total");

        Long pagoId = pg.queryForObject("""
                INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado,
                                  referencia_externa, fecha_pago)
                VALUES (?, ?, ?, ?, 'completado', NULLIF(?, ''), now())
                RETURNING id""",
                Long.class, pedidoId, metodoPagoId,
                ((Number) ped.get("moneda_id")).longValue(), total, referencia);
        pg.update("""
                INSERT INTO transaccion_pago (pago_id, tipo, estado, monto,
                                              codigo_autorizacion, respuesta_pasarela)
                VALUES (?, 'captura', 'exitosa', ?, ?, ?::jsonb)""",
                pagoId, total, codigoAutorizacion, detalleJson);

        cambiarEstadoPedido(pedidoId, "pagado", "Pago online confirmado en el checkout ("
                + metodos.get(0) + (referencia != null && !referencia.isBlank()
                        ? " · " + referencia : "") + ")");
        // Factura AUTOMÁTICA del pedido online: misma transacción que el pago
        // (compra online real: el comprobante nace con el cobro, sin pasos
        // manuales del back-office). El pedido queda 'facturado' y entra a la
        // cola de preparación de bodega.
        Map<String, Object> factura = emitirFactura(pedidoId, true);
        return Map.of("pagoId", pagoId, "monto", total, "metodo", metodos.get(0),
                "facturaId", factura.get("id"), "facturaNumero", factura.get("numero"));
    }

    /**
     * Rastro de un intento de cobro RECHAZADO por la pasarela simulada
     * (OTD-VEN-12, script 52): pago 'fallido' SIN pedido (el checkout es una
     * sola transacción y al rechazar no se crea pedido, ni stock, ni factura)
     * + transaccion_pago 'autorizacion'/'fallida' con el motivo y el contexto
     * (cliente, carrito) en respuesta_pasarela.
     *
     * REQUIRES_NEW: corre en su PROPIA transacción (PgSessionRoleAspect asume
     * grp_cliente también en ella) y se confirma ANTES de que el checkout
     * propague el error y revierta todo lo demás. La función SECURITY DEFINER
     * existe porque pol_cliente_pago exige pedido propio y aquí no hay pedido.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long registrarIntentoPagoFallido(long clienteId, long metodoPagoId,
                                            BigDecimal monto, String referencia,
                                            String motivo, long carritoId) {
        Long id = pg.queryForObject(
                "SELECT fn_registrar_intento_pago_fallido(?, ?, ?, ?, ?, ?)",
                Long.class, clienteId, metodoPagoId, monto, referencia, motivo, carritoId);
        if (id == null) {
            throw new IllegalStateException("No se pudo registrar el intento de pago fallido");
        }
        return id;
    }

    /** Suma de pagos completados del pedido. */
    private BigDecimal totalPagado(long pedidoId) {
        return pg.queryForObject("""
                SELECT COALESCE(SUM(monto), 0) FROM pago
                WHERE pedido_id = ? AND estado = 'completado'""", BigDecimal.class, pedidoId);
    }

    // ── Caso 8: factura de venta ─────────────────────────────────────────

    /** Emisión MANUAL (VENDEDOR/ADMIN) para pedidos internos; los online se
     *  facturan solos al pagar el checkout. */
    @Transactional
    public Map<String, Object> emitirFactura(long pedidoId) {
        return emitirFactura(pedidoId, false);
    }

    /**
     * Emite la factura del pedido y lo pasa a 'facturado' (entra a la cola de
     * preparación de bodega). Compuerta: solo un pedido 'pagado' se factura,
     * y una sola vez. Con automatica=true la dispara el pago del checkout
     * online, en la MISMA transacción (corre bajo grp_cliente: INSERT +
     * política pol_cliente_emision del script 39).
     */
    @Transactional
    public Map<String, Object> emitirFactura(long pedidoId, boolean automatica) {
        // Guardia de idempotencia: un pedido se factura una sola vez. Las
        // facturas ANULADAS no cuentan: un pedido con su factura anulada
        // puede volver a facturarse (saneamiento script 43).
        List<String> existentes = pg.queryForList(
                "SELECT numero FROM factura_venta WHERE pedido_id = ? AND estado <> 'anulada'",
                String.class, pedidoId);
        if (!existentes.isEmpty()) {
            throw new IllegalStateException("El pedido ya fue facturado (factura "
                    + existentes.get(0) + "); no se puede facturar de nuevo");
        }
        String estado = estadoPedido(pedidoId);
        if (List.of("cancelado", "devuelto").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede facturar un pedido en estado '" + estado + "'");
        }
        // Compuerta: la factura se emite sobre un pedido ya COBRADO
        if (!"pagado".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido debe estar pagado antes de emitir la factura; "
                    + "registra primero el pago del cliente (estado actual: '" + estado + "')");
        }

        Map<String, Object> datos = pg.queryForMap("""
                SELECT p.cliente_id, p.moneda_id,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS razon_social,
                       COALESCE(c.numero_identificacion, '9999999999') AS identificacion,
                       (SELECT d.calle_principal || COALESCE(', ' || d.referencia, '')
                        FROM direccion d WHERE d.usuario_id = c.usuario_id
                        ORDER BY d.es_predeterminada DESC LIMIT 1) AS direccion
                FROM pedido p JOIN cliente c ON c.id = p.cliente_id
                WHERE p.id = ?""", pedidoId);

        String numero = siguienteNumero("FV");
        Long facturaId = pg.queryForObject("""
                INSERT INTO factura_venta
                    (numero, pedido_id, cliente_id, moneda_id, razon_social,
                     identificacion, direccion_facturacion, estado)
                VALUES (?, ?, ?, ?, trim(?), ?, ?, 'emitida')
                RETURNING id""",
                Long.class, numero, pedidoId,
                ((Number) datos.get("cliente_id")).longValue(),
                ((Number) datos.get("moneda_id")).longValue(),
                (String) datos.get("razon_social"), (String) datos.get("identificacion"),
                (String) datos.get("direccion"));

        // Detalle copiado del pedido (snapshot). subtotal = columna generada.
        // El descuento de línea arrastra la promoción y PRORRATEA el cupón de
        // cabecera (pedido.monto_descuento) entre las líneas: el trigger
        // SECURITY DEFINER de la factura recalcula sus totales solo desde el
        // detalle, así el total facturado coincide con el total del pedido.
        BigDecimal cupon = pg.queryForObject(
                "SELECT monto_descuento FROM pedido WHERE id = ?", BigDecimal.class, pedidoId);
        List<Map<String, Object>> lineas = pg.queryForList("""
                SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
                       precio_unitario, subtotal, monto_descuento, monto_impuesto
                FROM pedido_detalle WHERE pedido_id = ? ORDER BY id""", pedidoId);
        BigDecimal baseNeta = lineas.stream()
                .map(l -> ((BigDecimal) l.get("subtotal")).subtract((BigDecimal) l.get("monto_descuento")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cuponRepartido = BigDecimal.ZERO;
        for (int i = 0; i < lineas.size(); i++) {
            Map<String, Object> l = lineas.get(i);
            BigDecimal neto = ((BigDecimal) l.get("subtotal"))
                    .subtract((BigDecimal) l.get("monto_descuento"));
            BigDecimal prorrateo = BigDecimal.ZERO;
            if (cupon != null && cupon.signum() > 0 && baseNeta.signum() > 0) {
                prorrateo = i == lineas.size() - 1
                        ? cupon.subtract(cuponRepartido)   // última línea: ajuste de redondeo
                        : cupon.multiply(neto).divide(baseNeta, 2, RoundingMode.HALF_UP);
                cuponRepartido = cuponRepartido.add(prorrateo);
            }
            pg.update("""
                    INSERT INTO factura_venta_detalle
                        (factura_venta_id, pedido_detalle_id, producto_variante_id,
                         descripcion, cantidad, precio_unitario, monto_descuento, monto_impuesto)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    facturaId, ((Number) l.get("id")).longValue(),
                    ((Number) l.get("producto_variante_id")).longValue(),
                    l.get("nombre_producto") + " (" + l.get("sku") + ")",
                    ((Number) l.get("cantidad")).intValue(),
                    l.get("precio_unitario"),
                    ((BigDecimal) l.get("monto_descuento")).add(prorrateo),
                    l.get("monto_impuesto"));
        }

        // El pedido pasa a 'facturado': entra a la cola de preparación de bodega
        cambiarEstadoPedido(pedidoId, "facturado", "Factura de venta " + numero
                + (automatica
                   ? " emitida AUTOMÁTICAMENTE al confirmar el pago online"
                   : " emitida") + "; pedido en cola de preparación de bodega");
        return obtenerFactura(facturaId); // totales ya recalculados por el trigger
    }

    /**
     * Listado de facturas de venta emitidas, con búsqueda y paginación.
     *
     * <h3>Dos defectos que se sumaban, y el segundo era el gordo</h3>
     * <ol>
     *   <li><b>La búsqueda vacía filtraba igual.</b> Con la caja de búsqueda en
     *       blanco el filtro era {@code '%%'} y el WHERE quedaba en
     *       {@code numero ILIKE '%%' OR razon_social ILIKE '%%' OR p.numero
     *       ILIKE '%%'} — siempre cierto, pero evaluado con tres ILIKE sobre
     *       2.855.378 facturas MÁS un JOIN a `pedido` (3,0 M, también con RLS).
     *       Medido: el conteo pasaba de 5.867 ms a <b>13.171 ms</b> solo por
     *       arrastrar ese predicado que no filtra nada. Ahora, sin texto, no se
     *       añade ni el WHERE ni el JOIN.</li>
     *   <li><b>El conteo no tenía tope.</b> Contar 2.855.378 facturas bajo RLS
     *       cuesta 5.867 ms porque `esta_en_horario()` se evalúa por fila. Se
     *       pasa a {@link com.retailmind.comun.Paginacion#paginarConTope}: el
     *       total es EXACTO hasta 200.000 y por encima viaja como mínimo
     *       declarado, y la pantalla lo dice.</li>
     * </ol>
     * El JOIN a `pedido` solo se conserva cuando hace falta: para pintar
     * `numero_pedido` en la página, y en el conteo únicamente si se busca por
     * número de pedido. Es un INNER JOIN sobre una FK NOT NULL a la clave
     * primaria, así que quitarlo del conteo no cambia el número de filas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarFacturas(String q, int page, int size) {
        int tam = Math.min(Math.max(size, 1), 100);
        int pagina = Math.max(page, 0);
        String busq = (q == null || q.isBlank()) ? null : q.trim();

        // El WHERE se arma SOLO si hay búsqueda; las piezas son constantes del
        // código y el texto viaja como parámetro ligado.
        String where = busq == null ? "" : """
                 WHERE fv.numero ILIKE ? OR fv.razon_social ILIKE ?
                    OR p.numero ILIKE ?
                """;
        Object[] args = busq == null ? new Object[0]
                : new Object[] { "%" + busq + "%", "%" + busq + "%", "%" + busq + "%" };

        String cuerpoConteo = "FROM factura_venta fv"
                + (busq == null ? "" : " JOIN pedido p ON p.id = fv.pedido_id")
                + where;

        String sqlItems = """
                SELECT fv.id, fv.numero, fv.estado, fv.fecha_emision, fv.total,
                       fv.razon_social AS cliente, fv.pedido_id, p.numero AS numero_pedido
                FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                """ + where + " ORDER BY fv.id DESC";

        return com.retailmind.comun.Paginacion.paginarConTope(
                pg, sqlItems, cuerpoConteo, args, pagina, tam);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerFactura(long facturaId) {
        Map<String, Object> f = pg.queryForMap("""
                SELECT fv.id, fv.numero, fv.estado, fv.fecha_emision, fv.razon_social,
                       fv.identificacion, fv.direccion_facturacion, fv.pedido_id,
                       fv.subtotal, fv.monto_descuento, fv.monto_impuesto, fv.total,
                       p.numero AS numero_pedido
                FROM factura_venta fv JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.id = ?""", facturaId);
        f.put("detalles", pg.queryForList("""
                SELECT id, descripcion, cantidad, precio_unitario, subtotal,
                       monto_descuento, monto_impuesto
                FROM factura_venta_detalle WHERE factura_venta_id = ? ORDER BY id""", facturaId));
        return f;
    }

    // ── Preparación por BODEGA (picking/empaque, script 39) ──────────────

    /** Las cuatro columnas caras de la cola: una subconsulta y un LATERAL POR
     *  FILA. Con la cola entera (26.551 pedidos) esto tardaba 27,5 s; sobre una
     *  página de 25 se paga 25 veces, no 26.551. */
    private static final String SEL_PREPARACION = """
            SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                   c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                   fv.numero AS factura, t.nombre AS transportista,
                   me.nombre AS metodo_envio,
                   (SELECT COUNT(*) FROM pedido_detalle pd
                    WHERE pd.pedido_id = p.id) AS items,
                   (SELECT COALESCE(SUM(pd.cantidad), 0) FROM pedido_detalle pd
                    WHERE pd.pedido_id = p.id) AS unidades
            FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            JOIN cliente c ON c.id = p.cliente_id
            LEFT JOIN LATERAL (SELECT numero FROM factura_venta
                               WHERE pedido_id = p.id ORDER BY id DESC LIMIT 1) fv ON true
            LEFT JOIN transportista t ON t.id = p.transportista_id
            LEFT JOIN metodo_envio me ON me.id = p.metodo_envio_id
            """;

    /**
     * El filtro de estado de la cola, POR ID Y CONTRA EL ÍNDICE.
     *
     * Estaba escrito como {@code WHERE ep.codigo IN ('facturado',
     * 'en_preparacion')}, lo que obliga a unir `estado_pedido` y deja el
     * predicado sobre una columna de OTRA tabla: el plan era un
     * {@code Parallel Seq Scan} de los 3,0 M de `pedido` con
     * `esta_en_horario()` evaluada fila a fila — <b>4.446 ms</b> para encontrar
     * 26.551 filas.
     *
     * Aquí la comparación es {@code estado_pedido_id = ANY(ARRAY[...])} con los
     * ids resueltos por subconsulta escalar (InitPlan: se evalúan UNA vez). El
     * operador es {@code int4eq}, que sí es leakproof, así que puede bajar a
     * {@code Index Cond} sobre `idx_pedido_estado` pese al qual de seguridad de
     * RLS. Medido: <b>109 ms</b>, y el conteo sigue siendo EXACTO — no hace
     * falta ningún tope aquí.
     *
     * Los códigos siguen siendo la fuente de verdad; lo que cambia es que se
     * traducen a id antes de tocar `pedido`, no dentro del recorrido.
     */
    private static final String W_PREPARACION = """
             WHERE p.estado_pedido_id = ANY (ARRAY[
                     (SELECT id FROM estado_pedido WHERE codigo = 'facturado'),
                     (SELECT id FROM estado_pedido WHERE codigo = 'en_preparacion')])
            """;

    /**
     * Cola de preparación: pedidos facturados (por tomar) y en preparación.
     * SIN montos: es una vista operativa de BODEGA (segregación financiera).
     *
     * <h3>Por qué dejó de devolver una lista</h3>
     * Devolvía la cola COMPLETA —26.551 pedidos, 7,01 MB— y la pantalla los
     * pintaba todos: era el endpoint más lento del sistema (27,5 s medidos)
     * porque cada fila arrastra dos subconsultas sobre `pedido_detalle` y un
     * LATERAL sobre `factura_venta`. Ahora devuelve el sobre
     * {@code {items, total, page, size}}.
     *
     * <h3>El desempate del ORDER BY no es cosmético</h3>
     * Ordenaba solo por {@code fecha_pedido}, que NO es única: dos pedidos del
     * mismo instante pueden salir en distinto orden en dos consultas, y con
     * LIMIT/OFFSET eso hace que una fila aparezca en dos páginas y otra en
     * ninguna. Se desempata por {@code p.id}, que sí lo es.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> colaPreparacion(Integer page, Integer size, Boolean conTotal,
                                               String q) {
        // Búsqueda por número de pedido o cliente. La cola son 26.551 pedidos
        // ordenados por fecha ASCENDENTE, así que un pedido recién facturado
        // entra por el FINAL: sin este filtro, bodega no tiene forma de llegar
        // a él salvo paginando cientos de páginas. El criterio se evalúa contra
        // la cola COMPLETA, nunca sobre la página ya recortada.
        String busq = (q == null || q.isBlank()) ? null : q.trim();
        String filtro = busq == null ? ""
                : " AND (p.numero ILIKE ? OR (c.nombre || ' ' || COALESCE(c.apellido,'')) ILIKE ?)\n";
        Object[] a = busq == null ? new Object[0]
                : new Object[] { "%" + busq + "%", "%" + busq + "%" };

        String sqlItems = SEL_PREPARACION + W_PREPARACION + filtro
                        + " ORDER BY p.fecha_pedido, p.id";

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }
        // El conteo ya no une `estado_pedido`: el filtro va por id contra el
        // índice, así que sobra el join que antes obligaba a recorrer la tabla.
        // `cliente` solo se une cuando hay búsqueda, que es cuando hace falta.
        String sqlCount = "SELECT count(*) FROM pedido p"
                        + (busq != null ? " JOIN cliente c ON c.id = p.cliente_id" : "")
                        + W_PREPARACION + filtro;
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, sqlCount, a, pag, tam);
    }

    /**
     * Detalle del pedido a preparar / despachar: ítems con cantidades,
     * cliente, dirección de entrega y transportista asignado. Consulta
     * dedicada (no obtenerPedido) para que corra con los grants de
     * grp_bodega / grp_despacho, que no leen pagos, notas NI MONTOS
     * (segregación financiera: cantidades sí, precios no).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detalleLogistico(long pedidoId) {
        List<Map<String, Object>> pedidos = pg.queryForList("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                       c.telefono AS cliente_telefono,
                       p.transportista_id, t.nombre AS transportista,
                       p.metodo_envio_id, me.nombre AS metodo_envio,
                       me.dias_entrega_min, me.dias_entrega_max,
                       fv.numero AS factura,
                       COALESCE(d.calle_principal
                                || COALESCE(' ' || d.numero, '')
                                || COALESCE(', ' || d.referencia, '')
                                || COALESCE(' — ' || ci.nombre, ''),
                                'Retiro en tienda') AS direccion_entrega
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN transportista t ON t.id = p.transportista_id
                LEFT JOIN metodo_envio me ON me.id = p.metodo_envio_id
                LEFT JOIN LATERAL (SELECT numero FROM factura_venta
                                   WHERE pedido_id = p.id ORDER BY id DESC LIMIT 1) fv ON true
                LEFT JOIN direccion d ON d.id = COALESCE(p.direccion_envio_id,
                        (SELECT d2.id FROM direccion d2
                         WHERE d2.usuario_id = c.usuario_id AND d2.activo
                         ORDER BY d2.es_predeterminada DESC, d2.id LIMIT 1))
                LEFT JOIN ciudad ci ON ci.id = d.ciudad_id
                WHERE p.id = ?""", pedidoId);
        if (pedidos.isEmpty()) {
            throw new NoSuchElementException("No existe el pedido " + pedidoId);
        }
        Map<String, Object> pedido = pedidos.get(0);
        pedido.put("detalles", pg.queryForList("""
                SELECT id, sku, nombre_producto, cantidad
                FROM pedido_detalle WHERE pedido_id = ? ORDER BY id""", pedidoId));
        return pedido;
    }

    /** Compuerta: solo un pedido FACTURADO entra a preparación (picking). */
    @Transactional
    public Map<String, Object> iniciarPreparacion(long pedidoId) {
        String estado = estadoPedido(pedidoId);
        if ("en_preparacion".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido ya está en preparación; márcalo como preparado al terminar");
        }
        if (!"facturado".equals(estado)) {
            throw new IllegalStateException(
                    "Solo se puede preparar un pedido FACTURADO; este pedido está en estado '"
                    + estado + "'" + (List.of("pendiente", "confirmado", "pagado")
                            .contains(estado) ? " (falta la factura de venta)" : ""));
        }
        cambiarEstadoPedido(pedidoId, "en_preparacion",
                "Preparación iniciada por bodega (picking en curso)");
        return detalleLogistico(pedidoId);
    }

    /** Compuerta: solo un pedido EN PREPARACIÓN se marca preparado. */
    @Transactional
    public Map<String, Object> marcarPreparado(long pedidoId) {
        String estado = estadoPedido(pedidoId);
        if ("preparado".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido ya está preparado; queda a la espera del despacho");
        }
        if ("facturado".equals(estado)) {
            throw new IllegalStateException(
                    "Inicia primero la preparación del pedido (picking) antes de marcarlo preparado");
        }
        if (!"en_preparacion".equals(estado)) {
            throw new IllegalStateException(
                    "Solo se puede marcar preparado un pedido en preparación; "
                    + "este pedido está en estado '" + estado + "'");
        }
        cambiarEstadoPedido(pedidoId, "preparado",
                "Pedido preparado por bodega (picking y empaque completos); listo para despacho");
        return detalleLogistico(pedidoId);
    }

    // ── Caso 9: despachar pedido ─────────────────────────────────────────

    /**
     * Despacha un pedido PREPARADO por bodega. El transportista/método vienen
     * asignados por zona en el pedido; DESPACHO puede pasarlos en la request
     * para hacer override (optimización logística), y el cambio queda
     * registrado en la línea de tiempo.
     */
    @Transactional
    public Map<String, Object> despachar(long pedidoId, Long transportistaId, Long metodoEnvioId,
                                         Long bodegaId, String observacion) {
        // Guardia de estado: solo se despacha una vez y desde un estado valido
        String estado = estadoPedido(pedidoId);
        if (List.of("despachado", "entregado").contains(estado)) {
            List<String> guias = pg.queryForList(
                    "SELECT numero_guia FROM envio WHERE pedido_id = ? ORDER BY id",
                    String.class, pedidoId);
            throw new IllegalStateException("El pedido ya fue despachado"
                    + (guias.isEmpty() ? "" : " (guia " + guias.get(0) + ")")
                    + "; no se puede despachar de nuevo");
        }
        // Compuertas: pagado -> facturado -> preparado por bodega -> despachable
        if (List.of("pendiente", "confirmado").contains(estado)) {
            throw new IllegalStateException(
                    "El pedido debe estar pagado antes de despachar; "
                    + "registra primero el pago del cliente (estado actual: '" + estado + "')");
        }
        if ("pagado".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido debe tener factura de venta emitida antes del despacho");
        }
        if (List.of("facturado", "en_preparacion").contains(estado)) {
            throw new IllegalStateException(
                    "Bodega debe PREPARAR el pedido (picking y empaque) antes del despacho "
                    + "(estado actual: '" + estado + "')");
        }
        if (!"preparado".equals(estado)) {
            throw new IllegalStateException(
                    "No se puede despachar un pedido en estado '" + estado + "'");
        }
        List<String> facturas = pg.queryForList(
                "SELECT numero FROM factura_venta WHERE pedido_id = ?", String.class, pedidoId);
        if (facturas.isEmpty()) {
            throw new IllegalStateException(
                    "El pedido debe tener factura de venta emitida antes del despacho");
        }

        // Transportista/método: el asignado por zona, salvo override de despacho
        Map<String, Object> asignado = pg.queryForMap(
                "SELECT transportista_id, metodo_envio_id FROM pedido WHERE id = ?", pedidoId);
        Long transportistaFinal = transportistaId != null ? transportistaId
                : asignado.get("transportista_id") != null
                        ? ((Number) asignado.get("transportista_id")).longValue() : null;
        Long metodoFinal = metodoEnvioId != null ? metodoEnvioId
                : asignado.get("metodo_envio_id") != null
                        ? ((Number) asignado.get("metodo_envio_id")).longValue() : null;
        if (transportistaFinal == null || metodoFinal == null) {
            throw new IllegalArgumentException("El pedido no tiene transportista/método de "
                    + "envío asignado: selecciónalos para despachar");
        }
        Long transportistaAsignado = asignado.get("transportista_id") != null
                ? ((Number) asignado.get("transportista_id")).longValue() : null;
        String cambioTransportista = null;
        if (transportistaAsignado != null && !transportistaAsignado.equals(transportistaFinal)) {
            List<String> nombres = pg.queryForList("""
                    SELECT nombre FROM transportista WHERE id IN (?, ?) ORDER BY id = ?""",
                    String.class, transportistaAsignado, transportistaFinal, transportistaFinal);
            cambioTransportista = "Transportista cambiado por despacho: "
                    + (nombres.size() > 1 ? nombres.get(0) + " → " + nombres.get(1)
                                          : "override manual");
        }
        // El pedido refleja el transportista/método reales del envío
        if (!transportistaFinal.equals(transportistaAsignado)
                || !metodoFinal.equals(asignado.get("metodo_envio_id") != null
                        ? ((Number) asignado.get("metodo_envio_id")).longValue() : null)) {
            pg.update("UPDATE pedido SET transportista_id = ?, metodo_envio_id = ? WHERE id = ?",
                    transportistaFinal, metodoFinal, pedidoId);
        }

        String direccion = pg.queryForObject("""
                SELECT COALESCE(
                    (SELECT d.calle_principal || COALESCE(', ' || d.referencia, '')
                     FROM pedido p JOIN cliente c ON c.id = p.cliente_id
                     JOIN direccion d ON d.usuario_id = c.usuario_id
                     WHERE p.id = ? ORDER BY d.es_predeterminada DESC LIMIT 1),
                    'Retiro en tienda')""", String.class, pedidoId);

        String numero = siguienteNumero("EN");
        String guia = "GUIA-" + numero.substring(3);
        BigDecimal pesoTotal = pesoTotalPedido(pedidoId);
        BigDecimal costoEnvio = costoEnvioPorTarifa(pedidoId, metodoFinal, pesoTotal);
        // despachado_por = autor del JWT (trazabilidad, script 42)
        Long envioId = pg.queryForObject("""
                INSERT INTO envio (numero, pedido_id, transportista_id, metodo_envio_id,
                                   bodega_id, direccion_entrega, numero_guia, estado, costo,
                                   peso_total_kg, fecha_despacho, fecha_entrega_estimada,
                                   despachado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'en_transito', ?, ?::numeric, now(),
                        current_date + COALESCE((SELECT dias_entrega_max FROM metodo_envio
                                                 WHERE id = ?), 3)::int, ?)
                RETURNING id""",
                Long.class, numero, pedidoId, transportistaFinal, metodoFinal,
                bodegaId, direccion, guia, costoEnvio, pesoTotal, metodoFinal,
                usuarioActualId());
        auditoria.registrar("envio", envioId, "INSERT",
                Map.of("estado_pedido", "preparado"),
                Map.of("pedido_id", pedidoId, "numero_guia", guia,
                       "transportista_id", transportistaFinal,
                       "costo_envio", costoEnvio,
                       "estado_pedido", "despachado"));

        pg.update("""
                INSERT INTO envio_detalle (envio_id, pedido_detalle_id, cantidad)
                SELECT ?, id, cantidad FROM pedido_detalle WHERE pedido_id = ?""",
                envioId, pedidoId);

        pg.update("""
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                VALUES (?, 'en_transito', ?, 'Bodega RetailMind - Quevedo')""",
                envioId, "Paquete entregado al transportista"
                        + (cambioTransportista != null ? " · " + cambioTransportista : "")
                        + (observacion != null && !observacion.isBlank()
                           ? " · " + observacion : ""));

        cambiarEstadoPedido(pedidoId, "despachado", "Despachado con guia " + guia
                + (cambioTransportista != null ? " · " + cambioTransportista : ""));
        return obtenerEnvio(envioId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerEnvio(long envioId) {
        Map<String, Object> envio = pg.queryForMap("""
                SELECT e.id, e.numero, e.numero_guia, e.estado, e.fecha_despacho,
                       e.direccion_entrega, t.nombre AS transportista, me.nombre AS metodo_envio,
                       p.numero AS numero_pedido
                FROM envio e
                LEFT JOIN transportista t ON t.id = e.transportista_id
                LEFT JOIN metodo_envio me ON me.id = e.metodo_envio_id
                JOIN pedido p ON p.id = e.pedido_id
                WHERE e.id = ?""", envioId);
        envio.put("detalles", pg.queryForList("""
                SELECT ed.cantidad, pd.sku, pd.nombre_producto
                FROM envio_detalle ed JOIN pedido_detalle pd ON pd.id = ed.pedido_detalle_id
                WHERE ed.envio_id = ?""", envioId));
        return envio;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> seguimiento(long envioId) {
        return pg.queryForList("""
                SELECT estado, descripcion, ubicacion, fecha_evento
                FROM seguimiento_envio WHERE envio_id = ? ORDER BY id""", envioId);
    }

    // ── Entrega del pedido (cierra la logística) ─────────────────────────

    /** Compuerta: solo un pedido despachado puede marcarse entregado. */
    @Transactional
    public Map<String, Object> entregar(long pedidoId, String observacion) {
        String estado = estadoPedido(pedidoId);
        if ("entregado".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido ya fue marcado como entregado; no se puede entregar de nuevo");
        }
        if (!"despachado".equals(estado)) {
            throw new IllegalStateException(
                    "Solo se puede marcar la entrega de un pedido despachado "
                    + "(estado actual: '" + estado + "')");
        }
        // Cierra el envío vigente (el más reciente) y deja rastro de seguimiento
        List<Map<String, Object>> envios = pg.queryForList(
                "SELECT id, numero_guia, estado FROM envio WHERE pedido_id = ? ORDER BY id DESC",
                pedidoId);
        // Compuerta de novedades: un envío 'fallido' no se entrega sin resolver
        if (!envios.isEmpty() && "fallido".equals(envios.get(0).get("estado"))) {
            throw new IllegalStateException("El envío tiene una novedad abierta; "
                    + "resuélvela (reprogramar o devolver al almacén) antes de marcar la entrega");
        }
        String guia = null;
        if (!envios.isEmpty()) {
            long envioId = ((Number) envios.get(0).get("id")).longValue();
            guia = (String) envios.get(0).get("numero_guia");
            pg.update("""
                    UPDATE envio SET estado = 'entregado', fecha_entrega_real = now()
                    WHERE id = ?""", envioId);
            pg.update("""
                    INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                    VALUES (?, 'entregado', ?, 'Domicilio del cliente')""",
                    envioId, "Paquete entregado al cliente"
                            + (observacion != null && !observacion.isBlank()
                               ? " · " + observacion : ""));
        }
        cambiarEstadoPedido(pedidoId, "entregado",
                "Pedido entregado al cliente" + (guia != null ? " (guia " + guia + ")" : ""));
        // DESPACHO no lee pagos ni montos (segregación financiera): respuesta
        // operativa ligera; el resto de roles recibe el pedido completo.
        if (esRolLogistico()) {
            Map<String, Object> res = new java.util.LinkedHashMap<>();
            res.put("id", pedidoId);
            res.put("numero", pg.queryForObject(
                    "SELECT numero FROM pedido WHERE id = ?", String.class, pedidoId));
            res.put("estado", "entregado");
            res.put("envio", envios.isEmpty() ? null
                    : Map.of("id", envios.get(0).get("id"), "numero_guia",
                             guia == null ? "" : guia, "estado", "entregado"));
            return res;
        }
        return obtenerPedido(pedidoId);
    }

    // La devolución (RMA / logística inversa) vive en devoluciones/
    // (DevolucionService, script 38): nace del CLIENTE, la valida SOPORTE,
    // DESPACHO/BODEGA hacen el retorno físico y el stock reingresa SOLO tras
    // la inspección de bodega. El registro directo en un paso se eliminó.

    // ── Novedades / incidencias de envío (script 44) ─────────────────────

    /** Tipos de novedad = CHECK de novedad_envio (lista blanca) con su
     *  etiqueta legible para seguimiento/historial. */
    private static final Map<String, String> TIPOS_NOVEDAD = Map.of(
            "cliente_ausente", "cliente ausente",
            "direccion_incorrecta", "dirección incorrecta",
            "cliente_rechazo", "el cliente rechazó el paquete",
            "zona_dificil_acceso", "zona de difícil acceso",
            "dano_en_transito", "daño en tránsito");

    /** Tras este nº de intentos fallidos solo queda devolver al almacén. */
    public static final int MAX_INTENTOS_ENTREGA = 3;

    /**
     * Registra una novedad/incidencia sobre un envío EN TRÁNSITO (pedido
     * despachado, aún no entregado). El envío pasa a 'fallido' hasta que
     * despacho la resuelva (reprogramar o devolver al almacén); el pedido
     * sigue 'despachado'. Autor del JWT + rastro en seguimiento, historial
     * del pedido y log_auditoria.
     */
    @Transactional
    public Map<String, Object> registrarNovedad(long envioId, String tipo, String descripcion) {
        if (tipo == null || !TIPOS_NOVEDAD.containsKey(tipo)) {
            throw new IllegalArgumentException(
                    "Tipo de novedad inválido: usa uno de " + TIPOS_NOVEDAD.keySet());
        }
        Map<String, Object> envio = envioPorId(envioId);
        String estadoEnvio = (String) envio.get("estado");
        long pedidoId = ((Number) envio.get("pedido_id")).longValue();
        switch (estadoEnvio) {
            case "entregado" -> throw new IllegalStateException("El envío ya fue entregado; "
                    + "no admite novedades (si el cliente quiere devolver, aplica la RMA)");
            case "devuelto" -> throw new IllegalStateException(
                    "El envío ya fue devuelto al almacén; no admite más novedades");
            case "fallido" -> throw new IllegalStateException("El envío ya tiene una novedad "
                    + "abierta; resuélvela (reprogramar o devolver al almacén)");
            case "en_transito" -> { }
            default -> throw new IllegalStateException(
                    "Solo se registran novedades sobre envíos despachados/en tránsito "
                    + "(estado actual del envío: '" + estadoEnvio + "')");
        }
        int intento = intentoActual(envioId);
        Long id = pg.queryForObject("""
                INSERT INTO novedad_envio (envio_id, pedido_id, tipo, descripcion,
                                           intento_numero, registrado_por)
                VALUES (?, ?, ?, NULLIF(?, ''), ?, ?) RETURNING id""",
                Long.class, envioId, pedidoId, tipo, descripcion, intento, usuarioActualId());
        pg.update("UPDATE envio SET estado = 'fallido' WHERE id = ?", envioId);
        pg.update("""
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                VALUES (?, 'fallido', ?, 'Ruta de entrega')""",
                envioId, "Novedad en la entrega (intento " + intento + "): "
                        + TIPOS_NOVEDAD.get(tipo)
                        + (descripcion != null && !descripcion.isBlank()
                           ? " · " + descripcion : ""));
        registrarHistorial(pedidoId, "despachado",
                "Novedad de envío registrada: " + TIPOS_NOVEDAD.get(tipo));
        auditoria.registrar("novedad_envio", id, "INSERT",
                Map.of("envio_estado", "en_transito"),
                Map.of("envio_id", envioId, "pedido_id", pedidoId, "tipo", tipo,
                       "intento", intento, "envio_estado", "fallido"));
        return novedadesDePedido(pedidoId);
    }

    /**
     * Resuelve la novedad reprogramando un SEGUNDO (o tercer) intento de
     * entrega: el envío vuelve a 'en_transito' con nueva fecha estimada.
     * Máximo MAX_INTENTOS_ENTREGA intentos; después solo queda devolver.
     */
    @Transactional
    public Map<String, Object> reprogramarEntrega(long novedadId, String observacion) {
        Map<String, Object> n = novedadAbierta(novedadId, "reprogramar la entrega");
        long envioId = ((Number) n.get("envio_id")).longValue();
        long pedidoId = ((Number) n.get("pedido_id")).longValue();
        int intento = ((Number) n.get("intento_numero")).intValue();
        if (intento >= MAX_INTENTOS_ENTREGA) {
            throw new IllegalStateException("Se alcanzó el máximo de " + MAX_INTENTOS_ENTREGA
                    + " intentos de entrega; devuelve el envío al almacén");
        }
        pg.update("""
                UPDATE novedad_envio SET estado = 'resuelta', accion = 'reprogramada',
                       resuelto_por = ?, fecha_resolucion = now() WHERE id = ?""",
                usuarioActualId(), novedadId);
        pg.update("""
                UPDATE envio SET estado = 'en_transito',
                       fecha_entrega_estimada = current_date + COALESCE(
                           (SELECT me.dias_entrega_max FROM metodo_envio me
                            WHERE me.id = envio.metodo_envio_id), 3)::int
                WHERE id = ?""", envioId);
        int nuevoIntento = intento + 1;
        pg.update("""
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                VALUES (?, 'en_transito', ?, 'Ruta de entrega')""",
                envioId, "Entrega reprogramada: intento " + nuevoIntento + " de "
                        + MAX_INTENTOS_ENTREGA
                        + (observacion != null && !observacion.isBlank()
                           ? " · " + observacion : ""));
        registrarHistorial(pedidoId, "despachado",
                "Entrega reprogramada (intento " + nuevoIntento + " de " + MAX_INTENTOS_ENTREGA
                + ") tras novedad: " + TIPOS_NOVEDAD.get((String) n.get("tipo")));
        auditoria.registrar("novedad_envio", novedadId, "UPDATE",
                Map.of("estado", "abierta", "envio_estado", "fallido"),
                Map.of("accion", "reprogramada", "intento_nuevo", nuevoIntento,
                       "envio_estado", "en_transito"));
        return novedadesDePedido(pedidoId);
    }

    /**
     * Resuelve la novedad devolviendo el envío al almacén: el envío queda
     * 'devuelto' y el pedido pasa a 'no_entregado' (terminal). Criterio de
     * stock/RMA documentado: aquí NO se reingresa stock — el kardex solo se
     * mueve tras inspección FÍSICA de bodega (mismo criterio que la RMA,
     * script 38). La RMA de cliente no aplica (exige pedido entregado): el
     * reembolso/reingreso del pedido no entregado se gestiona vía ticket de
     * soporte + gerencia (deuda documentada).
     */
    @Transactional
    public Map<String, Object> devolverAlmacen(long novedadId, String observacion) {
        Map<String, Object> n = novedadAbierta(novedadId, "devolver al almacén");
        long envioId = ((Number) n.get("envio_id")).longValue();
        long pedidoId = ((Number) n.get("pedido_id")).longValue();
        String motivo = TIPOS_NOVEDAD.get((String) n.get("tipo"));
        pg.update("""
                UPDATE novedad_envio SET estado = 'resuelta', accion = 'devuelto_almacen',
                       resuelto_por = ?, fecha_resolucion = now() WHERE id = ?""",
                usuarioActualId(), novedadId);
        pg.update("UPDATE envio SET estado = 'devuelto' WHERE id = ?", envioId);
        pg.update("""
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                VALUES (?, 'devuelto', ?, 'Bodega RetailMind - Quevedo')""",
                envioId, "Envío devuelto al almacén tras novedad: " + motivo
                        + (observacion != null && !observacion.isBlank()
                           ? " · " + observacion : ""));
        cambiarEstadoPedido(pedidoId, "no_entregado",
                "Pedido no entregado: envío devuelto al almacén (" + motivo
                + "); el stock no se reingresa hasta la inspección de bodega");
        auditoria.registrar("novedad_envio", novedadId, "UPDATE",
                Map.of("estado", "abierta", "envio_estado", "fallido",
                       "estado_pedido", "despachado"),
                Map.of("accion", "devuelto_almacen", "envio_estado", "devuelto",
                       "estado_pedido", "no_entregado"));
        return novedadesDePedido(pedidoId);
    }

    /**
     * Envío vigente + intentos + historial de novedades del pedido. La usan
     * la pantalla de despacho y Mis Pedidos del cliente (RLS lo aísla a sus
     * pedidos; su consulta no une usuario porque grp_cliente no lo lee).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> novedadesDePedido(long pedidoId) {
        estadoPedido(pedidoId); // mensaje claro si no existe (o RLS lo oculta)
        List<Map<String, Object>> envios = pg.queryForList("""
                SELECT id, numero, numero_guia, estado, fecha_entrega_estimada
                FROM envio WHERE pedido_id = ? ORDER BY id DESC LIMIT 1""", pedidoId);
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("pedido_id", pedidoId);
        res.put("envio", envios.isEmpty() ? null : envios.get(0));
        if (envios.isEmpty()) {
            res.put("intentos", 0);
            res.put("novedades", List.of());
            return res;
        }
        long envioId = ((Number) envios.get(0).get("id")).longValue();
        res.put("intentos", intentoActual(envioId));
        res.put("max_intentos", MAX_INTENTOS_ENTREGA);
        boolean esCliente = "CLIENTE".equalsIgnoreCase(rolActual());
        res.put("novedades", esCliente
                ? pg.queryForList("""
                        SELECT id, tipo, descripcion, intento_numero, estado, accion,
                               fecha_registro, fecha_resolucion
                        FROM novedad_envio WHERE envio_id = ? ORDER BY id""", envioId)
                : pg.queryForList("""
                        SELECT n.id, n.tipo, n.descripcion, n.intento_numero, n.estado,
                               n.accion, n.fecha_registro, n.fecha_resolucion,
                               trim(concat(ur.nombre, ' ', COALESCE(ur.apellido, '')))
                                   AS registrado_por,
                               trim(concat(us.nombre, ' ', COALESCE(us.apellido, '')))
                                   AS resuelto_por
                        FROM novedad_envio n
                        LEFT JOIN usuario ur ON ur.id = n.registrado_por
                        LEFT JOIN usuario us ON us.id = n.resuelto_por
                        WHERE n.envio_id = ? ORDER BY n.id""", envioId));
        return res;
    }

    /** Envío por id con su pedido; 404 con mensaje claro si no existe. */
    private Map<String, Object> envioPorId(long envioId) {
        List<Map<String, Object>> envios = pg.queryForList(
                "SELECT id, pedido_id, estado, numero_guia FROM envio WHERE id = ?", envioId);
        if (envios.isEmpty()) {
            throw new NoSuchElementException("No existe el envío " + envioId);
        }
        return envios.get(0);
    }

    /** Nº del intento de entrega en curso = 1 + reprogramaciones previas. */
    private int intentoActual(long envioId) {
        Integer reprogramadas = pg.queryForObject("""
                SELECT COUNT(*) FROM novedad_envio
                WHERE envio_id = ? AND accion = 'reprogramada'""", Integer.class, envioId);
        return 1 + (reprogramadas == null ? 0 : reprogramadas);
    }

    /** Novedad ABIERTA con su envío; guardias de estado con mensaje claro. */
    private Map<String, Object> novedadAbierta(long novedadId, String accionPedida) {
        List<Map<String, Object>> novedades = pg.queryForList("""
                SELECT n.id, n.envio_id, n.pedido_id, n.tipo, n.estado, n.accion,
                       n.intento_numero, e.estado AS envio_estado
                FROM novedad_envio n JOIN envio e ON e.id = n.envio_id
                WHERE n.id = ?""", novedadId);
        if (novedades.isEmpty()) {
            throw new NoSuchElementException("No existe la novedad de envío " + novedadId);
        }
        Map<String, Object> n = novedades.get(0);
        if ("resuelta".equals(n.get("estado"))) {
            throw new IllegalStateException("La novedad ya fue resuelta ("
                    + ("reprogramada".equals(n.get("accion"))
                       ? "entrega reprogramada" : "envío devuelto al almacén")
                    + "); no se puede " + accionPedida + " de nuevo");
        }
        if ("entregado".equals(n.get("envio_estado"))) {
            throw new IllegalStateException(
                    "El envío ya fue entregado; no se puede " + accionPedida);
        }
        if (!"fallido".equals(n.get("envio_estado"))) {
            throw new IllegalStateException("El envío no está en estado de novedad "
                    + "(estado actual: '" + n.get("envio_estado") + "')");
        }
        return n;
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    /** Estado actual del pedido; falla con mensaje claro si el pedido no existe. */
    private String estadoPedido(long pedidoId) {
        List<String> estados = pg.queryForList("""
                SELECT ep.codigo FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.id = ?""", String.class, pedidoId);
        if (estados.isEmpty()) {
            throw new IllegalArgumentException("No existe el pedido " + pedidoId);
        }
        return estados.get(0);
    }

    private void cambiarEstadoPedido(long pedidoId, String estadoCodigo, String comentario) {
        int filas = pg.update("""
                UPDATE pedido SET estado_pedido_id = (SELECT id FROM estado_pedido WHERE codigo = ?)
                WHERE id = ?""", estadoCodigo, pedidoId);
        if (filas == 0) throw new IllegalArgumentException("No existe pedido " + pedidoId);
        registrarHistorial(pedidoId, estadoCodigo, comentario);
    }

    private void registrarHistorial(long pedidoId, String estadoCodigo, String comentario) {
        pg.update("""
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario)
                VALUES (?, (SELECT id FROM estado_pedido WHERE codigo = ?), ?, ?)""",
                pedidoId, estadoCodigo, usuarioActualId(), comentario);
    }

    private String siguienteNumero(String prefijo) {
        // Secuencia global (script 43): número único garantizado, sin la
        // colisión posible del sufijo aleatorio legacy.
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || nextval('seq_numero_documento')::text",
                String.class, prefijo);
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

    /** BODEGA/DESPACHO: roles operativos SIN acceso a montos (segregación financiera). */
    private boolean esRolLogistico() {
        String rol = rolActual();
        return "BODEGA".equalsIgnoreCase(rol) || "DESPACHO".equalsIgnoreCase(rol);
    }
}
