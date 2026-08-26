package com.retailmind.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * Generador GENÉRICO de PDF para documentos comerciales (iText 5).
 *
 * No conoce tablas ni ciclos: recibe un {@link DocumentoPdf} ya poblado y
 * devuelve los bytes. Lo usan la factura de COMPRA, la de VENTA y la guía de
 * retorno del RMA; el aspecto es el mismo para las tres y se cambia SOLO aquí.
 *
 * <h2>Notas de iText 5 que cuestan una tarde si no se saben</h2>
 * <ul>
 *   <li><b>El pie de página no puede escribirse en el flujo</b>: el contenido
 *       fluye y no se sabe cuándo termina cada página. Va en un
 *       {@link PdfPageEventHelper}, que se dispara al cerrarlas.</li>
 *   <li><b>«Página X de Y» exige un {@link PdfTemplate}</b>: al pintar la
 *       página 1 todavía no se sabe cuántas habrá, así que se reserva un hueco
 *       y se estampa el total al cerrar el documento.</li>
 *   <li><b>El evento se registra ANTES de {@code open()}</b>. Después, el
 *       escritor ya emitió la primera página y el pie no sale en ella.</li>
 *   <li><b>Un {@code Paragraph} no tiene fondo</b>: cualquier cosa con color
 *       detrás —la etiqueta de estado, una regla— es una tabla de una celda.</li>
 * </ul>
 */
@Service
public class DocumentoPdfService {

    // Datos fijos del emisor (la tienda)
    private static final String EMISOR_NOMBRE    = "RETAILMIND S.A.";
    private static final String EMISOR_RUC       = "RUC 1291799999001";
    private static final String EMISOR_DIRECCION = "Av. Quito y Séptima, Quevedo - Los Ríos, Ecuador";
    private static final String EMISOR_CONTACTO  = "contacto@retailmind.ec  ·  +593 5 275 0000";

    /** Logotipo: se busca UNA vez y su ausencia no rompe el documento. */
    private static final String RUTA_LOGO = "/pdf/logo-retailmind.png";

    private static final BaseColor TINTA        = new BaseColor(17, 24, 39);    // casi negro
    private static final BaseColor TINTA_SUAVE  = new BaseColor(107, 114, 128); // gris medio
    private static final BaseColor ACENTO       = new BaseColor(21, 101, 192);  // azul RetailMind
    private static final BaseColor ACENTO_TENUE = new BaseColor(232, 240, 252);
    private static final BaseColor CEBRA        = new BaseColor(248, 250, 252);
    private static final BaseColor LINEA        = new BaseColor(226, 232, 240);
    private static final BaseColor VERDE        = new BaseColor(22, 128, 96);
    private static final BaseColor ROJO         = new BaseColor(179, 38, 30);

    /** Cache del logotipo: releerlo del classpath en cada factura no aporta nada. */
    private static byte[] logo;
    private static boolean logoBuscado;

    public byte[] generar(DocumentoPdf doc) {
        try {
            Document pdf = new Document(PageSize.A4, 38, 38, 34, 62);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            // ANTES de open(): si no, la página 1 sale sin pie.
            writer.setPageEvent(new Pie(doc));
            pdf.open();

            agregarCabecera(pdf, doc);
            agregarPartes(pdf, doc);
            agregarLineas(pdf, doc);
            agregarTotales(pdf, doc);

            if (doc.getNotaPie() != null) {
                pdf.add(notaFinal(doc.getNotaPie()));
            }

            pdf.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Error generando PDF: " + e.getMessage(), e);
        }
    }

    // ── Bloques del documento ────────────────────────────────────────────

    /**
     * Membrete: logotipo y datos del emisor a la izquierda, caja del documento
     * a la derecha. Debajo, una regla de acento que separa cabecera de cuerpo.
     */
    private void agregarCabecera(Document pdf, DocumentoPdf doc) throws DocumentException {
        PdfPTable cab = new PdfPTable(new float[]{58, 42});
        cab.setWidthPercentage(100);

        PdfPCell emisor = celdaSinBorde();
        emisor.setVerticalAlignment(Element.ALIGN_TOP);
        Image marca = logo();
        if (marca != null) {
            marca.scaleToFit(44, 48);
            Paragraph membrete = new Paragraph();
            // El desplazamiento vertical negativo alinea la base del logotipo
            // con la del texto; sin él la imagen "cuelga" sobre la línea.
            membrete.add(new Chunk(marca, 0, -7, true));
            membrete.add(new Chunk("   " + EMISOR_NOMBRE, fuente(17, true, TINTA)));
            membrete.setSpacingAfter(5);
            emisor.addElement(membrete);
        } else {
            emisor.addElement(new Paragraph(EMISOR_NOMBRE, fuente(17, true, TINTA)));
        }
        emisor.addElement(pequena(EMISOR_RUC));
        emisor.addElement(pequena(EMISOR_DIRECCION));
        emisor.addElement(pequena(EMISOR_CONTACTO));
        cab.addCell(emisor);

        PdfPCell caja = new PdfPCell();
        caja.setBackgroundColor(TINTA);
        caja.setPadding(12);
        caja.setBorder(Rectangle.NO_BORDER);
        caja.addElement(centrado(doc.getTitulo().toUpperCase(Locale.ROOT),
                fuente(11, true, new BaseColor(148, 179, 233)), 3));
        caja.addElement(centrado("N.º " + doc.getNumero(), fuente(14, true, BaseColor.WHITE), 4));
        caja.addElement(centrado("Emitido el " + doc.getFecha(),
                fuente(9, false, new BaseColor(203, 213, 225)), 0));
        if (doc.getEstado() != null) {
            caja.addElement(chipEstado(doc.getEstado()));
        }
        cab.addCell(caja);
        cab.setSpacingAfter(4);
        pdf.add(cab);
        pdf.add(regla(ACENTO, 2f, 14f));
    }

    /** Contraparte a la izquierda y metadatos del documento a la derecha. */
    private void agregarPartes(Document pdf, DocumentoPdf doc) throws DocumentException {
        PdfPTable partes = new PdfPTable(new float[]{55, 45});
        partes.setWidthPercentage(100);

        DocumentoPdf.Parte p = doc.getContraparte();
        PdfPCell contraparte = new PdfPCell();
        contraparte.setBackgroundColor(ACENTO_TENUE);
        contraparte.setPadding(11);
        contraparte.setBorder(Rectangle.NO_BORDER);
        if (p != null) {
            contraparte.addElement(rotulo(p.etiqueta()));
            contraparte.addElement(new Paragraph(p.nombre(), fuente(12, true, TINTA)));
            if (p.identificacion() != null) { contraparte.addElement(pequena(p.identificacion())); }
            if (p.direccion() != null)      { contraparte.addElement(pequena(p.direccion())); }
            if (p.contacto() != null)       { contraparte.addElement(pequena(p.contacto())); }
        }
        partes.addCell(contraparte);

        PdfPCell metas = new PdfPCell();
        metas.setPadding(11);
        metas.setPaddingLeft(16);
        metas.setBorder(Rectangle.NO_BORDER);
        if (!doc.getMetadatos().isEmpty()) {
            metas.addElement(rotulo("DATOS DEL DOCUMENTO"));
        }
        for (Map.Entry<String, String> m : doc.getMetadatos().entrySet()) {
            Paragraph linea = new Paragraph();
            linea.setLeading(13f);
            linea.add(new Phrase(m.getKey() + ":  ", fuente(9, true, TINTA)));
            linea.add(new Phrase(m.getValue(), fuente(9, false, TINTA_SUAVE)));
            metas.addElement(linea);
        }
        partes.addCell(metas);
        partes.setSpacingAfter(16);
        pdf.add(partes);
    }

    private void agregarLineas(Document pdf, DocumentoPdf doc) throws DocumentException {
        pdf.add(rotuloSuelto("DETALLE"));

        // El código va ancho (18 %) porque los SKU con guion —«Trionda-Pelota»—
        // se partían en dos líneas y el resto de la fila crecía con ellos.
        PdfPTable tabla = new PdfPTable(new float[]{18, 36, 8, 13, 11, 14});
        tabla.setWidthPercentage(100);
        // La cabecera se repite en cada página: una tabla de treinta líneas
        // parte en dos y la segunda mitad, sin encabezado, no se puede leer.
        tabla.setHeaderRows(1);

        String[] titulos = {"Código", "Descripción", "Cant.", "P. unitario", "IVA", "Subtotal"};
        int[] alineacion = {Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_CENTER,
                            Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT};
        for (int i = 0; i < titulos.length; i++) {
            PdfPCell hc = new PdfPCell(new Phrase(titulos[i], fuente(8.5f, true, BaseColor.WHITE)));
            hc.setBackgroundColor(TINTA);
            hc.setPaddingTop(7);
            hc.setPaddingBottom(7);
            hc.setPaddingLeft(7);
            hc.setPaddingRight(7);
            hc.setBorder(Rectangle.NO_BORDER);
            hc.setHorizontalAlignment(alineacion[i]);
            tabla.addCell(hc);
        }

        boolean par = false;
        for (DocumentoPdf.Linea l : doc.getLineas()) {
            BaseColor fondo = par ? CEBRA : BaseColor.WHITE;
            tabla.addCell(celdaLinea(codigoODefecto(l.codigo()), Element.ALIGN_LEFT, fondo, true));
            tabla.addCell(celdaLinea(l.descripcion(), Element.ALIGN_LEFT, fondo, false));
            tabla.addCell(celdaLinea(String.valueOf(l.cantidad()), Element.ALIGN_CENTER, fondo, false));
            tabla.addCell(celdaLinea(monto(l.precioUnitario()), Element.ALIGN_RIGHT, fondo, false));
            tabla.addCell(celdaLinea(monto(l.impuesto()), Element.ALIGN_RIGHT, fondo, false));
            tabla.addCell(celdaLinea(monto(l.subtotal()), Element.ALIGN_RIGHT, fondo, false));
            par = !par;
        }
        pdf.add(tabla);
    }

    private void agregarTotales(Document pdf, DocumentoPdf doc) throws DocumentException {
        DocumentoPdf.Totales t = doc.getTotales();
        if (t == null) { return; }

        PdfPTable marco = new PdfPTable(new float[]{55, 45});
        marco.setWidthPercentage(100);
        marco.setSpacingBefore(14);

        // Izquierda: el recuento de artículos, que es la primera comprobación
        // que hace cualquiera al recibir una factura.
        PdfPCell resumen = celdaSinBorde();
        if (!doc.getLineas().isEmpty()) {
            int lineas = doc.getLineas().size();
            int unidades = 0;
            for (DocumentoPdf.Linea l : doc.getLineas()) { unidades += l.cantidad(); }
            resumen.addElement(pequena(lineas + (lineas == 1 ? " artículo" : " artículos")
                    + "  ·  " + unidades + (unidades == 1 ? " unidad" : " unidades")));
        }
        marco.addCell(resumen);

        PdfPTable caja = new PdfPTable(new float[]{52, 48});
        caja.setWidthPercentage(100);
        agregarFilaTotal(caja, "Subtotal", null,
                t.simboloMoneda() + " " + monto(t.subtotal()), false);
        if (t.descuento() != null && t.descuento().signum() > 0) {
            agregarFilaTotal(caja, "Descuento", t.detalleDescuento(),
                    "- " + t.simboloMoneda() + " " + monto(t.descuento()), false);
        }
        agregarFilaTotal(caja, "IVA", null,
                t.simboloMoneda() + " " + monto(t.impuesto()), false);
        agregarFilaTotal(caja, "TOTAL", null,
                t.simboloMoneda() + " " + monto(t.total()), true);

        PdfPCell celdaCaja = new PdfPCell(caja);
        celdaCaja.setBorder(Rectangle.NO_BORDER);
        marco.addCell(celdaCaja);
        pdf.add(marco);
    }

    /**
     * Una fila del bloque de totales. {@code detalle} es la letra pequeña bajo
     * la etiqueta —el código del cupón, por ejemplo—; va DENTRO de la misma
     * celda para que no se despegue de su importe al partir la página.
     */
    private void agregarFilaTotal(PdfPTable caja, String etiqueta, String detalle,
                                  String valor, boolean destacado) {
        Font fe = destacado ? fuente(11.5f, true, BaseColor.WHITE) : fuente(10, true, TINTA);
        Font fv = destacado ? fuente(12.5f, true, BaseColor.WHITE) : fuente(10.5f, false, TINTA);
        BaseColor fondo = destacado ? ACENTO : BaseColor.WHITE;

        PdfPCell ce = new PdfPCell();
        ce.addElement(new Paragraph(etiqueta, fe));
        if (detalle != null && !detalle.isBlank()) {
            Paragraph d = new Paragraph(detalle, fuente(8, false,
                    destacado ? new BaseColor(219, 234, 254) : TINTA_SUAVE));
            d.setSpacingBefore(1);
            ce.addElement(d);
        }
        prepararCeldaTotal(ce, fondo, destacado);
        caja.addCell(ce);

        PdfPCell cv = new PdfPCell(new Phrase(valor, fv));
        prepararCeldaTotal(cv, fondo, destacado);
        cv.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cv.setVerticalAlignment(Element.ALIGN_MIDDLE);
        caja.addCell(cv);
    }

    private static void prepararCeldaTotal(PdfPCell c, BaseColor fondo, boolean destacado) {
        c.setBackgroundColor(fondo);
        c.setPadding(7);
        c.setBorder(destacado ? Rectangle.NO_BORDER : Rectangle.BOTTOM);
        c.setBorderColor(LINEA);
        c.setBorderWidthBottom(0.6f);
    }

    // ── Pie de página con numeración ─────────────────────────────────────

    /**
     * Pie repetido en todas las páginas. El total de páginas se reserva como
     * plantilla y se estampa al cerrar el documento, que es el primer momento
     * en que se conoce.
     */
    private static class Pie extends PdfPageEventHelper {
        private final DocumentoPdf doc;
        private PdfTemplate hueco;

        Pie(DocumentoPdf doc) { this.doc = doc; }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            hueco = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte lienzo = writer.getDirectContent();
            Rectangle pagina = document.getPageSize();
            float y = document.bottomMargin() - 22;

            lienzo.saveState();
            lienzo.setColorStroke(LINEA);
            lienzo.setLineWidth(0.7f);
            lienzo.moveTo(document.leftMargin(), y + 14);
            lienzo.lineTo(pagina.getWidth() - document.rightMargin(), y + 14);
            lienzo.stroke();
            lienzo.restoreState();

            Font f = fuente(7.5f, false, TINTA_SUAVE);
            ColumnText.showTextAligned(lienzo, Element.ALIGN_LEFT,
                    new Phrase(EMISOR_NOMBRE + "  ·  " + EMISOR_RUC, f),
                    document.leftMargin(), y, 0);
            ColumnText.showTextAligned(lienzo, Element.ALIGN_CENTER,
                    new Phrase(doc.getTitulo() + " N.º " + doc.getNumero(), f),
                    pagina.getWidth() / 2, y, 0);

            String parcial = "Página " + writer.getPageNumber() + " de ";
            float ancho = f.getBaseFont().getWidthPoint(parcial, f.getSize());
            float derecha = pagina.getWidth() - document.rightMargin();
            ColumnText.showTextAligned(lienzo, Element.ALIGN_LEFT, new Phrase(parcial, f),
                    derecha - ancho - 12, y, 0);
            lienzo.addTemplate(hueco, derecha - 12, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            Font f = fuente(7.5f, false, TINTA_SUAVE);
            hueco.beginText();
            hueco.setFontAndSize(f.getBaseFont(), f.getSize());
            hueco.setColorFill(TINTA_SUAVE);
            hueco.showText(String.valueOf(writer.getPageNumber()));
            hueco.endText();
        }
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private static Font fuente(float tam, boolean negrita, BaseColor color) {
        return FontFactory.getFont(negrita ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA,
                tam, color);
    }

    private static Paragraph pequena(String texto) {
        Paragraph p = new Paragraph(texto, fuente(8.5f, false, TINTA_SUAVE));
        p.setLeading(11.5f);
        return p;
    }

    /** Rótulo de sección: pequeño, en mayúsculas y con el color de acento. */
    private static Paragraph rotulo(String texto) {
        Paragraph p = new Paragraph(texto.toUpperCase(Locale.ROOT), fuente(7.5f, true, ACENTO));
        p.setSpacingAfter(3);
        return p;
    }

    private static Paragraph rotuloSuelto(String texto) {
        Paragraph p = rotulo(texto);
        p.setSpacingAfter(5);
        return p;
    }

    private static Paragraph centrado(String texto, Font f, float espacioDespues) {
        Paragraph p = new Paragraph(texto, f);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(espacioDespues);
        return p;
    }

    /**
     * Estado del documento como etiqueta de color. Va en una tabla de una celda
     * porque un {@code Paragraph} no tiene fondo propio en iText 5.
     */
    private static PdfPTable chipEstado(String estado) {
        String e = estado.toLowerCase(Locale.ROOT);
        BaseColor color = switch (e) {
            case "anulada", "anulado", "rechazada", "cancelada" -> ROJO;
            case "pagada", "pagado", "cerrada", "recibida", "entregada" -> VERDE;
            default -> new BaseColor(71, 85, 105);
        };
        PdfPTable chip = new PdfPTable(1);
        chip.setWidthPercentage(64);
        chip.setHorizontalAlignment(Element.ALIGN_CENTER);
        chip.setSpacingBefore(8);
        PdfPCell c = new PdfPCell(new Phrase(estado.toUpperCase(Locale.ROOT),
                fuente(8, true, BaseColor.WHITE)));
        c.setBackgroundColor(color);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(4);
        c.setPaddingBottom(5);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        chip.addCell(c);
        return chip;
    }

    /** Regla horizontal a todo el ancho, como separador de bloques. */
    private static PdfPTable regla(BaseColor color, float grosor, float espacioDespues) {
        PdfPTable r = new PdfPTable(1);
        r.setWidthPercentage(100);
        r.setSpacingAfter(espacioDespues);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(grosor);
        c.setBackgroundColor(color);
        c.setBorder(Rectangle.NO_BORDER);
        r.addCell(c);
        return r;
    }

    private static Paragraph notaFinal(String nota) {
        Paragraph p = new Paragraph(nota, fuente(8.5f, false, TINTA_SUAVE));
        p.setSpacingBefore(26);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(12f);
        return p;
    }

    private static PdfPCell celdaSinBorde() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    /**
     * Un código vacío se marca con una raya y no con la nada: una columna en
     * blanco parece un fallo de impresión, y quien lee la factura no puede
     * distinguir «este dato falta» de «este producto no tiene código».
     */
    private static String codigoODefecto(String codigo) {
        return codigo == null || codigo.isBlank() ? "—" : codigo;
    }

    private static PdfPCell celdaLinea(String texto, int alineacion, BaseColor fondo,
                                       boolean monoespaciada) {
        Font f = monoespaciada
                ? FontFactory.getFont(FontFactory.COURIER, 8.5f, TINTA)
                : fuente(9, false, TINTA);
        PdfPCell c = new PdfPCell(new Phrase(texto == null ? "" : texto, f));
        c.setPaddingTop(6);
        c.setPaddingBottom(6);
        c.setPaddingLeft(7);
        c.setPaddingRight(7);
        c.setBackgroundColor(fondo);
        c.setHorizontalAlignment(alineacion);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINEA);
        c.setBorderWidthBottom(0.6f);
        return c;
    }

    /** Logotipo del classpath; null si no está, y entonces no se pinta. */
    private static synchronized Image logo() {
        if (!logoBuscado) {
            logoBuscado = true;
            try (InputStream in = DocumentoPdfService.class.getResourceAsStream(RUTA_LOGO)) {
                logo = in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                logo = null;   // un membrete sin logotipo sigue siendo una factura
            }
        }
        if (logo == null) { return null; }
        try {
            return Image.getInstance(logo);
        } catch (Exception e) {
            return null;
        }
    }

    private static String monto(BigDecimal v) {
        return String.format(Locale.US, "%,.2f", v == null ? BigDecimal.ZERO : v);
    }
}
