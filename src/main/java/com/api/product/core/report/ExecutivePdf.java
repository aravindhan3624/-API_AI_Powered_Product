package com.api.product.core.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.api.product.core.engine.TestResult;

/** One-document PDF of a run for managers and auditors: summary, module table, failures, blocked reasons. */
final class ExecutivePdf {

    private ExecutivePdf() {}

    static void write(Path file, ReportGenerator.Meta meta, List<TestResult> results, Map<String, Object> summary, String started, String ended) throws IOException {
        try (PdfBuilder pdf = new PdfBuilder("API test report - " + meta.project + " - run " + meta.runId)) {
            pdf.title("API Test Report", meta.project + "  |  " + (meta.environment == null ? "" : meta.environment) + "  |  " + (meta.baseUrl == null ? "" : meta.baseUrl));
            pdf.h1("1. Run");
            pdf.table(new String[] { "Item", "Value" }, new float[] { 0.25f, 0.75f }, List.of(
                    new String[] { "Run id", meta.runId },
                    new String[] { "Executed", started + "  to  " + ended },
                    new String[] { "Tester", meta.tester },
                    new String[] { "Source", String.valueOf(meta.source) },
                    new String[] { "Rule pack", String.valueOf(meta.ruleVersion) }));
            pdf.h1("2. Result summary");
            java.util.Collections.singletonList(0);
            pdf.table(new String[] { "Total", "Passed", "Failed", "Blocked", "Skipped", "Not executed", "Pass rate", "Avg ms" },
                    new float[] { 0.1f, 0.11f, 0.11f, 0.12f, 0.12f, 0.16f, 0.16f, 0.12f },
                    java.util.Collections.singletonList(new String[] { s(summary, "total"), s(summary, "PASS"), s(summary, "FAIL"), s(summary, "BLOCKED"), s(summary, "SKIPPED"),
                            s(summary, "NOT_EXECUTED"), s(summary, "passRate"), s(summary, "avgMs") }));
            pdf.p("Pass rate = passed / (passed + failed). Blocked tests were not sent (a prerequisite failed or credentials were missing); they are shown separately and are not counted as passes. "
                    + "A FAIL is a finding: the API behaved differently from the expectation or from the rule pack.");

            pdf.h1("3. By module");
            Map<String, List<TestResult>> g = results.stream().collect(Collectors.groupingBy(r -> r.module == null ? "General" : r.module, TreeMap::new, Collectors.toList()));
            List<String[]> rows = new ArrayList<>();
            for (Map.Entry<String, List<TestResult>> e : g.entrySet()) {
                Map<String, Object> s = ReportGenerator.summarize(e.getValue());
                rows.add(new String[] { e.getKey(), s(s, "total"), s(s, "PASS"), s(s, "FAIL"), s(s, "BLOCKED"), s(s, "NOT_EXECUTED"), s(s, "passRate") });
            }
            pdf.table(new String[] { "Module", "Total", "Passed", "Failed", "Blocked", "Not exec.", "Pass rate" }, new float[] { 0.3f, 0.1f, 0.12f, 0.12f, 0.12f, 0.12f, 0.12f }, rows);

            pdf.h1("4. Failed tests (most severe first)");
            List<TestResult> fails = results.stream().filter(r -> "FAIL".equals(r.outcome))
                    .sorted(Comparator.comparingInt((TestResult r) -> -VaptReport.rank(r.severity))).collect(Collectors.toList());
            if (fails.isEmpty()) {
                pdf.p("No test failed.");
            } else {
                List<String[]> fr = new ArrayList<>();
                for (TestResult r : fails.stream().limit(60).collect(Collectors.toList())) {
                    fr.add(new String[] { r.testCaseId, (r.method == null ? "" : r.method + " ") + path(r.url), r.category, r.httpStatus == null ? "-" : String.valueOf(r.httpStatus),
                            r.severity == null ? "-" : r.severity, r.remark == null ? "" : r.remark });
                }
                pdf.table(new String[] { "Test", "Endpoint", "Check", "HTTP", "Severity", "Why" }, new float[] { 0.12f, 0.24f, 0.13f, 0.07f, 0.1f, 0.34f }, fr);
                if (fails.size() > 60) pdf.p("... and " + (fails.size() - 60) + " more failed tests; see the HTML or Excel report for the complete list with evidence.");
            }

            pdf.h1("5. Blocked tests (not sent)");
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (TestResult r : results) {
                if (!"BLOCKED".equals(r.outcome)) continue;
                String k = r.remark == null ? "unknown" : r.remark.replaceAll("\\$\\{[^}]*}", java.util.regex.Matcher.quoteReplacement("${..}"));
                if (k.length() > 110) k = k.substring(0, 110) + "...";
                reasons.merge(k, 1, Integer::sum);
            }
            if (reasons.isEmpty()) {
                pdf.p("Nothing was blocked.");
            } else {
                List<String[]> br = new ArrayList<>();
                reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15).forEach(e -> br.add(new String[] { e.getKey(), String.valueOf(e.getValue()) }));
                pdf.table(new String[] { "Reason", "Tests" }, new float[] { 0.88f, 0.12f }, br);
            }
            pdf.save(file);
        }
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "0" : String.valueOf(v);
    }

    private static String path(String url) {
        if (url == null) return "";
        try {
            String p = java.net.URI.create(url).getPath();
            return p == null ? url : p;
        } catch (Exception e) {
            return url;
        }
    }
}
