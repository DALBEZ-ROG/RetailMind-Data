package com.retailmind.pdf;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * Generador GENÉRICO de PDF para documentos comerciales (iText 5).
 * No conoce tablas ni ciclos: recibe un {@link DocumentoPdf} ya poblado y
 * devuelve los bytes del PDF. Lo usan la factura de COMPRA hoy y debe usarlo
 * la factura de VENTA mañana (solo cambia quién arma el DocumentoPdf).
 */
@Service
public class DocumentoPdfService {

    // Datos fijos del emisor (la tienda)
    private static final String EMISOR_NOMBRE    = "RETAILMIND S.A.";
    private static final String EMISOR_RUC       = "RUC 1291799999001";
    private static final String EMISOR_DIRECCION = "Av. Quito y Septima, Quevedo - Los Rios, Ecuador";
    private static final String EMISOR_CONTACTO  = "contacto@retailmind.ec · +593 5 275 0000";

    private static final BaseColor COLOR_PRIMARIO = new BaseColor(31, 41, 55);   // gris azulado oscuro
    private static final BaseColor COLOR_ACENTO   = new BaseColor(37, 99, 235);  // azul
    private static final BaseColor COLOR_SUAVE    = new BaseColor(243, 244, 246);// gris claro

    public byte[] generar(DocumentoPdf doc) {
        try {
            Document pdf = new Document(PageSize.A4, 40, 40, 40, 48);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(pdf, out);
            pdf.open();

            agregarCabecera(pdf, doc);
            agregarPartes(pdf, doc);
            agregarLineas(pdf, doc);
            agregarTotales(pdf, doc);

            if (doc.getNotaPie() != null) {
                Paragraph pie = new Paragraph(doc.getNotaPie(), fuente(9, false, BaseColor.GRAY));
                pie.setSpacingBefore(24);
                pie.setAlignment(Element.ALIGN_CENTER);
                pdf.add(pie);
            }

            pdf.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Error generando PDF: " + e.getMessage(), e);
        }
    }

    // ── Bloques del documento ────────────────────────────────────────────

    private void agregarCabecera(Document pdf, DocumentoPdf doc) throws DocumentException {
        PdfPTable cab = new PdfPTable(new float[]{60, 40});
        cab.setWidthPercentage(100);

        PdfPCell tienda = celdaSinBorde();
        tienda.addElement(new Paragraph(EMISOR_NOMBRE, fuente(16, true, COLOR_PRIMARIO)));
        tienda.addElement(new Paragraph(EMISOR_RUC, fuente(9, false, BaseColor.DARK_GRAY)));
        tienda.addElement(new Paragraph(EMISOR_DIRECCION, fuente(9, false, BaseColor.DARK_GRAY)));
        tienda.addElement(new Paragraph(EMISOR_CONTACTO, fuente(9, false, BaseColor.DARK_GRAY)));
        cab.addCell(tienda);

        PdfPCell caja = new PdfPCell();
        caja.setBackgroundColor(COLOR_PRIMARIO);
        caja.setPadding(10);
        caja.setBorder(Rectangle.NO_BORDER);
        Paragraph t = new Paragraph(doc.getTitulo(), fuente(13, true, BaseColor.WHITE));
        t.setAlignment(Element.ALIGN_CENTER);
        caja.addElement(t);
        Paragraph n = new Paragraph("N° " + doc.getNumero(), fuente(11, true, BaseColor.WHITE));
        n.setAlignment(Element.ALIGN_CENTER);
        caja.addElement(n);
        Paragraph f = new Paragraph("Fecha: " + doc.getFecha(), fuente(9, false, BaseColor.WHITE));
        f.setAlignment(Element.ALIGN_CENTER);
        caja.addElement(f);
        if (doc.getEstado() != null) {
            Paragraph e = new Paragraph("Estado: " + doc.getEstado().toUpperCase(),
                    fuente(9, false, BaseColor.WHITE));
            e.setAlignment(Element.ALIGN_CENTER);
            caja.addElement(e);
        }
        cab.addCell(caja);
        cab.setSpacingAfter(14);
        pdf.add(cab);
    }

    private void agregarPartes(Document pdf, DocumentoPdf doc) throws DocumentException {
        PdfPTable partes = new PdfPTable(new float[]{55, 45});
        partes.setWidthPercentage(100);

        DocumentoPdf.Parte p = doc.getContraparte();
        PdfPCell contraparte = new PdfPCell();
        contraparte.setBackgroundColor(COLOR_SUAVE);
        contraparte.setPadding(9);
        contraparte.setBorder(Rectangle.NO_BORDER);
        if (p != null) {
            contraparte.addElement(new Paragraph(p.etiqueta(), fuente(9, true, COLOR_ACENTO)));
            contraparte.addElement(new Paragraph(p.nombre(), fuente(11, true, COLOR_PRIMARIO)));
            if (p.identificacion() != null)
                contraparte.addElement(new Paragraph(p.identificacion(), fuente(9, false, BaseColor.DARK_GRAY)));
            if (p.direccion() != null)
                contraparte.addElement(new Paragraph(p.direccion(), fuente(9, false, BaseColor.DARK_GRAY)));
            if (p.contacto() != null)
                contraparte.addElement(new Paragraph(p.contacto(), fuente(9, false, BaseColor.DARK_GRAY)));
        }
        partes.addCell(contraparte);

        PdfPCell metas = new PdfPCell();
        metas.setPadding(9);
        metas.setBorder(Rectangle.NO_BORDER);
        for (Map.Entry<String, String> m : doc.getMetadatos().entrySet()) {
            Paragraph linea = new Paragraph();
            linea.add(new Phrase(m.getKey() + ":  ", fuente(9, true, COLOR_PRIMARIO)));
            linea.add(new Phrase(m.getValue(), fuente(9, false, BaseColor.DARK_GRAY)));
            metas.addElement(linea);
        }
        partes.addCell(metas);
        partes.setSpacingAfter(14);
        pdf.add(partes);
    }

    private void agregarLineas(Document pdf, DocumentoPdf doc) throws DocumentException {
        PdfPTable tabla = new PdfPTable(new float[]{16, 38, 9, 13, 11, 13});
        tabla.setWidthPercentage(100);

        for (String h : new String[]{"Código", "Descripción", "Cant.", "P. Unitario", "IVA", "Subtotal"}) {
            PdfPCell hc = new PdfPCell(new Phrase(h, fuente(9, true, BaseColor.WHITE)));
            hc.setBackgroundColor(COLOR_ACENTO);
            hc.setPadding(6);
            hc.setHorizontalAlignment(Element.ALIGN_CENTER);
            tabla.addCell(hc);
        }

        boolean par = false;
        for (DocumentoPdf.Linea l : doc.getLineas()) {
            BaseColor fondo = par ? COLOR_SUAVE : BaseColor.WHITE;
            tabla.addCell(celdaLinea(l.codigo(), Element.ALIGN_LEFT, fondo));
            tabla.addCell(celdaLinea(l.descripcion(), Element.ALIGN_LEFT, fondo));
            tabla.addCell(celdaLinea(String.valueOf(l.cantidad()), Element.ALIGN_CENTER, fondo));
            tabla.addCell(celdaLinea(monto(l.precioUnitario()), Element.ALIGN_RIGHT, fondo));
            tabla.addCell(celdaLinea(monto(l.impuesto()), Element.ALIGN_RIGHT, fondo));
            tabla.addCell(celdaLinea(monto(l.subtotal()), Element.ALIGN_RIGHT, fondo));
            par = !par;
        }
        pdf.add(tabla);
    }

    private void agregarTotales(Document pdf, DocumentoPdf doc) throws DocumentException {
        DocumentoPdf.Totales t = doc.getTotales();
        if (t == null) return;

        PdfPTable marco = new PdfPTable(new float[]{62, 38});
        marco.setWidthPercentage(100);
        marco.setSpacingBefore(10);
        marco.addCell(celdaSinBorde());

        PdfPTable caja = new PdfPTable(new float[]{50, 50});
        caja.setWidthPercentage(100);
        agregarFilaTotal(caja, "Subtotal", t.simboloMoneda() + " " + monto(t.subtotal()), false);
        if (t.descuento() != null && t.descuento().signum() > 0) {
            agregarFilaTotal(caja, "Descuento",
                    "- " + t.simboloMoneda() + " " + monto(t.descuento()), false);
        }
        agregarFilaTotal(caja, "IVA", t.simboloMoneda() + " " + monto(t.impuesto()), false);
        agregarFilaTotal(caja, "TOTAL", t.simboloMoneda() + " " + monto(t.total()), true);

        PdfPCell celdaCaja = new PdfPCell(caja);
        celdaCaja.setBorder(Rectangle.NO_BORDER);
        marco.addCell(celdaCaja);
        pdf.add(marco);
    }

    private void agregarFilaTotal(PdfPTable caja, String etiqueta, String valor, boolean destacado) {
        Font fe = destacado ? fuente(11, true, BaseColor.WHITE) : fuente(10, true, COLOR_PRIMARIO);
        Font fv = destacado ? fuente(11, true, BaseColor.WHITE) : fuente(10, false, BaseColor.DARK_GRAY);
        BaseColor fondo = destacado ? COLOR_PRIMARIO : COLOR_SUAVE;

        PdfPCell ce = new PdfPCell(new Phrase(etiqueta, fe));
        ce.setBackgroundColor(fondo);
        ce.setPadding(6);
        ce.setBorder(Rectangle.NO_BORDER);
        caja.addCell(ce);

        PdfPCell cv = new PdfPCell(new Phrase(valor, fv));
        cv.setBackgroundColor(fondo);
        cv.setPadding(6);
        cv.setBorder(Rectangle.NO_BORDER);
        cv.setHorizontalAlignment(Element.ALIGN_RIGHT);
        caja.addCell(cv);
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private static Font fuente(int tam, boolean negrita, BaseColor color) {
        return FontFactory.getFont(negrita ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA,
                tam, color);
    }

    private static PdfPCell celdaSinBorde() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static PdfPCell celdaLinea(String texto, int alineacion, BaseColor fondo) {
        PdfPCell c = new PdfPCell(new Phrase(texto == null ? "" : texto,
                fuente(9, false, BaseColor.DARK_GRAY)));
        c.setPadding(5);
        c.setBackgroundColor(fondo);
        c.setHorizontalAlignment(alineacion);
        c.setBorderColor(new BaseColor(229, 231, 235));
        return c;
    }

    private static String monto(BigDecimal v) {
        return v == null ? "0.00" : String.format("%,.2f", v);
    }
}
