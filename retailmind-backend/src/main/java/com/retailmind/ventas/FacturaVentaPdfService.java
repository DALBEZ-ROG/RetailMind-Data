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
                       fv.subtotal, fv.monto_impuesto, fv.total,
                       p.numero AS numero_pedido, c.email AS cliente_email,
                       m.simbolo AS moneda_simbolo, m.codigo AS moneda_codigo
                FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                JOIN cliente c ON c.id = fv.cliente_id
                JOIN moneda m ON m.id = fv.moneda_id
                WHERE fv.id = ?""", facturaId);

        List<Map<String, Object>> detalles = pg.queryForList("""
                SELECT descripcion, cantidad, precio_unitario, monto_impuesto, subtotal
                FROM factura_venta_detalle
                WHERE factura_venta_id = ? ORDER BY id""", facturaId);

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
                .totales(new DocumentoPdf.Totales(
                        (BigDecimal) f.get("subtotal"),
                        (BigDecimal) f.get("monto_impuesto"),
                        (BigDecimal) f.get("total"),
                        (String) f.get("moneda_simbolo")))
                .notaPie("Gracias por su compra · RetailMind · www.retailmind.ec");

        for (Map<String, Object> d : detalles) {
            doc.linea(new DocumentoPdf.Linea(
                    null,
                    (String) d.get("descripcion"),
                    ((Number) d.get("cantidad")).intValue(),
                    (BigDecimal) d.get("precio_unitario"),
                    (BigDecimal) d.get("monto_impuesto"),
                    (BigDecimal) d.get("subtotal")));
        }
        return documentoPdfService.generar(doc);
    }
}
