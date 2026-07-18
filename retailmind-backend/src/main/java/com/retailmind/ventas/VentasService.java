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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPedidos() {
        // Segregación financiera: BODEGA/DESPACHO listan pedidos SIN montos
        // (sus grants de columna ya no incluyen total; su trabajo no lo necesita)
        if (esRolLogistico()) {
            return pg.queryForList("""
                    SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                           c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                           t.nombre AS transportista,
                           EXISTS (SELECT 1 FROM factura_venta fv
                                   WHERE fv.pedido_id = p.id) AS tiene_factura
                    FROM pedido p
                    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                    JOIN cliente c ON c.id = p.cliente_id
                    LEFT JOIN transportista t ON t.id = p.transportista_id
                    ORDER BY p.id DESC""");
        }
        // tiene_factura permite a los selectores ofrecer solo pedidos válidos
        return pg.queryForList("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.total, p.fecha_pedido,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                       t.nombre AS transportista,
                       EXISTS (SELECT 1 FROM factura_venta fv
                               WHERE fv.pedido_id = p.id) AS tiene_factura
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                LEFT JOIN transportista t ON t.id = p.transportista_id
                ORDER BY p.id DESC""");
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
        // Guardia de idempotencia: un pedido se factura una sola vez
        List<String> existentes = pg.queryForList(
                "SELECT numero FROM factura_venta WHERE pedido_id = ?", String.class, pedidoId);
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

    /** Listado de facturas de venta emitidas, con búsqueda y paginación. */
    @Transactional(readOnly = true)
    public Map<String, Object> listarFacturas(String q, int page, int size) {
        int tam = Math.min(Math.max(size, 1), 100);
        int pagina = Math.max(page, 0);
        String filtro = "%" + (q == null ? "" : q.trim()) + "%";
        Long total = pg.queryForObject("""
                SELECT COUNT(*) FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.numero ILIKE ? OR fv.razon_social ILIKE ? OR p.numero ILIKE ?""",
                Long.class, filtro, filtro, filtro);
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT fv.id, fv.numero, fv.estado, fv.fecha_emision, fv.total,
                       fv.razon_social AS cliente, fv.pedido_id, p.numero AS numero_pedido
                FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.numero ILIKE ? OR fv.razon_social ILIKE ? OR p.numero ILIKE ?
                ORDER BY fv.id DESC LIMIT ? OFFSET ?""",
                filtro, filtro, filtro, tam, pagina * tam);
        return Map.of("items", items, "total", total, "page", pagina, "size", tam);
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

    /** Cola de preparación: pedidos facturados (por tomar) y en preparación.
     *  SIN montos: es una vista operativa de BODEGA (segregación financiera). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> colaPreparacion() {
        return pg.queryForList("""
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
                WHERE ep.codigo IN ('facturado', 'en_preparacion')
                ORDER BY p.fecha_pedido""");
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
        // despachado_por = autor del JWT (trazabilidad, script 42)
        Long envioId = pg.queryForObject("""
                INSERT INTO envio (numero, pedido_id, transportista_id, metodo_envio_id,
                                   bodega_id, direccion_entrega, numero_guia, estado,
                                   fecha_despacho, fecha_entrega_estimada, despachado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'en_transito', now(),
                        current_date + COALESCE((SELECT dias_entrega_max FROM metodo_envio
                                                 WHERE id = ?), 3)::int, ?)
                RETURNING id""",
                Long.class, numero, pedidoId, transportistaFinal, metodoFinal,
                bodegaId, direccion, guia, metodoFinal, usuarioActualId());
        auditoria.registrar("envio", envioId, "INSERT",
                Map.of("estado_pedido", "preparado"),
                Map.of("pedido_id", pedidoId, "numero_guia", guia,
                       "transportista_id", transportistaFinal,
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
                "SELECT id, numero_guia FROM envio WHERE pedido_id = ? ORDER BY id DESC", pedidoId);
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
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || lpad(floor(random()*100000)::text, 5, '0')",
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
