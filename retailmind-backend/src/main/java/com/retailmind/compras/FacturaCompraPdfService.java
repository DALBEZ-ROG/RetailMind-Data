package com.retailmind.compras;

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
 * Arma el {@link DocumentoPdf} de una FACTURA DE COMPRA leyendo la BD y
 * delega el render a {@link DocumentoPdfService}.
 *
 * === PATRÓN PARA EL CICLO DE VENTA ===
 * Crear un FacturaVentaPdfService equivalente que:
 *   1. Lea factura_venta + detalles + cliente + moneda.
 *   2. Construya DocumentoPdf con titulo "FACTURA DE VENTA" y
 *      contraparte etiqueta "CLIENTE".
 *   3. Llame a documentoPdfService.generar(doc).
 * El render (cabecera, tabla, totales, estilo) es 100% compartido.
 */
@Service
public class FacturaCompraPdfService {

    private final JdbcTemplate pg;
    private final DocumentoPdfService documentoPdfService;

    public FacturaCompraPdfService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                   DocumentoPdfService documentoPdfService) {
        this.pg = pg;
        this.documentoPdfService = documentoPdfService;
    }

    @Transactional(readOnly = true)
    public byte[] generarPdf(long facturaId) {
        Map<String, Object> f = pg.queryForMap("""
                SELECT fc.numero_factura, fc.estado, fc.fecha_emision, fc.fecha_vencimiento,
                       fc.subtotal, fc.monto_impuesto, fc.total,
                       oc.numero AS numero_orden,
                       m.simbolo AS moneda_simbolo, m.codigo AS moneda_codigo,
                       p.razon_social, p.ruc, p.direccion, p.email, p.telefono
                FROM factura_compra fc
                JOIN proveedor p ON p.id = fc.proveedor_id
                JOIN moneda m ON m.id = fc.moneda_id
                LEFT JOIN orden_compra oc ON oc.id = fc.orden_compra_id
                WHERE fc.id = ?""", facturaId);

        List<Map<String, Object>> detalles = pg.queryForList("""
                SELECT pv.sku, pr.nombre AS producto, d.cantidad,
                       d.precio_unitario, d.monto_impuesto, d.subtotal
                FROM factura_compra_detalle d
                JOIN producto_variante pv ON pv.id = d.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE d.factura_compra_id = ? ORDER BY d.id""", facturaId);

        String contacto = juntar((String) f.get("email"), (String) f.get("telefono"));
        DocumentoPdf doc = new DocumentoPdf("FACTURA DE COMPRA",
                (String) f.get("numero_factura"), String.valueOf(f.get("fecha_emision")))
                .estado((String) f.get("estado"))
                .contraparte(new DocumentoPdf.Parte("PROVEEDOR",
                        (String) f.get("razon_social"),
                        "RUC " + f.get("ruc"),
                        (String) f.get("direccion"),
                        contacto))
                .meta("Orden de compra", (String) f.get("numero_orden"))
                .meta("Moneda", String.valueOf(f.get("moneda_codigo")).trim())
                .meta("Vencimiento", f.get("fecha_vencimiento") != null
                        ? String.valueOf(f.get("fecha_vencimiento")) : null)
                .totales(new DocumentoPdf.Totales(
                        (BigDecimal) f.get("subtotal"),
                        (BigDecimal) f.get("monto_impuesto"),
                        (BigDecimal) f.get("total"),
                        (String) f.get("moneda_simbolo")))
                .notaPie("Documento generado por RetailMind · uso interno de compras");

        for (Map<String, Object> d : detalles) {
            doc.linea(new DocumentoPdf.Linea(
                    (String) d.get("sku"),
                    (String) d.get("producto"),
                    ((Number) d.get("cantidad")).intValue(),
                    (BigDecimal) d.get("precio_unitario"),
                    (BigDecimal) d.get("monto_impuesto"),
                    (BigDecimal) d.get("subtotal")));
        }
        return documentoPdfService.generar(doc);
    }

    private static String juntar(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " · " + b;
    }
}
