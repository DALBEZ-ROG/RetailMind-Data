package com.retailmind.devoluciones;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.pdf.DocumentoPdf;
import com.retailmind.pdf.DocumentoPdfService;

/**
 * PDF de la GUÍA DE RETORNO del RMA (patrón FacturaVentaPdfService: este
 * servicio solo ARMA el DocumentoPdf; el render es del genérico). El cliente
 * la descarga desde Mis Devoluciones cuando soporte aprueba; la corre el rol
 * del solicitante (los datos salen de obtener(), que ya es role-aware y aísla
 * al CLIENTE a sus devoluciones).
 */
@Service
public class GuiaRetornoPdfService {

    private final DevolucionService devoluciones;
    private final DocumentoPdfService pdf;

    public GuiaRetornoPdfService(DevolucionService devoluciones, DocumentoPdfService pdf) {
        this.devoluciones = devoluciones;
        this.pdf = pdf;
    }

    @Transactional(readOnly = true)
    public byte[] generarPdf(long devolucionId) {
        Map<String, Object> d = devoluciones.obtener(devolucionId);
        String guia = (String) d.get("guia_retorno");
        if (guia == null || guia.isBlank()) {
            throw new IllegalStateException("La devolución aún no tiene guía de retorno: "
                    + "se genera cuando soporte aprueba la solicitud");
        }
        DocumentoPdf doc = new DocumentoPdf("GUÍA DE RETORNO (RMA)", guia,
                String.valueOf(d.get("fecha_creacion")).substring(0, 10))
                .estado((String) d.get("estado"))
                .contraparte(new DocumentoPdf.Parte("REMITENTE (CLIENTE)",
                        (String) d.get("cliente"), null, null, (String) d.get("cliente_email")))
                .meta("Devolución", (String) d.get("numero"))
                .meta("Pedido", (String) d.get("numero_pedido"))
                .meta("Motivo", (String) d.get("motivo"))
                .meta("Transportista", (String) d.get("transportista"))
                .meta("Entregar en", d.get("bodega") + (d.get("bodega_direccion") != null
                        ? " — " + d.get("bodega_direccion") : ""))
                .notaPie("Imprime esta guía, pégala al paquete y entrégalo al transportista "
                        + "indicado. La devolución se inspecciona al llegar al almacén; el "
                        + "reembolso depende del resultado. Documento simulado (RetailMind).");

        BigDecimal total = BigDecimal.ZERO;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detalles = (List<Map<String, Object>>) d.get("detalles");
        for (Map<String, Object> det : detalles) {
            int cantidad = ((Number) det.get("cantidad")).intValue();
            BigDecimal precio = (BigDecimal) det.get("precio_unitario");
            BigDecimal subtotal = precio.multiply(BigDecimal.valueOf(cantidad));
            total = total.add(subtotal);
            doc.linea(new DocumentoPdf.Linea((String) det.get("sku"),
                    (String) det.get("nombre_producto"), cantidad,
                    precio, BigDecimal.ZERO, subtotal));
        }
        doc.totales(new DocumentoPdf.Totales(total, BigDecimal.ZERO, total, "$"));
        return pdf.generar(doc);
    }
}
