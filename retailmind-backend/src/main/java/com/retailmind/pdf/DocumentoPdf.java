package com.retailmind.pdf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modelo GENÉRICO de documento comercial imprimible (factura de compra,
 * factura de venta, orden, nota). El ciclo de venta solo debe construir uno
 * de estos con sus datos y pasarlo a {@link DocumentoPdfService#generar}.
 *
 * Patrón de uso:
 * <pre>
 *   DocumentoPdf doc = new DocumentoPdf("FACTURA DE VENTA", "FV-00012", "2026-07-04")
 *       .contraparte(new Parte("CLIENTE", "Juan Pérez", "CI 1250...", "Av. ...", "juan@..."))
 *       .meta("Pedido", "PED-000034")
 *       .linea(new Linea("SKU-1", "Producto X", 2, precio, iva, subtotal))
 *       .totales(new Totales(sub, iva, total, "$"))
 *       .notaPie("Gracias por su compra");
 *   byte[] pdf = documentoPdfService.generar(doc);
 * </pre>
 */
public class DocumentoPdf {

    /** Contraparte del documento: proveedor (compra) o cliente (venta). */
    public record Parte(String etiqueta, String nombre, String identificacion,
                        String direccion, String contacto) {}

    /** Línea de detalle del documento. */
    public record Linea(String codigo, String descripcion, int cantidad,
                        BigDecimal precioUnitario, BigDecimal impuesto, BigDecimal subtotal) {}

    /** Bloque de totales (los montos vienen de la BD, nunca recalculados aquí). */
    public record Totales(BigDecimal subtotal, BigDecimal impuesto, BigDecimal total,
                          String simboloMoneda) {}

    private final String titulo;
    private final String numero;
    private final String fecha;
    private String estado;
    private Parte contraparte;
    private final Map<String, String> metadatos = new LinkedHashMap<>();
    private final List<Linea> lineas = new ArrayList<>();
    private Totales totales;
    private String notaPie;

    public DocumentoPdf(String titulo, String numero, String fecha) {
        this.titulo = titulo;
        this.numero = numero;
        this.fecha = fecha;
    }

    public DocumentoPdf estado(String estado)        { this.estado = estado; return this; }
    public DocumentoPdf contraparte(Parte parte)     { this.contraparte = parte; return this; }
    public DocumentoPdf meta(String clave, String v) { if (v != null) metadatos.put(clave, v); return this; }
    public DocumentoPdf linea(Linea linea)           { this.lineas.add(linea); return this; }
    public DocumentoPdf totales(Totales totales)     { this.totales = totales; return this; }
    public DocumentoPdf notaPie(String nota)         { this.notaPie = nota; return this; }

    public String getTitulo()               { return titulo; }
    public String getNumero()               { return numero; }
    public String getFecha()                { return fecha; }
    public String getEstado()               { return estado; }
    public Parte getContraparte()           { return contraparte; }
    public Map<String, String> getMetadatos() { return metadatos; }
    public List<Linea> getLineas()          { return lineas; }
    public Totales getTotales()             { return totales; }
    public String getNotaPie()              { return notaPie; }
}
