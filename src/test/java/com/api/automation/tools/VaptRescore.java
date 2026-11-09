package com.api.automation.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.api.automation.config.ConfigManager;
import com.api.automation.config.ProjectContext;
import com.api.automation.negative.VaptRules;
import com.api.automation.report.FailureClassifier;
import com.api.automation.report.PdfReports;
import com.api.automation.report.VaptModel;
import com.api.automation.utils.ExcelUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Re-scores a SAVED run with the current VAPT rules - no request is sent, nothing is changed in the run folder.
 *
 * Reads the run's saved HTML report (frozen console state) and responses/*.json, applies negative.VaptRules to every
 * negative test, and writes into  <run folder>/rescore_<date_time>/ :
 *   Reports_<P>_rescored.xlsx, VAPT_Report_*.pdf, Failure_Report_*.pdf  (same layout as a live run)
 *   VAPT_Rescore_Summary.txt   findings, counts old -> new
 *   VAPT_Rescore_Changes.csv   every negative test: old result -> new result, class, severity, reason
 *
 * Limits: the SQL injection boolean / time probes and the state-check snapshot need live requests, so injection is
 * judged on the DB-error proof only and STATE_CHECK rows are INCONCLUSIVE (re-run needed).
 *
 * Run in IntelliJ: right-click -> Run 'VaptRescore.main()'; program argument = run folder
 * (default: the newest folder under reports/CKYC). Example: reports/CKYC/2026-11-02_16-07-58
 */
public final class VaptRescore {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern RULE = Pattern.compile("rule (\\w+)");
    private static final Pattern NEG_TITLE = Pattern.compile("\\|\\s*(?:NEG|CHECK)\\s+\\w+\\s+(.*)$");

    private VaptRescore() {}

    public static void main(String[] args) throws Exception {
        Path run = args.length > 0 ? Paths.get(args[0]) : newestRun(Paths.get("reports", "CKYC"));
        if (run == null || !Files.isDirectory(run)) {
            System.out.println("Run folder not found: " + (args.length > 0 ? args[0] : "reports/CKYC/<newest>"));
            return;
        }
        Path html;
        try (Stream<Path> s = Files.list(run)) {
            html = s.filter(p -> p.getFileName().toString().matches("Reports_.*\\.html")).findFirst().orElse(null);
        }
        if (html == null) {
            System.out.println("No saved HTML report (Reports_*.html) in " + run);
            return;
        }
        JsonNode runState = readState(html);
        String project = runState.path("project").asText("CKYC");
        ProjectContext.set(project);

        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> oldResult = new LinkedHashMap<>();
        for (JsonNode t : runState.path("tests")) {
            Map<String, String> r = toRow(t, run);
            rows.add(r);
            oldResult.put(r.get("TC_ID"), r.get("Result"));
        }
        for (Map<String, String> r : rows) {
            if (!"Y".equals(r.get("Negative")) && isExecuted(r)) VaptRules.POSITIVE_BODY.put(r.get("TC_ID"), r.getOrDefault("ActualResponse", ""));
        }
        // pass 1: fields where an invalid value reached the database (needed before judging short values on the same field)
        for (Map<String, String> r : rows) {
            if (!scorable(r)) continue;
            VaptRules.Leak l = VaptRules.findLeak(r.getOrDefault("ActualResponse", ""));
            if (l != null && l.sql) VaptRules.noteDbError(r.get("Endpoint"), r.get("NegField"), r.get("TC_ID"));
        }
        // pass 2: verdicts
        for (Map<String, String> r : rows) {
            if (isExecuted(r)) {
                VaptRules.Leak l = VaptRules.findLeak(r.getOrDefault("ActualResponse", ""));
                r.put("Leak_Evidence", l == null ? "" : l.describe());
            }
            if (scorable(r)) rescore(r);
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path out = run.resolve("rescore_" + stamp);
        Files.createDirectories(out);
        ExcelUtils.ReportMeta meta = new ExcelUtils.ReportMeta();
        meta.project = project;
        meta.displayName = ConfigManager.get(project + ".display.name", runState.path("displayName").asText(project));
        meta.env = runState.path("env").asText("");
        meta.baseUrl = runState.path("baseUrl").asText("");
        meta.startedAt = runState.path("startedAt").asLong(0);
        meta.finishedAt = runState.path("finishedAt").asLong(0);
        meta.testedBy = runState.path("testedBy").asText("");
        meta.runner = "VaptRescore of " + run.getFileName() + " (saved evidence, no new requests)";
        String name = project + "_" + run.getFileName() + "_rescored";
        meta.vaptPdfName = "VAPT_Report_" + name + ".pdf";
        meta.failurePdfName = "Failure_Report_" + name + ".pdf";
        ExcelUtils.writeReport(out.resolve("Reports_" + name + ".xlsx").toString(), rows, meta);
        // -Dpdf=false: Excel + summary only (the PDFs are printed by a headless Chrome / Edge)
        PdfReports.Result pdf = Boolean.parseBoolean(System.getProperty("pdf", "true")) ? PdfReports.generate(rows, meta, out, name) : null;

        writeSummary(out, run, rows, oldResult, pdf);
        System.out.println("\nCOMPLETED - re-scored " + run.getFileName() + " -> " + out.toAbsolutePath());
    }

    // ── one negative test ────────────────────────────────────────────────
    private static void rescore(Map<String, String> r) {
        String cat = r.getOrDefault("NegCategory", "");
        String tc = r.get("TC_ID");
        String base = tc.replaceAll("_[A-Z0-9]+$", "");
        VaptRules.Verdict v;
        if ("STATE_CHECK".equals(cat)) {
            v = new VaptRules.Verdict(VaptRules.Outcome.INCONCLUSIVE, VaptRules.Cls.INCONCLUSIVE, "",
                    "INCONCLUSIVE - re-run needed: the state check now compares with a snapshot taken right before the negatives of this API; the saved run has none", null);
        } else {
            VaptRules.Input in = new VaptRules.Input();
            in.tc = tc;
            in.category = cat;
            in.method = r.getOrDefault("Method", "");
            in.endpoint = r.getOrDefault("Endpoint", "");
            in.field = r.get("NegField");
            in.http = parseInt(r.get("HTTP"));
            in.contentType = r.getOrDefault("Format", "").matches("(?s).*→\\s*HTML.*") ? "text/html" : "application/json";
            in.response = r.getOrDefault("ActualResponse", "");
            in.pageRejected = VaptRules.businessRejected(in.response);
            in.encryptedBody = !r.getOrDefault("WireRequest", "").isEmpty() && !in.method.matches("GET|HEAD|OPTIONS");
            in.positiveResponse = VaptRules.POSITIVE_BODY.get(base);
            v = VaptRules.evaluate(in);
        }
        String result = v.outcome == VaptRules.Outcome.PASS ? "PASS" : v.outcome == VaptRules.Outcome.FAIL ? "FAIL" : "SKIP";
        r.put("Result", result);
        r.put("Error", v.reason);
        r.put("VAPT_Class", v.cls.name());
        r.put("VAPT_Severity", v.outcome == VaptRules.Outcome.FAIL && v.cls == VaptRules.Cls.VAPT ? v.severity : "");
        r.put("VAPT_Point", v.point == null ? cat : v.point);
        FailureClassifier.Verdict fc = FailureClassifier.classify(result, parseInt(r.get("HTTP")), v.reason);
        r.put("Failure_Category", fc.category);
        r.put("Failure_Reason", fc.reason);
        r.put("Action_Needed", fc.action);
        switch (v.cls) {
            case NA:
                r.put("Failure_Category", "NOT NEEDED - NOT APPLICABLE");
                r.put("Failure_Reason", v.reason);
                r.put("Action_Needed", "None - this check does not apply to this API.");
                break;
            case INCONCLUSIVE:
                r.put("Failure_Category", "INCONCLUSIVE");
                r.put("Failure_Reason", v.reason);
                r.put("Action_Needed", "STATE_CHECK".equals(cat) ? "Run again with the new framework."
                        : "Tester: give the positive test data that returns a record, then run again.");
                break;
            case FUNCTIONAL:
                r.put("Failure_Category", "FUNCTIONAL - QA (NOT VAPT)");
                r.put("Failure_Reason", v.reason);
                r.put("Action_Needed", "Developer: add the business validation and return 400 with a clear message (no security impact).");
                break;
            default:
                break;
        }
    }

    private static boolean scorable(Map<String, String> r) {
        String cat = r.getOrDefault("NegCategory", "");
        return "Y".equals(r.get("Negative")) && isExecuted(r) && !cat.isEmpty()
                && !cat.matches("VERIFY_LOGIN|RELOGIN|NOT_EXECUTABLE");
    }

    private static boolean isExecuted(Map<String, String> r) {
        String res = r.getOrDefault("Result", "");
        return "PASS".equals(res) || "FAIL".equals(res);
    }

    // ── saved run -> report rows ─────────────────────────────────────────
    private static Map<String, String> toRow(JsonNode t, Path run) {
        Map<String, String> r = new HashMap<>();
        String tc = t.path("tcId").asText();
        String status = t.path("status").asText("");
        r.put("TC_ID", tc);
        r.put("API_Name", t.path("apiName").asText(""));
        r.put("Module", t.path("module").asText(""));
        r.put("Method", t.path("method").asText(""));
        r.put("Endpoint", t.path("endpoint").asText(""));
        r.put("Base URL", t.path("baseUrl").asText(""));
        r.put("HTTP", t.path("httpStatus").isNull() ? "0" : t.path("httpStatus").asText("0"));
        r.put("Exec Time", t.path("timeMs").asLong(0) + "ms");
        r.put("ExpectedResult", t.path("expected").asText(""));
        r.put("Expected_HTTP", t.path("expectedHttp").asText(""));
        r.put("HTTP_Check", t.path("httpCheck").asText(""));
        r.put("VAPT_Ref", t.path("vapt").asText(""));
        r.put("ActualResult", t.path("actual").asText(""));
        r.put("Error", t.path("reason").asText(""));
        r.put("Failure_Category", t.path("category").asText(""));
        r.put("Failure_Reason", t.path("remark").asText(""));
        r.put("Action_Needed", t.path("action").asText(""));
        r.put("Data_Source", t.path("dataSource").asText(""));
        r.put("Executed_At", t.path("executedAt").asText(""));
        r.put("Format", t.path("format").asText(""));
        r.put("TestData", t.path("testData").asText("NA"));
        r.put("ResponseFile", t.path("responseFile").asText(""));
        r.put("WireRequest", t.path("wireRequest").isNull() ? "" : t.path("wireRequest").asText(""));
        r.put("WireResponse", t.path("wireResponse").isNull() ? "" : t.path("wireResponse").asText(""));
        String req = t.path("request").asText("");
        int nl = req.indexOf("\n\n");
        r.put("RequestBody", nl >= 0 ? req.substring(nl + 2) : "");
        r.put("ActualResponse", fullResponse(run, tc, t.path("response").asText("")));
        boolean neg = t.path("negative").asBoolean(false);
        r.put("Negative", neg ? "Y" : "");
        Matcher rule = RULE.matcher(r.get("Data_Source"));
        Matcher title = NEG_TITLE.matcher(r.get("API_Name"));
        r.put("NegTitle", title.find() ? title.group(1).trim() : "");
        if ("NA".equals(status)) {
            r.put("Result", "N/A");
            r.put("NegCategory", "NOT_EXECUTABLE");
            r.put("NA_Count", "1");
        } else {
            r.put("Result", status);
            r.put("NegCategory", neg && rule.find() ? rule.group(1) : "");
        }
        String td = r.get("TestData");
        int c = td.indexOf(": ");
        r.put("NegField", neg && c > 0 ? nz(VaptRules.fieldOf(td.substring(c + 2))) : "");
        return r;
    }

    /** responses/<tc>.json holds the full (masked) response; the console state keeps only the first 20,000 characters */
    private static String fullResponse(Path run, String tc, String fallback) {
        try {
            Path f = run.resolve("responses").resolve(tc.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
            if (Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // fall back to the console copy
        }
        return fallback;
    }

    private static JsonNode readState(Path html) throws Exception {
        String h = Files.readString(html, StandardCharsets.UTF_8);
        int i = h.indexOf("window.__STATIC_STATE__");
        if (i < 0) throw new IllegalStateException("No saved console state in " + html.getFileName());
        int eq = h.indexOf('=', i);
        try (JsonParser p = M.getFactory().createParser(h.substring(eq + 1))) {
            JsonNode state = M.readTree(p);
            JsonNode runs = state.path("runs");
            return runs.isArray() ? runs.get(0) : runs.elements().next();
        }
    }

    private static Path newestRun(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_.*"))
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
    }

    // ── summary ──────────────────────────────────────────────────────────
    private static void writeSummary(Path out, Path run, List<Map<String, String>> rows, Map<String, String> oldResult, PdfReports.Result pdf) throws Exception {
        List<VaptModel.Point> pts = VaptModel.build(rows);
        List<VaptModel.Finding> findings = VaptModel.findings(pts);
        StringBuilder s = new StringBuilder();
        s.append("VAPT RE-SCORE of ").append(run.toAbsolutePath()).append("\n");
        s.append("Written ").append(LocalDateTime.now()).append(" - saved evidence only, no request was sent.\n\n");

        int oldFail = 0, newFail = 0;
        Map<String, Integer> cls = new TreeMap<>();
        for (Map<String, String> r : rows) {
            if (!scorableOld(r, oldResult)) continue;
            if ("FAIL".equals(oldResult.get(r.get("TC_ID")))) oldFail++;
            if ("FAIL".equals(r.get("Result")) && "VAPT".equals(r.get("VAPT_Class"))) newFail++;
            String k = r.getOrDefault("VAPT_Class", "?") + " " + r.get("Result");
            cls.merge(k, 1, Integer::sum);
        }
        s.append("Negative tests: FAIL before = ").append(oldFail).append(", VAPT FAIL now = ").append(newFail).append("\n");
        for (Map.Entry<String, Integer> e : cls.entrySet()) s.append("   ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        long leakPos = rows.stream().filter(r -> !"Y".equals(r.get("Negative")) && !r.getOrDefault("Leak_Evidence", "").isEmpty()).count();
        s.append("Positive tests with leaked internal details (listed under VAPT-032): ").append(leakPos).append("\n\n");

        s.append("CONFIRMED FINDINGS (").append(findings.size()).append(")\n");
        for (VaptModel.Finding f : findings) {
            s.append(f.id).append("  ").append(f.severity).append("  ").append(f.title).append("  (").append(f.point.id).append(")\n      APIs: ")
                    .append(String.join(", ", f.apis)).append("\n      tests (").append(f.rows.size()).append("): ");
            List<String> ids = new ArrayList<>();
            for (Map<String, String> r : f.rows) ids.add(r.get("TC_ID"));
            s.append(String.join(", ", ids)).append("\n");
            Map<String, String> first = f.rows.get(0);
            String why = "INTERNAL_LEAK".equals(f.point.key) && !first.getOrDefault("Leak_Evidence", "").isEmpty()
                    ? "leaked: " + first.get("Leak_Evidence") : first.getOrDefault("Error", "");
            s.append("      ").append(why.length() > 300 ? why.substring(0, 300) + "..." : why).append("\n");
        }
        s.append("\nFunctional (QA, not VAPT): ").append(VaptModel.functional(rows).size()).append("\n");
        s.append("\nFiles: ").append(out.toAbsolutePath()).append("\n");
        if (pdf != null && !pdf.note.isEmpty()) s.append("PDF note: ").append(pdf.note).append("\n");
        Files.writeString(out.resolve("VAPT_Rescore_Summary.txt"), s.toString(), StandardCharsets.UTF_8);

        StringBuilder csv = new StringBuilder("TC_ID,Check,Field,HTTP,Old result,New result,Class,Severity,VAPT point,Reason\n");
        for (Map<String, String> r : rows) {
            if (!scorableOld(r, oldResult)) continue;
            csv.append(String.join(",", q(r.get("TC_ID")), q(r.get("NegCategory")), q(r.get("NegField")), q(r.get("HTTP")),
                    q(oldResult.get(r.get("TC_ID"))), q(r.get("Result")), q(r.get("VAPT_Class")), q(r.get("VAPT_Severity")),
                    q(r.get("VAPT_Point")), q(r.get("Error")))).append("\n");
        }
        Files.writeString(out.resolve("VAPT_Rescore_Changes.csv"), "﻿" + csv, StandardCharsets.UTF_8);
        System.out.println(s);
    }

    private static boolean scorableOld(Map<String, String> r, Map<String, String> oldResult) {
        String old = oldResult.getOrDefault(r.get("TC_ID"), "");
        String cat = r.getOrDefault("NegCategory", "");
        return "Y".equals(r.get("Negative")) && ("PASS".equals(old) || "FAIL".equals(old)) && !cat.isEmpty()
                && !cat.matches("VERIFY_LOGIN|RELOGIN|NOT_EXECUTABLE");
    }

    private static String q(String s) {
        String v = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
