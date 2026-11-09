package com.api.automation.report;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.api.automation.negative.ExpectedStatus;
import com.api.automation.payloads.ResolvedPayload;

/**
 * ONE workbook with every positive and negative test of a project, refreshed at the start of each run:
 *   src/test/resources/API_TestData/<PROJECT>/generated/<PROJECT>_Positive_Negative_TestData.xlsx
 * Sheets: All tests | Positive | Negative - with the data sent, the expected HTTP code and the VAPT reference.
 */
public final class TestDataCatalog {

    public static final class Entry {
        public String tcId, type, module, api, method, endpoint, check, category, change, body, expectedHttp, expected, vapt;
    }

    private TestDataCatalog() {}

    public static Entry positive(Map<String, String> row, ResolvedPayload p, String tcId) {
        Entry e = base(row, tcId);
        e.type = "Positive";
        e.check = "Valid request";
        e.category = "POSITIVE";
        e.change = "valid data (customer ids found live)";
        e.body = p == null ? "" : p.getBody();
        String st = row.getOrDefault("ExpectedStatus", "").trim();
        ExpectedStatus.Expect x = st.matches("\\d{3}") ? null : ExpectedStatus.of("POSITIVE");
        e.expectedHttp = st.matches("\\d{3}") ? st + " " + ExpectedStatus.name(Integer.parseInt(st)) : x != null ? x.text() : "200 OK";
        e.expected = row.getOrDefault("ExpectedResult", "").isBlank() ? "Success response with valid data" : row.get("ExpectedResult");
        e.vapt = "";
        return e;
    }

    public static Entry negative(Map<String, String> row, ResolvedPayload p, String tcId) {
        Entry e = base(row, tcId);
        e.type = "Negative";
        e.check = p.getNegTitle();
        e.category = p.getNegCategory();
        e.change = p.getNegChange();
        e.body = p.getBody();
        if (p.getMethodOverride() != null) e.method = p.getMethodOverride();
        if (p.getEndpointOverride() != null) e.endpoint = p.getEndpointOverride();
        ExpectedStatus.Expect x = ExpectedStatus.of(p.getNegCategory());
        e.expectedHttp = x == null ? "200 OK (same as positive)" : x.text();
        e.expected = p.getNegExpected();
        e.vapt = x == null ? "" : x.owasp;
        return e;
    }

    private static Entry base(Map<String, String> row, String tcId) {
        Entry e = new Entry();
        e.tcId = tcId;
        e.module = row.getOrDefault("Module", "").trim();
        e.api = row.getOrDefault("API_Name", "").trim();
        e.method = row.getOrDefault("Method", "").trim().toUpperCase();
        e.endpoint = row.getOrDefault("Endpoint", "").trim();
        return e;
    }

    public static Path write(Path folder, String project, List<Entry> entries) {
        try {
            Files.createDirectories(folder);
            Path out = folder.resolve(project + "_Positive_Negative_TestData.xlsx");
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                List<Entry> pos = new ArrayList<>(), neg = new ArrayList<>();
                for (Entry e : entries) ("Positive".equals(e.type) ? pos : neg).add(e);
                sheet(wb, "All tests", entries, project);
                sheet(wb, "Positive", pos, project);
                sheet(wb, "Negative", neg, project);
                try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
                    wb.write(fos);
                }
            }
            return out;
        } catch (Exception ex) {
            System.out.println("⚠ Test data catalog not written: " + ex.getMessage());
            return null;
        }
    }

    private static final String[] HEAD = {"TC ID", "Type", "Module", "API", "Method", "Endpoint", "Check", "What is sent (test data)",
            "Request body", "Expected HTTP", "Expected result", "VAPT / OWASP reference"};
    private static final int[] WIDTH = {12, 10, 22, 40, 9, 46, 26, 50, 60, 22, 46, 40};

    private static void sheet(XSSFWorkbook wb, String name, List<Entry> rows, String project) {
        XSSFSheet sh = wb.createSheet(name);
        for (int i = 0; i < WIDTH.length; i++) sh.setColumnWidth(i, WIDTH[i] * 256);

        XSSFCellStyle title = style(wb, "1F3864", "FFFFFF", true, 13);
        Row t = sh.createRow(0);
        t.setHeightInPoints(26);
        Cell tc = t.createCell(0);
        tc.setCellValue(project + " - positive and negative test data  ·  " + rows.size() + " tests  ·  generated "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")) + "  ·  ${...} values are filled in at run time");
        tc.setCellStyle(title);
        for (int i = 1; i < HEAD.length; i++) t.createCell(i).setCellStyle(title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, HEAD.length - 1));

        XSSFCellStyle head = style(wb, "1565C0", "FFFFFF", true, 10);
        Row h = sh.createRow(1);
        h.setHeightInPoints(22);
        for (int i = 0; i < HEAD.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(HEAD[i]);
            c.setCellStyle(head);
        }
        XSSFCellStyle posS = style(wb, "FFFFFF", "1C2833", false, 9), negS = style(wb, "F7F1FF", "1C2833", false, 9);
        XSSFCellStyle posT = style(wb, "E6F0FC", "1565C0", true, 9), negT = style(wb, "F1E9FF", "6C3483", true, 9);
        int rn = 2;
        for (Entry e : rows) {
            Row r = sh.createRow(rn++);
            String[] v = {e.tcId, e.type, e.module, e.api, e.method, e.endpoint, e.check, e.change, compact(e.body), e.expectedHttp, e.expected, e.vapt};
            boolean neg = "Negative".equals(e.type);
            for (int i = 0; i < v.length; i++) {
                Cell c = r.createCell(i);
                String s = v[i] == null ? "" : v[i];
                c.setCellValue(s.length() > 32000 ? s.substring(0, 32000) + " ...(truncated)" : s);
                c.setCellStyle(i == 1 ? (neg ? negT : posT) : (neg ? negS : posS));
            }
        }
        sh.setAutoFilter(new CellRangeAddress(1, Math.max(1, rn - 1), 0, HEAD.length - 1));
        sh.createFreezePane(2, 2);
    }

    private static String compact(String body) {
        if (body == null) return "";
        String b = body.replaceAll("\\s+", " ").trim();
        return b.length() > 2000 ? b.substring(0, 2000) + " ...(" + b.length() + " chars)" : b;
    }

    private static XSSFCellStyle style(XSSFWorkbook wb, String bg, String fg, boolean bold, int size) {
        XSSFCellStyle cs = wb.createCellStyle();
        cs.setFillForegroundColor(new XSSFColor(hex(bg), null));
        cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont f = wb.createFont();
        f.setColor(new XSSFColor(hex(fg), null));
        f.setBold(bold);
        f.setFontHeightInPoints((short) size);
        f.setFontName("Arial");
        cs.setFont(f);
        cs.setWrapText(true);
        cs.setVerticalAlignment(VerticalAlignment.TOP);
        cs.setBorderBottom(BorderStyle.THIN);
        cs.setBorderTop(BorderStyle.THIN);
        cs.setBorderLeft(BorderStyle.THIN);
        cs.setBorderRight(BorderStyle.THIN);
        return cs;
    }

    private static byte[] hex(String h) {
        return new byte[]{(byte) Integer.parseInt(h.substring(0, 2), 16), (byte) Integer.parseInt(h.substring(2, 4), 16),
                (byte) Integer.parseInt(h.substring(4, 6), 16)};
    }
}
