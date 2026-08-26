package com.retailmind.ventas;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.pdf.DocumentoPdf;
import com.retailmind.pdf.DocumentoPdfService;

/**
 * PDF de la FACTURA DE VENTA: réplica exacta del patrón de
 * FacturaCompraPdfService — query cabecera + query detalle + mapeo a
 * DocumentoPdf (contraparte CLIENTE) + render con DocumentoPdfService.
 */
@Service
public class FacturaVentaPdfService {

    private final JdbcTemplate pg;
    private final DocumentoPdfService documentoPdfService;

    public FacturaVentaPdfService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                  DocumentoPdfService documentoPdfService) {
        this.pg = pg;
        this.documentoPdfService = documentoPdfService;
    }

    @Transactional(readOnly = true)
    public byte[] generarPdf(long facturaId) {
        Map<String, Object> f = pg.queryForMap("""
                SELECT fv.numero, fv.estado, fv.fecha_emision::date AS fecha,
                       fv.razon_social, fv.identificacion, fv.direccion_facturacion,
                       fv.subtotal, fv.monto_descuento, fv.monto_impuesto, fv.total,
                       p.numero AS numero_pedido, c.email AS cliente_email,
                       m.simbolo AS moneda_simbolo, m.codigo AS moneda_codigo,
                       cu.codigo AS cupon, uc.monto_descontado AS cupon_descuento
                FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                JOIN cliente c ON c.id = fv.cliente_id
                JOIN moneda m ON m.id = fv.moneda_id
                LEFT JOIN uso_cupon uc ON uc.pedido_id = fv.pedido_id
                LEFT JOIN cupon cu ON cu.id = uc.cupon_id
                WHERE fv.id = ?""", facturaId);

        // El SKU sale de la VARIANTE y no de la factura: `factura_venta_detalle`
        // guarda la descripción congelada pero no el código, y la columna
        // «Código» del PDF salía vacía en todas las líneas. El LEFT JOIN es a
        // propósito —una variante dada de baja no puede dejar la factura sin
        // emitir— y `grp_cliente` ya tiene SELECT sobre `producto_variante`,
        // que es como la tienda pinta el catálogo: no hizo falta ningún GRANT.
        List<Map<String, Object>> detalles = pg.queryForList("""
                SELECT d.descripcion, d.cantidad, d.precio_unitario,
                       d.monto_impuesto, d.subtotal, pv.sku
                FROM factura_venta_detalle d
                LEFT JOIN producto_variante pv ON pv.id = d.producto_variante_id
                WHERE d.factura_venta_id = ? ORDER BY d.id""", facturaId);

        DocumentoPdf doc = new DocumentoPdf("FACTURA DE VENTA",
                (String) f.get("numero"), String.valueOf(f.get("fecha")))
                .estado((String) f.get("estado"))
                .contraparte(new DocumentoPdf.Parte("CLIENTE",
                        (String) f.get("razon_social"),
                        "CI/RUC " + f.get("identificacion"),
                        (String) f.get("direccion_facturacion"),
                        (String) f.get("cliente_email")))
                .meta("Pedido", (String) f.get("numero_pedido"))
                .meta("Moneda", String.valueOf(f.get("moneda_codigo")).trim())
                // El importe del cupón ya no va aquí: vive pegado a la fila
                // «Descuento» de los totales, que es donde se busca.
                .meta("Cupón", (String) f.get("cupon"))
                .totales(new DocumentoPdf.Totales(
                        (BigDecimal) f.get("subtotal"),
                        (BigDecimal) f.get("monto_descuento"),
                        detalleDescuento(f),
                        (BigDecimal) f.get("monto_impuesto"),
                        (BigDecimal) f.get("total"),
                        (String) f.get("moneda_simbolo")))
                .notaPie("Gracias por su compra · RetailMind · www.retailmind.ec");

        for (Map<String, Object> d : detalles) {
            doc.linea(new DocumentoPdf.Linea(
                    (String) d.get("sku"),
                    sinCodigoRepetido((String) d.get("descripcion"), (String) d.get("sku")),
                    ((Number) d.get("cantidad")).intValue(),
                    (BigDecimal) d.get("precio_unitario"),
                    (BigDecimal) d.get("monto_impuesto"),
                    (BigDecimal) d.get("subtotal")));
        }
        return documentoPdfService.generar(doc);
    }

    /**
     * Quita del texto de la línea el SKU entre paréntesis que ya trae pegado.
     *
     * `factura_venta_detalle.descripcion` se congela al emitir con la forma
     * «Camiseta Dri-FIT (DRIFIT-M-NEG)», y eso se escribió cuando el PDF no
     * tenía columna de código. Ahora sí la tiene, y el SKU salía DOS VECES en
     * la misma fila. No se pierde nada: lo que se retira es exactamente lo que
     * la columna de al lado ya dice, y solo cuando coincide carácter a
     * carácter — cualquier otro paréntesis se respeta.
     */
    private static String sinCodigoRepetido(String descripcion, String sku) {
        if (descripcion == null || sku == null || sku.isBlank()) { return descripcion; }
        String sufijo = " (" + sku + ")";
        return descripcion.endsWith(sufijo)
                ? descripcion.substring(0, descripcion.length() - sufijo.length())
                : descripcion;
    }

    /**
     * Letra pequeña bajo la fila «Descuento» del bloque de totales.
     *
     * El descuento de la factura puede venir de DOS sitios y no son lo mismo:
     * el CUPÓN, que se aplica sobre el pedido entero, y las PROMOCIONES, que se
     * aplican línea a línea. Cuando hay cupón se nombra —es lo que el cliente
     * tecleó y quiere ver reflejado— y, si además la cifra descontada es mayor
     * que lo que aportó el cupón, se dice que el resto son promociones en vez
     * de dejar la diferencia sin explicar.
     */
    private static String detalleDescuento(Map<String, Object> f) {
        String cupon = (String) f.get("cupon");
        BigDecimal total = (BigDecimal) f.get("monto_descuento");
        if (cupon == null) {
            return total != null && total.signum() > 0 ? "Promociones aplicadas" : null;
        }
        BigDecimal delCupon = (BigDecimal) f.get("cupon_descuento");
        String texto = "Cupón " + cupon;
        if (delCupon != null && total != null && total.compareTo(delCupon) > 0) {
            texto += " + promociones";
        }
        return texto;
    }
}
