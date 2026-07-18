package com.retailmind.marketing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * APLICACIÓN de los descuentos de marketing al ciclo de venta (script 40).
 *
 * Modelo de combinación (documentado):
 *  - PROMOCIONES: automáticas, POR LÍNEA. Van a pedido_detalle.monto_descuento
 *    y el trigger fn_recalcular_total_pedido ya las resta del subtotal. Si un
 *    producto tiene varias promociones vigentes gana la de mayor prioridad
 *    (empate: mayor descuento); solo si la ganadora es `acumulable` se le
 *    SUMAN las demás acumulables. Nunca se descuenta más que la línea.
 *  - CUPÓN: uno solo por pedido (UNIQUE uso_cupon.pedido_id), manual, DE
 *    CABECERA. Va a pedido.monto_descuento y el trigger de cabecera lo resta
 *    del total. Se calcula sobre el subtotal YA rebajado por promociones
 *    (sin IVA ni envío) — promoción + cupón SÍ se combinan; dos cupones NO.
 *  - El IVA se calcula por línea sobre la base con promoción; el cupón no
 *    recalcula el IVA (decisión documentada en DEUDA_TECNICA.md).
 *
 * La validación del cupón SIEMPRE ocurre aquí (backend) y el monto se
 * recalcula en el servidor al confirmar: el front solo envía el CÓDIGO.
 * El trigger fn_registrar_uso_cupon (SECURITY DEFINER, lock de fila) es el
 * backstop de concurrencia de los límites de uso.
 */
@Service
public class DescuentosService {

    private final JdbcTemplate pg;

    public DescuentosService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Promociones por línea ────────────────────────────────────────────

    /**
     * Descuento promocional de UNA línea (variante × cantidad). Devuelve
     * {monto, promocion} con monto 0 y promocion null si no hay vigentes.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> descuentoPromocional(long varianteId, BigDecimal precioUnitario,
                                                    int cantidad) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("monto", BigDecimal.ZERO.setScale(2));
        res.put("promocion", null);
        if (precioUnitario == null || cantidad <= 0) return res;

        List<Map<String, Object>> promos = pg.queryForList("""
                SELECT pr.nombre, pr.tipo_descuento, pr.valor, pr.acumulable
                FROM producto_variante pv
                JOIN promocion_producto pp ON pp.producto_id = pv.producto_id
                JOIN promocion pr ON pr.id = pp.promocion_id
                WHERE pv.id = ? AND pr.activo AND pr.fecha_inicio <= now()
                  AND (pr.fecha_fin IS NULL OR pr.fecha_fin > now())
                ORDER BY pr.prioridad DESC, pr.valor DESC""", varianteId);
        if (promos.isEmpty()) return res;

        BigDecimal bruto = precioUnitario.multiply(BigDecimal.valueOf(cantidad));
        BigDecimal monto = BigDecimal.ZERO;
        StringBuilder nombres = new StringBuilder();
        boolean ganadoraAcumulable = (Boolean) promos.get(0).get("acumulable");
        for (int i = 0; i < promos.size(); i++) {
            Map<String, Object> p = promos.get(i);
            // La ganadora siempre aplica; el resto solo si TODAS (ganadora
            // incluida) son acumulables.
            if (i > 0 && (!ganadoraAcumulable || !((Boolean) p.get("acumulable")))) continue;
            BigDecimal valor = (BigDecimal) p.get("valor");
            BigDecimal d = "porcentaje".equals(p.get("tipo_descuento"))
                    ? bruto.multiply(valor).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : valor.multiply(BigDecimal.valueOf(cantidad));   // monto_fijo por unidad
            monto = monto.add(d);
            if (nombres.length() > 0) nombres.append(" + ");
            nombres.append((String) p.get("nombre"));
        }
        if (monto.compareTo(bruto) > 0) monto = bruto;   // nunca más que la línea
        res.put("monto", monto.setScale(2, RoundingMode.HALF_UP));
        res.put("promocion", nombres.toString());
        return res;
    }

    // ── Validación de cupón (todas las reglas, con motivo claro) ─────────

    /**
     * Valida un código contra la base de compra (subtotal con promociones,
     * sin IVA ni envío). Devuelve {valido:true, cuponId, codigo,
     * tipoDescuento, descuento} o {valido:false, motivo}. Nunca lanza por
     * cupón inválido: el motivo viaja en la respuesta.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validarCupon(String codigo, long clienteId,
                                            BigDecimal baseCompra, BigDecimal costoEnvio) {
        if (codigo == null || codigo.isBlank()) {
            return invalido("Ingresa un código de cupón");
        }
        String cod = codigo.trim().toUpperCase();
        List<Map<String, Object>> cupones = pg.queryForList("""
                SELECT id, codigo, tipo_descuento, valor, monto_minimo_pedido,
                       usos_maximos, usos_por_cliente, usos_actuales,
                       fecha_inicio, fecha_fin, activo
                FROM cupon WHERE upper(codigo) = ?""", cod);
        if (cupones.isEmpty()) {
            return invalido("El cupón '" + cod + "' no existe");
        }
        Map<String, Object> c = cupones.get(0);
        if (!(Boolean) c.get("activo")) {
            return invalido("El cupón " + cod + " está inactivo");
        }
        long ahora = System.currentTimeMillis();
        java.sql.Timestamp inicio = (java.sql.Timestamp) c.get("fecha_inicio");
        java.sql.Timestamp fin = (java.sql.Timestamp) c.get("fecha_fin");
        if (inicio != null && ahora < inicio.getTime()) {
            return invalido("El cupón " + cod + " aún no está vigente");
        }
        if (fin != null && ahora > fin.getTime()) {
            return invalido("El cupón " + cod + " está vencido");
        }
        Integer usosMax = c.get("usos_maximos") != null
                ? ((Number) c.get("usos_maximos")).intValue() : null;
        int usosActuales = ((Number) c.get("usos_actuales")).intValue();
        if (usosMax != null && usosActuales >= usosMax) {
            return invalido("El cupón " + cod + " ya agotó sus usos disponibles");
        }
        int usosPorCliente = ((Number) c.get("usos_por_cliente")).intValue();
        Integer usosCliente = pg.queryForObject(
                "SELECT count(*) FROM uso_cupon WHERE cupon_id = ? AND cliente_id = ?",
                Integer.class, ((Number) c.get("id")).longValue(), clienteId);
        if (usosCliente != null && usosCliente >= usosPorCliente) {
            return invalido("Ya usaste el cupón " + cod + " el máximo permitido ("
                    + usosPorCliente + (usosPorCliente == 1 ? " vez)" : " veces)"));
        }
        BigDecimal minimo = (BigDecimal) c.get("monto_minimo_pedido");
        if (minimo != null && baseCompra.compareTo(minimo) < 0) {
            return invalido("El cupón " + cod + " requiere una compra mínima de $" + minimo
                    + " (tu subtotal es $" + baseCompra.setScale(2, RoundingMode.HALF_UP) + ")");
        }

        String tipo = (String) c.get("tipo_descuento");
        BigDecimal valor = (BigDecimal) c.get("valor");
        BigDecimal descuento;
        switch (tipo) {
            case "porcentaje" -> descuento = baseCompra.multiply(valor)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case "monto_fijo" -> descuento = valor.min(baseCompra);
            default -> {   // envio_gratis
                if (costoEnvio == null || costoEnvio.signum() == 0) {
                    return invalido("El cupón " + cod
                            + " es de envío gratis y tu pedido no tiene costo de envío");
                }
                descuento = costoEnvio;
            }
        }
        if (descuento.signum() <= 0) {
            return invalido("El cupón " + cod + " no genera descuento sobre esta compra");
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("valido", true);
        res.put("cuponId", ((Number) c.get("id")).longValue());
        res.put("codigo", cod);
        res.put("tipoDescuento", tipo);
        res.put("descuento", descuento.setScale(2, RoundingMode.HALF_UP));
        return res;
    }

    // ── Aplicación al pedido (misma transacción del checkout) ────────────

    /**
     * Recalcula y aplica el cupón a un pedido recién creado: escribe SOLO
     * pedido.monto_descuento (el trigger de cabecera rehace el total) y
     * registra el uso en uso_cupon (el trigger de la BD enforza los límites
     * bajo lock e incrementa usos_actuales). Cupón inválido -> 400 con el
     * motivo: el checkout completo se revierte y el cliente decide.
     */
    @Transactional
    public Map<String, Object> aplicarCupon(long pedidoId, String codigo, long clienteId) {
        // pedido.subtotal ya viene NETO de promociones (el trigger de detalle
        // suma subtotal - monto_descuento por línea)
        Map<String, Object> ped = pg.queryForMap(
                "SELECT subtotal, costo_envio FROM pedido WHERE id = ?", pedidoId);
        Map<String, Object> v = validarCupon(codigo, clienteId,
                (BigDecimal) ped.get("subtotal"), (BigDecimal) ped.get("costo_envio"));
        if (!Boolean.TRUE.equals(v.get("valido"))) {
            throw new IllegalArgumentException((String) v.get("motivo"));
        }
        BigDecimal descuento = (BigDecimal) v.get("descuento");
        pg.update("UPDATE pedido SET monto_descuento = ? WHERE id = ?", descuento, pedidoId);
        pg.update("""
                INSERT INTO uso_cupon (cupon_id, pedido_id, cliente_id, monto_descontado)
                VALUES (?, ?, ?, ?)""",
                ((Number) v.get("cuponId")).longValue(), pedidoId, clienteId, descuento);
        return v;
    }

    private static Map<String, Object> invalido(String motivo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("valido", false);
        res.put("motivo", motivo);
        return res;
    }
}
