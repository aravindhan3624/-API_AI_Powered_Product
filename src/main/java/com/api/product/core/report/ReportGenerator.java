package com.api.product.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.api.product.core.engine.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes the project-wise run outputs:
 * <pre>reports/&lt;project&gt;/Reports_&lt;project&gt;_&lt;dd-MMM-yy&gt;_&lt;HH-mm&gt;.html  (single offline file, module-wise, searchable)
 * reports/&lt;project&gt;/Reports_&lt;project&gt;_&lt;dd-MMM-yy&gt;_&lt;HH-mm&gt;.xlsx  (customisable sheets)
 * reports/&lt;project&gt;/index.html                                           (clickable history of every report)</pre>
 */
public final class ReportGenerator {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Locale EN = Locale.ENGLISH;
    private static final String[] OUTCOMES = { "PASS", "FAIL", "BLOCKED", "SKIPPED", "NOT_EXECUTED" };

    public static final class Meta {
        public String project;
        public String environment;
        public String baseUrl;
        public String source;
        public String runId;
        public String ruleVersion;
        public String tester = System.getProperty("user.name", "");
    }

    public static final class Output {
        public Path html;
        public Path excel;
        public Path index;
        public Path pdf;
        public Path vaptHtml;
        public Path vaptPdf;
    }

    private ReportGenerator() {}

    /**
     * @param sheets comma list of Summary,Modules,All,Failures,Security (blank = all)
     */
    public static Output write(Path reportsRoot, Meta meta, List<TestResult> results, String sheets) throws IOException {
        Path dir = reportsRoot.resolve(safe(meta.project));
        Files.createDirectories(dir);

        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        String stem = "Reports_" + safe(meta.project) + "_"
                + now.format(DateTimeFormatter.ofPattern("dd-MMM-yy_HH-mm", EN));
        if (Files.exists(dir.resolve(stem + ".html"))) stem += now.format(DateTimeFormatter.ofPattern("_ss"));

        Output out = new Output();
        out.html = dir.resolve(stem + ".html");
        out.excel = dir.resolve(stem + ".xlsx");
        out.index = dir.resolve("index.html");
        String vstem = stem.replaceFirst("^Reports_", "VAPT_");
        out.pdf = dir.resolve(stem + ".pdf");
        out.vaptHtml = dir.resolve(vstem + ".html");
        out.vaptPdf = dir.resolve(vstem + ".pdf");

        Map<String, Object> summary = summarize(results);
        String started = results.stream().map(r -> r.startedAt).filter(s -> s != null).min(String::compareTo).orElse("");
        String ended = results.stream().map(r -> r.endedAt).filter(s -> s != null).max(String::compareTo).orElse("");

        ObjectNode data = M.createObjectNode();
        data.put("project", meta.project);
        data.put("environment", meta.environment == null ? "" : meta.environment);
        data.put("baseUrl", meta.baseUrl == null ? "" : meta.baseUrl);
        data.put("source", meta.source == null ? "" : meta.source);
        data.put("runId", meta.runId);
        data.put("tester", meta.tester);
        data.put("ruleVersion", meta.ruleVersion == null ? "" : meta.ruleVersion);
        data.put("startedAt", started);
        data.put("finishedAt", ended);
        data.put("duration", duration(started, ended));
        ObjectNode links = data.putObject("links");
        links.put("excel", stem + ".xlsx");
        links.put("pdf", stem + ".pdf");
        links.put("vaptHtml", vstem + ".html");
        links.put("vaptPdf", vstem + ".pdf");
        data.put("generatedAt", now.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", EN)));
        ArrayNode arr = data.putArray("results");
        for (TestResult r : results) arr.add(M.valueToTree(r));

        String tpl = resource("report/report-template.html");
        String json = M.writeValueAsString(data).replace("</", "<\\/");
        Files.writeString(out.html, tpl.replace("/*__DATA__*/null", json), StandardCharsets.UTF_8);

        VaptReport.Report vapt = VaptReport.build(results);
        writeExcel(out.excel, meta, results, summary, sheets, vapt);
        ExecutivePdf.write(out.pdf, meta, results, summary, started, ended);
        VaptReport.writeHtml(out.vaptHtml, meta, started, ended, vapt);
        VaptReport.writePdf(out.vaptPdf, meta, started, ended, vapt);
        updateIndex(dir, meta, stem, vstem, summary, started, now);
        return out;
    }

    // ------------------------------------------------------------------ summary

    public static Map<String, Object> summarize(List<TestResult> results) {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String o : OUTCOMES) c.put(o, 0);
        for (TestResult r : results) c.merge(r.outcome, 1, Integer::sum);
        int den = c.get("PASS") + c.get("FAIL");
        s.put("total", results.size());
        s.putAll(c);
        s.put("passRate", den == 0 ? "n/a" : String.format(EN, "%.1f%%", 100.0 * c.get("PASS") / den));
        long n = results.stream().filter(r -> r.httpStatus != null).count();
        s.put("avgMs", n == 0 ? 0 : Math.round(results.stream().filter(r -> r.httpStatus != null).mapToLong(r -> r.latencyMs).sum() / (double) n));
        return s;
    }

    private static String duration(String a, String b) {
        try {
            long s = Duration.between(Instant.parse(a), Instant.parse(b)).getSeconds();
            return s / 60 + "m " + s % 60 + "s";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ excel

    private static void writeExcel(Path file, Meta meta, List<TestResult> results, Map<String, Object> summary, String sheets,
            VaptReport.Report vapt) throws IOException {
        Set<String> want = sheets == null || sheets.isBlank() ? null
                : java.util.Arrays.stream(sheets.split(",")).map(x -> x.trim().toLowerCase(EN)).collect(Collectors.toSet());
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            if (want == null || want.contains("summary")) {
                Sheet sh = wb.createSheet("Summary");
                int r = 0;
                String[][] kv = { { "Project", meta.project }, { "Environment", nz(meta.environment) }, { "Base URL", nz(meta.baseUrl) },
                        { "Run ID", meta.runId }, { "Source", nz(meta.source) }, { "Tester", meta.tester },
                        { "Rule pack", nz(meta.ruleVersion) } };
                for (String[] p : kv) row(sh, r++, null, p[0], p[1]);
                r++;
                for (Map.Entry<String, Object> e : summary.entrySet()) row(sh, r++, null, e.getKey(), String.valueOf(e.getValue()));
                row(sh, r++, null, "Pass rate basis", "passed / (passed + failed)");
                sh.setColumnWidth(0, 6000);
                sh.setColumnWidth(1, 14000);
            }
            if (want == null || want.contains("modules")) {
                Sheet sh = wb.createSheet("Modules");
                row(sh, 0, head, "Module", "Total", "Passed", "Failed", "Blocked", "Skipped", "Not executed", "Pass rate");
                Map<String, List<TestResult>> g = results.stream().collect(Collectors.groupingBy(
                        r -> r.module == null ? "General" : r.module, TreeMap::new, Collectors.toList()));
                int r = 1;
                for (Map.Entry<String, List<TestResult>> e : g.entrySet()) {
                    Map<String, Object> s = summarize(e.getValue());
                    row(sh, r++, null, e.getKey(), s.get("total"), s.get("PASS"), s.get("FAIL"), s.get("BLOCKED"),
                            s.get("SKIPPED"), s.get("NOT_EXECUTED"), s.get("passRate"));
                }
                finish(sh, 8);
            }
            if (want == null || want.contains("all")) testSheet(wb, "All tests", results, head);
            if (want == null || want.contains("failures")) {
                testSheet(wb, "Failures", results.stream().filter(r -> "FAIL".equals(r.outcome) || "BLOCKED".equals(r.outcome))
                        .collect(Collectors.toList()), head);
            }
            if (want == null || want.contains("security")) {
                testSheet(wb, "Security", results.stream().filter(r -> r.owasp != null && !r.owasp.isBlank())
                        .collect(Collectors.toList()), head);
            }
            if (want == null || want.contains("vapt")) {
                Sheet sh = wb.createSheet("VAPT Findings");
                row(sh, 0, head, "ID", "Finding", "Severity", "OWASP", "CWE", "Failed checks", "Affected endpoints", "Recommendation", "Status");
                int r = 1;
                for (VaptReport.Finding f : vapt.findings) {
                    row(sh, r++, null, f.id, f.info.title, f.severity, f.info.owasp, f.info.cwe, f.count, String.join("; ", f.affected), f.info.fix, f.status);
                }
                finish(sh, 9);
            }
            if (wb.getNumberOfSheets() == 0) wb.createSheet("Empty");
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }
    }

    private static void testSheet(XSSFWorkbook wb, String name, List<TestResult> rs, CellStyle head) {
        Sheet sh = wb.createSheet(name);
        row(sh, 0, head, "Test ID", "Module", "API", "Method", "Base URL", "API URL", "Category", "Title", "Expected HTTP",
                "Actual HTTP", "Outcome", "Remark", "Severity", "OWASP / CWE", "Latency ms", "Started", "Request", "Response");
        int r = 1;
        for (TestResult t : rs) {
            row(sh, r++, null, t.testCaseId, t.module, t.apiName, t.method, t.baseUrl, t.url, t.category, t.title,
                    t.expectedStatus == null ? "" : t.expectedStatus.toString(), t.httpStatus, t.outcome, t.remark,
                    t.severity, t.owasp, t.latencyMs, t.startedAt, cut(t.requestEvidence), cut(t.responseEvidence));
        }
        finish(sh, 20);
    }

    private static void row(Sheet sh, int idx, CellStyle style, Object... vals) {
        Row row = sh.createRow(idx);
        for (int i = 0; i < vals.length; i++) {
            Cell c = row.createCell(i);
            Object v = vals[i];
            if (v instanceof Number n) c.setCellValue(n.doubleValue());
            else c.setCellValue(v == null ? "" : String.valueOf(v));
            if (style != null) c.setCellStyle(style);
        }
    }

    private static void finish(Sheet sh, int cols) {
        sh.createFreezePane(0, 1);
        if (sh.getLastRowNum() >= 0) {
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, sh.getLastRowNum()), 0, cols - 1));
        }
        for (int i = 0; i < cols; i++) sh.setColumnWidth(i, i >= 17 ? 12000 : i == 5 || i == 7 || i == 11 ? 9000 : 4500);
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 32000 ? s.substring(0, 32000) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ history index

    private static void updateIndex(Path dir, Meta meta, String stem, String vstem, Map<String, Object> summary, String started,
            LocalDateTime now) throws IOException {
        Path runs = dir.resolve("runs.json");
        ArrayNode list = Files.exists(runs) ? (ArrayNode) M.readTree(runs.toFile()) : M.createArrayNode();
        ObjectNode e = list.addObject();
        e.put("file", stem + ".html");
        e.put("excel", stem + ".xlsx");
        e.put("pdf", stem + ".pdf");
        e.put("vaptHtml", vstem + ".html");
        e.put("vaptPdf", vstem + ".pdf");
        e.put("runId", meta.runId);
        e.put("environment", nz(meta.environment));
        e.put("when", now.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", EN)));
        e.put("epoch", System.currentTimeMillis());
        e.put("total", (Integer) summary.get("total"));
        e.put("passed", (Integer) summary.get("PASS"));
        e.put("failed", (Integer) summary.get("FAIL"));
        e.put("blocked", (Integer) summary.get("BLOCKED") + (Integer) summary.get("NOT_EXECUTED"));
        e.put("passRate", String.valueOf(summary.get("passRate")));
        M.writerWithDefaultPrettyPrinter().writeValue(runs.toFile(), list);

        // also surface reports written by the legacy console in the same folder
        List<String> known = new ArrayList<>();
        list.forEach(n -> known.add(n.path("file").asText()));
        ArrayNode legacy = M.createArrayNode();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "Reports_*.html")) {
            for (Path p : ds) {
                if (known.contains(p.getFileName().toString())) continue;
                ObjectNode l = legacy.addObject();
                l.put("file", p.getFileName().toString());
                l.put("when", Files.getLastModifiedTime(p).toString().replace('T', ' ').substring(0, 16));
                l.put("epoch", Files.getLastModifiedTime(p).toMillis());
            }
        }
        ObjectNode all = M.createObjectNode();
        all.put("project", meta.project);
        ArrayNode merged = all.putArray("runs");
        list.forEach(merged::add);
        legacy.forEach(merged::add);
        List<com.fasterxml.jackson.databind.JsonNode> sorted = new ArrayList<>();
        merged.forEach(sorted::add);
        Collections.sort(sorted, (a, b) -> Long.compare(b.path("epoch").asLong(), a.path("epoch").asLong()));
        ArrayNode finalList = all.putArray("runs");
        finalList.removeAll();
        sorted.forEach(finalList::add);

        String tpl = resource("report/index-template.html");
        Files.writeString(dir.resolve("index.html"),
                tpl.replace("/*__DATA__*/null", M.writeValueAsString(all).replace("</", "<\\/")), StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ReportGenerator.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String safe(String s) {
        return s == null ? "project" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public static Path defaultRoot() {
        return Paths.get("reports");
    }
}
