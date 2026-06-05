package com.retailmind.admin.reportes;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.draw.LineSeparator;

@Service
public class ReportesService {

    private static final Logger logger = LoggerFactory.getLogger(ReportesService.class);
    private final JdbcTemplate ch;

    // Colores corporativos
    private static final BaseColor PDF_DARK  = new BaseColor(31, 78, 121);   // #1F4E79
    private static final BaseColor PDF_MED   = new BaseColor(46, 117, 182);  // #2E75B6
    private static final BaseColor PDF_LIGHT = new BaseColor(240, 247, 255); // #F0F7FF

    private static final byte[] XLS_DARK  = {31, 78, 121};
    private static final byte[] XLS_MED   = {46, 117, (byte) 182};
    private static final byte[] XLS_LIGHT = {(byte) 240, (byte) 247, (byte) 255};

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public ReportesService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXCEL: DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    public byte[] generarExcelDashboard() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            crearPortada(wb, "Reporte Dashboard", new String[]{"KPIs", "Sesiones por Semana"});

            // Hoja KPIs
            Sheet sheetKpis = wb.createSheet("KPIs");
            Map<String, Object> kpis = getKpis();
            String[][] kpiData = {
                {"Total Sesiones", str(kpis.get("totalSesiones"))},
                {"Total Usuarios", str(kpis.get("totalUsuarios"))},
                {"Conversiones", str(kpis.get("conversiones"))},
                {"Tasa Conversión %", str(kpis.get("tasaConversion"))},
                {"Abandonos", str(kpis.get("abandonos"))},
                {"Total Eventos", str(kpis.get("totalEventos"))},
                {"Semanas Cargadas", str(kpis.get("semanasCargadas"))},
                {"Prom. Eventos/Sesión", str(kpis.get("promedioEventosSesion"))}
            };
            escribirTabla(wb, sheetKpis, new String[]{"Indicador", "Valor"}, kpiData, null);

            // Hoja Sesiones por Semana
            Sheet sheetSem = wb.createSheet("Sesiones por Semana");
            List<Map<String, Object>> semanas = getSesionesPorSemana();
            String[] colsSem = {"semana", "total_sesiones", "conversiones", "abandonos", "tasa_conversion"};
            String[] hdrsSem = {"Semana", "Total Sesiones", "Conversiones", "Abandonos", "Tasa Conv. %"};
            int[] numColsSem = {1, 2, 3};
            escribirTablaDesdeMap(wb, sheetSem, hdrsSem, colsSem, semanas, numColsSem);

            return toBytes(wb);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXCEL: PEDIDOS
    // ═══════════════════════════════════════════════════════════════════════════

    public byte[] generarExcelPedidos() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            crearPortada(wb, "Reporte de Pedidos", new String[]{"Pedidos", "Resumen por Usuario"});

            Sheet sheet1 = wb.createSheet("Pedidos");
            List<Map<String, Object>> pedidos = getPedidos();
            String[] cols1 = {"orden_id", "user_id", "fecha_orden", "total", "estado", "num_items"};
            String[] hdrs1 = {"Orden ID", "Username", "Fecha", "Total $", "Estado", "Items"};
            int[] numCols1 = {3, 5};
            escribirTablaDesdeMap(wb, sheet1, hdrs1, cols1, pedidos, numCols1);

            Sheet sheet2 = wb.createSheet("Resumen por Usuario");
            List<Map<String, Object>> resumen = getResumenPedidosPorUsuario();
            String[] cols2 = {"user_id", "total_ordenes", "total_gastado", "promedio_orden"};
            String[] hdrs2 = {"Username", "Total Órdenes", "Total Gastado $", "Promedio Orden $"};
            int[] numCols2 = {1, 2, 3};
            escribirTablaDesdeMap(wb, sheet2, hdrs2, cols2, resumen, numCols2);

            return toBytes(wb);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXCEL: FUNNEL
    // ═══════════════════════════════════════════════════════════════════════════

    public byte[] generarExcelFunnel() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            crearPortada(wb, "Reporte Funnel de Conversión", new String[]{"Funnel", "Sesiones Detalle"});

            Sheet sheet1 = wb.createSheet("Funnel");
            List<Map<String, Object>> funnel = getFunnelResumen();
            String[] cols1 = {"etapa", "total_sesiones", "porcentaje"};
            String[] hdrs1 = {"Etapa", "Total Sesiones", "Porcentaje %"};
            int[] numCols1 = {1, 2};
            escribirTablaDesdeMap(wb, sheet1, hdrs1, cols1, funnel, numCols1);

            Sheet sheet2 = wb.createSheet("Sesiones Detalle");
            List<Map<String, Object>> detalle = getSesionesDetalle();
            String[] cols2 = {"session_id", "user_id", "channel", "semana",
                    "hizo_view", "hizo_click", "hizo_carrito", "hizo_compra", "hizo_abandono"};
            String[] hdrs2 = {"Session ID", "User ID", "Canal", "Semana",
                    "View", "Click", "Carrito", "Compra", "Abandono"};
            int[] numCols2 = {3, 4, 5, 6, 7, 8};
            escribirTablaDesdeMap(wb, sheet2, hdrs2, cols2, detalle, numCols2);

            return toBytes(wb);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXCEL: REGION
    // ═══════════════════════════════════════════════════════════════════════════

    public byte[] generarExcelRegion() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            crearPortada(wb, "Reporte por Región", new String[]{"Regiones"});

            Sheet sheet = wb.createSheet("Regiones");
            List<Map<String, Object>> data = getRegionResumen();
            String[] cols = {"region_nombre", "sesiones", "usuarios", "conversiones",
                    "tasa_conversion", "revenue_total", "precio_promedio"};
            String[] hdrs = {"Región", "Sesiones", "Usuarios", "Conversiones",
                    "Tasa Conv. %", "Revenue Total $", "Precio Promedio $"};
            int[] numCols = {1, 2, 3, 4, 5, 6};
            escribirTablaDesdeMap(wb, sheet, hdrs, cols, data, numCols);

            return toBytes(wb);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PDF: REPORTE EJECUTIVO
    // ═══════════════════════════════════════════════════════════════════════════

    public byte[] generarPdfDashboard() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 50, 50);
        PdfWriter writer = PdfWriter.getInstance(doc, baos);
        String fechaGen = LocalDateTime.now().format(DTF);
        writer.setPageEvent(new FooterEvent(fechaGen));
        doc.open();

        Map<String, Object> kpis = getKpis();
        List<Map<String, Object>> funnel = getFunnelResumen();
        List<Map<String, Object>> regiones = getRegionResumen();
        List<Map<String, Object>> trafico = getTraficoResumen();

        long totalEventos = toLong(kpis.get("totalEventos"));
        long semanasCargadas = toLong(kpis.get("semanasCargadas"));

        // ═══ PÁGINA 1: PORTADA ═══
        pdfPortada(doc, writer, fechaGen, totalEventos, semanasCargadas);

        // ═══ PÁGINA 2: ÍNDICE ═══
        doc.newPage();
        pdfIndice(doc);

        // ═══ PÁGINA 3: RESUMEN EJECUTIVO ═══
        doc.newPage();
        pdfResumenEjecutivo(doc, kpis, totalEventos, semanasCargadas);

        // ═══ PÁGINA 4: KPIs ═══
        doc.newPage();
        pdfKpis(doc, kpis);

        // ═══ PÁGINA 5: FUNNEL ═══
        doc.newPage();
        pdfFunnel(doc, funnel, toLong(kpis.get("totalSesiones")));

        // ═══ PÁGINA 6: REGIONES ═══
        doc.newPage();
        pdfRegiones(doc, regiones);

        // ═══ PÁGINA 7: CANALES ═══
        doc.newPage();
        pdfCanales(doc, trafico);

        doc.close();
        return baos.toByteArray();
    }

    // ── PDF: Portada ──────────────────────────────────────────────────────────

    private void pdfPortada(Document doc, PdfWriter writer, String fecha,
                            long totalEventos, long semanas) throws Exception {
        PdfContentByte cb = writer.getDirectContentUnder();
        float pageW = doc.getPageSize().getWidth();
        float pageH = doc.getPageSize().getHeight();

        // Rectángulo superior oscuro (40% de la página)
        cb.setColorFill(PDF_DARK);
        cb.rectangle(0, pageH * 0.6f, pageW, pageH * 0.4f);
        cb.fill();

        // Rectángulo inferior medio (10%)
        cb.setColorFill(PDF_MED);
        cb.rectangle(0, 0, pageW, pageH * 0.08f);
        cb.fill();

        // Textos sobre el rectángulo oscuro
        doc.add(spacer(120));
        doc.add(pCentered("RETAILMIND SHOP", pdfFont(28, true, BaseColor.WHITE)));
        doc.add(spacer(10));
        doc.add(pCentered("REPORTE EJECUTIVO", pdfFont(18, false, BaseColor.WHITE)));
        doc.add(spacer(30));
        doc.add(new LineSeparator(2f, 60, PDF_MED, Element.ALIGN_CENTER, 0));
        doc.add(spacer(50));

        // Recuadro con info
        PdfPTable infoBox = new PdfPTable(1);
        infoBox.setWidthPercentage(65);
        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorderColor(PDF_MED);
        infoCell.setBorderWidth(1.5f);
        infoCell.setPadding(16);
        infoCell.setBackgroundColor(new BaseColor(255, 255, 255));
        infoCell.addElement(new Paragraph("Fecha de generación: " + fecha, pdfFont(11, false, BaseColor.DARK_GRAY)));
        infoCell.addElement(new Paragraph("Período analizado: Semana 1 - Semana " + semanas, pdfFont(11, false, BaseColor.DARK_GRAY)));
        infoCell.addElement(new Paragraph("Total registros analizados: " + String.format("%,d", totalEventos), pdfFont(11, false, BaseColor.DARK_GRAY)));
        infoCell.addElement(new Paragraph("Generado por: Sistema RetailMind Analytics", pdfFont(11, false, BaseColor.DARK_GRAY)));
        infoBox.addCell(infoCell);
        doc.add(infoBox);
    }

    // ── PDF: Índice ───────────────────────────────────────────────────────────

    private void pdfIndice(Document doc) throws Exception {
        doc.add(pdfSeccionHeader("CONTENIDO DEL REPORTE"));
        doc.add(spacer(20));
        doc.add(new LineSeparator(1f, 100, PDF_MED, Element.ALIGN_CENTER, 0));
        doc.add(spacer(20));

        String[] secciones = {
            "1. Resumen Ejecutivo ......................... pág 3",
            "2. Indicadores Clave (KPIs) .................. pág 4",
            "3. Análisis del Funnel ....................... pág 5",
            "4. Análisis por Región ....................... pág 6",
            "5. Análisis por Canal ........................ pág 7"
        };
        for (String s : secciones) {
            Paragraph p = new Paragraph(s, pdfFont(12, false, BaseColor.DARK_GRAY));
            p.setSpacingAfter(12);
            doc.add(p);
        }
    }

    // ── PDF: Resumen Ejecutivo ─────────────────────────────────────────────────

    private void pdfResumenEjecutivo(Document doc, Map<String, Object> kpis,
                                      long totalEventos, long semanas) throws Exception {
        doc.add(pdfSeccionHeader("1. RESUMEN EJECUTIVO"));
        doc.add(spacer(14));

        String tasaConv = str(kpis.get("tasaConversion"));
        long abandonos = toLong(kpis.get("abandonos"));
        long totalSes = toLong(kpis.get("totalSesiones"));
        double tasaAband = totalSes > 0 ? Math.round(abandonos * 100.0 / totalSes * 100.0) / 100.0 : 0;

        String intro = String.format(
            "RetailMind Shop ha procesado %s eventos de comportamiento de usuario " +
            "distribuidos en %d semanas de análisis. El sistema registra una tasa de " +
            "conversión del %s%% y una tasa de abandono del %.2f%%.",
            String.format("%,d", totalEventos), semanas, tasaConv, tasaAband);
        Paragraph pIntro = new Paragraph(intro, pdfFont(11, false, BaseColor.DARK_GRAY));
        pIntro.setSpacingAfter(20);
        pIntro.setLeading(18);
        doc.add(pIntro);

        // 4 métricas destacadas
        PdfPTable metrics = new PdfPTable(4);
        metrics.setWidthPercentage(100);
        metrics.setSpacingAfter(20);
        addMetricBox(metrics, String.format("%,d", totalSes), "Total Sesiones");
        addMetricBox(metrics, tasaConv + "%", "Tasa Conversión");
        addMetricBox(metrics, "$" + String.format("%,.0f", toDouble(kpis.get("revenueTotal"))), "Revenue Total");
        addMetricBox(metrics, String.format("%,d", toLong(kpis.get("totalUsuarios"))), "Usuarios Únicos");
        doc.add(metrics);
    }

    // ── PDF: KPIs ─────────────────────────────────────────────────────────────

    private void pdfKpis(Document doc, Map<String, Object> kpis) throws Exception {
        doc.add(pdfSeccionHeader("2. INDICADORES CLAVE (KPIs)"));
        doc.add(spacer(14));

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{35, 25, 40});
        addPdfTableHeader(table, "Indicador", "Valor", "Descripción");

        String[][] rows = {
            {"Total Sesiones", str(kpis.get("totalSesiones")), "Sesiones únicas registradas"},
            {"Total Usuarios", str(kpis.get("totalUsuarios")), "Usuarios únicos activos"},
            {"Conversiones", str(kpis.get("conversiones")), "Sesiones con al menos una compra"},
            {"Tasa Conversión", str(kpis.get("tasaConversion")) + "%", "Porcentaje de sesiones convertidas"},
            {"Abandonos", str(kpis.get("abandonos")), "Sesiones con abandono de carrito"},
            {"Total Eventos", str(kpis.get("totalEventos")), "Todos los eventos registrados"},
            {"Semanas Cargadas", str(kpis.get("semanasCargadas")), "Semanas con datos en el sistema"},
            {"Prom. Eventos/Sesión", str(kpis.get("promedioEventosSesion")), "Media de eventos por sesión"}
        };
        for (int i = 0; i < rows.length; i++) {
            BaseColor bg = (i % 2 == 0) ? PDF_LIGHT : BaseColor.WHITE;
            for (String val : rows[i]) {
                PdfPCell c = new PdfPCell(new Phrase(val, pdfFont(10, false, BaseColor.DARK_GRAY)));
                c.setBackgroundColor(bg);
                c.setPadding(7);
                c.setBorderColor(BaseColor.LIGHT_GRAY);
                table.addCell(c);
            }
        }
        doc.add(table);
    }

    // ── PDF: Funnel ───────────────────────────────────────────────────────────

    private void pdfFunnel(Document doc, List<Map<String, Object>> funnel, long totalSes) throws Exception {
        doc.add(pdfSeccionHeader("3. ANÁLISIS DEL FUNNEL"));
        doc.add(spacer(14));

        // Barras de texto
        for (Map<String, Object> row : funnel) {
            String etapa = str(row.get("etapa"));
            long sesiones = toLong(row.get("total_sesiones"));
            double pct = toDouble(row.get("porcentaje"));
            int barLen = (int) Math.round(pct / 5);
            String bar = "█".repeat(Math.max(barLen, 1)) + "░".repeat(Math.max(20 - barLen, 0));
            String line = String.format("%-10s %s %,d (%.1f%%)", etapa, bar, sesiones, pct);
            Paragraph p = new Paragraph(line, pdfFont(10, false, BaseColor.DARK_GRAY));
            p.setSpacingAfter(6);
            doc.add(p);
        }

        doc.add(spacer(16));

        // Tabla
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(80);
        addPdfTableHeader(table, "Etapa", "Sesiones", "Porcentaje");
        for (int i = 0; i < funnel.size(); i++) {
            Map<String, Object> row = funnel.get(i);
            BaseColor bg = (i % 2 == 0) ? PDF_LIGHT : BaseColor.WHITE;
            addPdfDataCell(table, str(row.get("etapa")), bg);
            addPdfDataCell(table, String.format("%,d", toLong(row.get("total_sesiones"))), bg);
            addPdfDataCell(table, str(row.get("porcentaje")) + "%", bg);
        }
        doc.add(table);
    }

    // ── PDF: Regiones ─────────────────────────────────────────────────────────

    private void pdfRegiones(Document doc, List<Map<String, Object>> regiones) throws Exception {
        doc.add(pdfSeccionHeader("4. ANÁLISIS POR REGIÓN"));
        doc.add(spacer(14));

        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{20, 15, 15, 15, 15, 20});
        addPdfTableHeader(table, "Región", "Sesiones", "Usuarios", "Conv.", "Tasa %", "Revenue $");
        for (int i = 0; i < regiones.size(); i++) {
            Map<String, Object> r = regiones.get(i);
            BaseColor bg = (i % 2 == 0) ? PDF_LIGHT : BaseColor.WHITE;
            addPdfDataCell(table, str(r.get("region_nombre")), bg);
            addPdfDataCell(table, String.format("%,d", toLong(r.get("sesiones"))), bg);
            addPdfDataCell(table, String.format("%,d", toLong(r.get("usuarios"))), bg);
            addPdfDataCell(table, String.format("%,d", toLong(r.get("conversiones"))), bg);
            addPdfDataCell(table, str(r.get("tasa_conversion")) + "%", bg);
            addPdfDataCell(table, String.format("$%,.0f", toDouble(r.get("revenue_total"))), bg);
        }
        doc.add(table);
    }

    // ── PDF: Canales ──────────────────────────────────────────────────────────

    private void pdfCanales(Document doc, List<Map<String, Object>> trafico) throws Exception {
        doc.add(pdfSeccionHeader("5. ANÁLISIS POR CANAL"));
        doc.add(spacer(14));

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{20, 20, 20, 20, 20});
        addPdfTableHeader(table, "Canal", "Sesiones", "Conversiones", "Tasa Conv. %", "Tiempo Prom. (s)");
        for (int i = 0; i < trafico.size(); i++) {
            Map<String, Object> r = trafico.get(i);
            BaseColor bg = (i % 2 == 0) ? PDF_LIGHT : BaseColor.WHITE;
            addPdfDataCell(table, str(r.get("fuente")), bg);
            addPdfDataCell(table, String.format("%,d", toLong(r.get("totalSesiones"))), bg);
            addPdfDataCell(table, String.format("%,d", toLong(r.get("totalConversiones"))), bg);
            addPdfDataCell(table, str(r.get("tasaConversion")) + "%", bg);
            addPdfDataCell(table, str(r.get("tiempoPromedio")), bg);
        }
        doc.add(table);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES CLICKHOUSE
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getKpis() {
        Map<String, Object> kpis = new LinkedHashMap<>();
        try {
            Map<String, Object> row = ch.queryForMap(
                "SELECT uniqExact(session_id) AS totalSesiones, " +
                "uniqExact(user_id) AS totalUsuarios, " +
                "sum(is_conversion) AS conversiones, " +
                "round(sum(is_conversion)/uniqExact(session_id)*100, 2) AS tasaConversion, " +
                "sum(drop_off_flag) AS abandonos, " +
                "count() AS totalEventos, " +
                "uniqExact(semana) AS semanasCargadas, " +
                "round(count()/uniqExact(session_id), 1) AS promedioEventosSesion, " +
                "round(sum(price * is_conversion), 2) AS revenueTotal " +
                "FROM retailmind.fact_eventos");
            kpis.putAll(row);
        } catch (Exception e) { logger.error("Error getKpis: {}", e.getMessage()); }
        return kpis;
    }

    private List<Map<String, Object>> getSesionesPorSemana() {
        try {
            return ch.query(
                "SELECT semana, uniqExact(session_id) AS total_sesiones, " +
                "sum(is_conversion) AS conversiones, sum(drop_off_flag) AS abandonos, " +
                "round(sum(is_conversion)/uniqExact(session_id)*100, 2) AS tasa_conversion " +
                "FROM retailmind.fact_eventos GROUP BY semana ORDER BY semana",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("semana", rs.getInt("semana"));
                    m.put("total_sesiones", rs.getLong("total_sesiones"));
                    m.put("conversiones", rs.getLong("conversiones"));
                    m.put("abandonos", rs.getLong("abandonos"));
                    m.put("tasa_conversion", rs.getDouble("tasa_conversion"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getPedidos() {
        try {
            return ch.query(
                "SELECT o.orden_id, o.user_id, o.fecha_orden, o.total, o.estado, " +
                "count() AS num_items " +
                "FROM retailmind.ordenes o " +
                "LEFT JOIN retailmind.orden_items oi ON o.orden_id = oi.orden_id " +
                "GROUP BY o.orden_id, o.user_id, o.fecha_orden, o.total, o.estado " +
                "ORDER BY o.fecha_orden DESC",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orden_id", rs.getString("orden_id"));
                    m.put("user_id", rs.getString("user_id"));
                    m.put("fecha_orden", rs.getString("fecha_orden"));
                    m.put("total", rs.getFloat("total"));
                    m.put("estado", rs.getString("estado"));
                    m.put("num_items", rs.getLong("num_items"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getResumenPedidosPorUsuario() {
        try {
            return ch.query(
                "SELECT user_id, count() AS total_ordenes, " +
                "round(sum(total), 2) AS total_gastado, " +
                "round(avg(total), 2) AS promedio_orden " +
                "FROM retailmind.ordenes GROUP BY user_id ORDER BY total_gastado DESC",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("user_id", rs.getString("user_id"));
                    m.put("total_ordenes", rs.getLong("total_ordenes"));
                    m.put("total_gastado", rs.getDouble("total_gastado"));
                    m.put("promedio_orden", rs.getDouble("promedio_orden"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getFunnelResumen() {
        try {
            Long totalSesiones = ch.queryForObject(
                "SELECT uniqExact(session_id) FROM retailmind.fact_eventos", Long.class);
            long total = totalSesiones != null ? totalSesiones : 1;
            String[] actions = {"view", "click", "add_to_cart", "purchase", "drop"};
            String[] labels = {"Vista", "Click", "Carrito", "Compra", "Abandono"};
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (int i = 0; i < actions.length; i++) {
                Long count = ch.queryForObject(
                    "SELECT uniqExact(session_id) FROM retailmind.fact_eventos WHERE user_action = '" + actions[i] + "'", Long.class);
                long c = count != null ? count : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("etapa", labels[i]);
                m.put("total_sesiones", c);
                m.put("porcentaje", Math.round(c * 100.0 / total * 100.0) / 100.0);
                result.add(m);
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getSesionesDetalle() {
        try {
            return ch.query(
                "SELECT session_id, user_id, channel, semana, " +
                "maxIf(1, user_action='view') AS hizo_view, " +
                "maxIf(1, user_action='click') AS hizo_click, " +
                "maxIf(1, user_action='add_to_cart') AS hizo_carrito, " +
                "maxIf(1, user_action='purchase') AS hizo_compra, " +
                "maxIf(1, user_action='drop') AS hizo_abandono " +
                "FROM retailmind.fact_eventos GROUP BY session_id, user_id, channel, semana " +
                "ORDER BY session_id LIMIT 10000",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("session_id", rs.getString("session_id"));
                    m.put("user_id", rs.getString("user_id"));
                    m.put("channel", rs.getString("channel"));
                    m.put("semana", rs.getInt("semana"));
                    m.put("hizo_view", rs.getInt("hizo_view"));
                    m.put("hizo_click", rs.getInt("hizo_click"));
                    m.put("hizo_carrito", rs.getInt("hizo_carrito"));
                    m.put("hizo_compra", rs.getInt("hizo_compra"));
                    m.put("hizo_abandono", rs.getInt("hizo_abandono"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getRegionResumen() {
        try {
            return ch.query(
                "SELECT r.region_nombre, uniqExact(fe.session_id) AS sesiones, " +
                "uniqExact(fe.user_id) AS usuarios, sum(fe.is_conversion) AS conversiones, " +
                "round(sum(fe.is_conversion)/uniqExact(fe.session_id)*100, 2) AS tasa_conversion, " +
                "round(sum(fe.price * fe.is_conversion), 2) AS revenue_total, " +
                "round(avg(fe.price), 2) AS precio_promedio " +
                "FROM retailmind.fact_eventos fe " +
                "JOIN retailmind.dim_usuario du ON fe.user_id = du.user_id " +
                "JOIN retailmind.dim_region r ON du.region_id = r.region_id " +
                "GROUP BY r.region_nombre ORDER BY sesiones DESC",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("region_nombre", rs.getString("region_nombre"));
                    m.put("sesiones", rs.getLong("sesiones"));
                    m.put("usuarios", rs.getLong("usuarios"));
                    m.put("conversiones", rs.getLong("conversiones"));
                    m.put("tasa_conversion", rs.getDouble("tasa_conversion"));
                    m.put("revenue_total", rs.getDouble("revenue_total"));
                    m.put("precio_promedio", rs.getDouble("precio_promedio"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> getTraficoResumen() {
        try {
            return ch.query(
                "SELECT channel AS fuente, uniqExact(session_id) AS totalSesiones, " +
                "uniqExact(user_id) AS totalUsuarios, " +
                "sum(is_conversion) AS totalConversiones, " +
                "round(sum(is_conversion)/uniqExact(session_id)*100, 2) AS tasaConversion, " +
                "round(avg(time_spent_sec), 2) AS tiempoPromedio " +
                "FROM retailmind.fact_eventos GROUP BY channel ORDER BY totalSesiones DESC",
                (rs, rn) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fuente", rs.getString("fuente"));
                    m.put("totalSesiones", rs.getLong("totalSesiones"));
                    m.put("totalUsuarios", rs.getLong("totalUsuarios"));
                    m.put("totalConversiones", rs.getLong("totalConversiones"));
                    m.put("tasaConversion", rs.getDouble("tasaConversion"));
                    m.put("tiempoPromedio", rs.getDouble("tiempoPromedio"));
                    return m;
                });
        } catch (Exception e) { return List.of(); }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILIDADES EXCEL
    // ═══════════════════════════════════════════════════════════════════════════

    private void crearPortada(XSSFWorkbook wb, String titulo, String[] hojas) {
        Sheet sheet = wb.createSheet("Portada");

        XSSFCellStyle darkStyle = wb.createCellStyle();
        darkStyle.setFillForegroundColor(new XSSFColor(XLS_DARK, null));
        darkStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font darkFont = wb.createFont();
        darkFont.setColor(IndexedColors.WHITE.getIndex());
        darkFont.setBold(true);
        darkFont.setFontHeightInPoints((short) 20);
        darkStyle.setFont(darkFont);

        XSSFCellStyle medStyle = wb.createCellStyle();
        medStyle.setFillForegroundColor(new XSSFColor(XLS_MED, null));
        medStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font medFont = wb.createFont();
        medFont.setColor(IndexedColors.WHITE.getIndex());
        medFont.setBold(true);
        medFont.setFontHeightInPoints((short) 14);
        medStyle.setFont(medFont);

        CellStyle infoStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font infoFont = wb.createFont();
        infoFont.setFontHeightInPoints((short) 11);
        infoStyle.setFont(infoFont);

        // Row 0: RETAILMIND SHOP
        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(40);
        Cell c0 = r0.createCell(0); c0.setCellValue("RETAILMIND SHOP"); c0.setCellStyle(darkStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        // Row 1: titulo reporte
        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(30);
        Cell c1 = r1.createCell(0); c1.setCellValue(titulo); c1.setCellStyle(medStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));

        // Rows 2-5: info
        String fecha = LocalDateTime.now().format(DTF);
        String[] info = {"Generado el: " + fecha, "Sistema: RetailMind Analytics",
                         "Base de datos: ClickHouse 26.4", ""};
        for (int i = 0; i < info.length; i++) {
            Row r = sheet.createRow(i + 2);
            Cell c = r.createCell(0); c.setCellValue(info[i]); c.setCellStyle(infoStyle);
        }

        // Row 6: Contenido
        Row r6 = sheet.createRow(6);
        Cell c6 = r6.createCell(0); c6.setCellValue("Contenido:");
        org.apache.poi.ss.usermodel.Font boldFont = wb.createFont();
        boldFont.setBold(true); boldFont.setFontHeightInPoints((short) 11);
        CellStyle boldStyle = wb.createCellStyle(); boldStyle.setFont(boldFont);
        c6.setCellStyle(boldStyle);

        for (int i = 0; i < hojas.length; i++) {
            Row r = sheet.createRow(7 + i);
            r.createCell(0).setCellValue("  • " + hojas[i]);
        }

        for (int i = 0; i <= 5; i++) sheet.setColumnWidth(i, 4000);
    }

    private void escribirTabla(XSSFWorkbook wb, Sheet sheet, String[] headers,
                               String[][] data, int[] numericCols) {
        XSSFCellStyle headerStyle = crearHeaderStyle(wb);
        XSSFCellStyle evenStyle = crearEvenRowStyle(wb);
        CellStyle oddStyle = wb.createCellStyle();
        addBorders(oddStyle);
        addBorders(evenStyle);

        // Header
        Row hr = sheet.createRow(0);
        hr.setHeightInPoints(25);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = hr.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);

        // Data
        for (int r = 0; r < data.length; r++) {
            Row row = sheet.createRow(r + 1);
            row.setHeightInPoints(18);
            CellStyle style = (r % 2 == 0) ? evenStyle : oddStyle;
            for (int c = 0; c < data[r].length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(data[r][c]);
                cell.setCellStyle(style);
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) < 3500) sheet.setColumnWidth(i, 3500);
        }
    }

    private void escribirTablaDesdeMap(XSSFWorkbook wb, Sheet sheet, String[] headers,
                                       String[] keys, List<Map<String, Object>> data, int[] numericCols) {
        XSSFCellStyle headerStyle = crearHeaderStyle(wb);
        XSSFCellStyle evenStyle = crearEvenRowStyle(wb);
        XSSFCellStyle oddStyle = (XSSFCellStyle) wb.createCellStyle();
        addBorders(oddStyle);
        addBorders(evenStyle);

        XSSFCellStyle totalStyle = wb.createCellStyle();
        totalStyle.setFillForegroundColor(new XSSFColor(XLS_MED, null));
        totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font totalFont = wb.createFont();
        totalFont.setColor(IndexedColors.WHITE.getIndex());
        totalFont.setBold(true);
        totalStyle.setFont(totalFont);
        addBorders(totalStyle);

        // Header row
        Row hr = sheet.createRow(0);
        hr.setHeightInPoints(25);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = hr.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);

        // Data rows
        double[] totals = new double[keys.length];
        for (int r = 0; r < data.size(); r++) {
            Row row = sheet.createRow(r + 1);
            row.setHeightInPoints(18);
            CellStyle style = (r % 2 == 0) ? evenStyle : oddStyle;
            Map<String, Object> map = data.get(r);
            for (int c = 0; c < keys.length; c++) {
                Cell cell = row.createCell(c);
                Object val = map.get(keys[c]);
                String sv = val != null ? val.toString() : "";
                if (val instanceof Number) {
                    cell.setCellValue(((Number) val).doubleValue());
                    totals[c] += ((Number) val).doubleValue();
                } else {
                    cell.setCellValue(sv);
                }
                cell.setCellStyle(style);
            }
        }

        // Total row
        if (data.size() > 0 && numericCols != null && numericCols.length > 0) {
            Row totalRow = sheet.createRow(data.size() + 1);
            totalRow.setHeightInPoints(22);
            Cell firstCell = totalRow.createCell(0);
            firstCell.setCellValue("TOTAL");
            firstCell.setCellStyle(totalStyle);
            for (int c = 1; c < keys.length; c++) {
                Cell cell = totalRow.createCell(c);
                boolean isNum = false;
                for (int nc : numericCols) if (nc == c) { isNum = true; break; }
                if (isNum) cell.setCellValue(Math.round(totals[c] * 100.0) / 100.0);
                cell.setCellStyle(totalStyle);
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) < 3500) sheet.setColumnWidth(i, 3500);
        }
    }

    private XSSFCellStyle crearHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(XLS_DARK, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font font = wb.createFont();
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        addBorders(style);
        return style;
    }

    private XSSFCellStyle crearEvenRowStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(XLS_LIGHT, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        addBorders(style);
        return style;
    }

    private void addBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private byte[] toBytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        return baos.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILIDADES PDF
    // ═══════════════════════════════════════════════════════════════════════════

    private com.itextpdf.text.Font pdfFont(float size, boolean bold, BaseColor color) {
        int style = bold ? com.itextpdf.text.Font.BOLD : com.itextpdf.text.Font.NORMAL;
        return new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, size, style, color);
    }

    private Paragraph pCentered(String text, com.itextpdf.text.Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private Paragraph spacer(float height) {
        Paragraph p = new Paragraph(new Chunk(" "));
        p.setSpacingBefore(height);
        return p;
    }

    private Paragraph pdfSeccionHeader(String titulo) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell(new Phrase(titulo, pdfFont(14, true, BaseColor.WHITE)));
        cell.setBackgroundColor(PDF_DARK);
        cell.setPadding(10);
        cell.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell);
        Paragraph p = new Paragraph();
        try { p.add(table); } catch (Exception ignored) {}
        p.setSpacingAfter(8);
        return p;
    }

    private void addPdfTableHeader(PdfPTable table, String... cols) {
        for (String col : cols) {
            PdfPCell cell = new PdfPCell(new Phrase(col, pdfFont(10, true, BaseColor.WHITE)));
            cell.setBackgroundColor(PDF_DARK);
            cell.setPadding(7);
            cell.setBorderColor(BaseColor.LIGHT_GRAY);
            table.addCell(cell);
        }
    }

    private void addPdfDataCell(PdfPTable table, String value, BaseColor bg) {
        PdfPCell cell = new PdfPCell(new Phrase(value, pdfFont(10, false, BaseColor.DARK_GRAY)));
        cell.setBackgroundColor(bg);
        cell.setPadding(6);
        cell.setBorderColor(BaseColor.LIGHT_GRAY);
        table.addCell(cell);
    }

    private void addMetricBox(PdfPTable table, String valor, String label) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(PDF_LIGHT);
        cell.setBorderColor(PDF_MED);
        cell.setBorderWidth(1f);
        cell.setPadding(12);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.addElement(pCentered(valor, pdfFont(18, true, PDF_DARK)));
        cell.addElement(pCentered(label, pdfFont(9, false, BaseColor.GRAY)));
        table.addCell(cell);
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private static class FooterEvent extends PdfPageEventHelper {
        private final String fecha;
        FooterEvent(String fecha) { this.fecha = fecha; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            if (writer.getPageNumber() == 1) return; // No footer en portada

            PdfContentByte cb = writer.getDirectContent();
            float bottom = document.bottom() - 25;
            float left = document.left();
            float right = document.right();

            // Línea
            cb.setColorStroke(new BaseColor(46, 117, 182));
            cb.setLineWidth(0.5f);
            cb.moveTo(left, bottom + 12);
            cb.lineTo(right, bottom + 12);
            cb.stroke();

            com.itextpdf.text.Font footerFont = new com.itextpdf.text.Font(
                com.itextpdf.text.Font.FontFamily.HELVETICA, 8,
                com.itextpdf.text.Font.ITALIC, BaseColor.GRAY);

            // Izquierda
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                new Phrase("RetailMind Shop — Reporte Ejecutivo", footerFont),
                left, bottom, 0);
            // Centro
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                new Phrase("Página " + writer.getPageNumber(), footerFont),
                (left + right) / 2, bottom, 0);
            // Derecha
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                new Phrase(fecha, footerFont),
                right, bottom, 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVERSIÓN
    // ═══════════════════════════════════════════════════════════════════════════

    private String str(Object o) { return o != null ? o.toString() : "0"; }
    private long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
    }
    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }
}
