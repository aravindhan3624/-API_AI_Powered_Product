package com.api.product.core.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.api.product.core.engine.TestResult;

/**
 * Defects of the latest run as CSV files that Jira and Azure DevOps can import (no connection to either system is needed, nothing is
 * sent anywhere). By default failures are grouped per API and failure type so a systemic problem is one defect, not 300. Evidence is
 * the already redacted request/response of the run.
 */
public final class IssueExport {

    private IssueExport() {}

    public static final class Issue {
        public String title;
        public String severity;
        public String category;
        public String module;
        public String owasp;
        public String description;
        public List<String> testIds = new ArrayList<>();
    }

    public static List<Issue> build(String project, List<TestResult> results, boolean group, boolean includeBlocked) {
        Map<String, Issue> map = new LinkedHashMap<>();
        for (TestResult r : results) {
            boolean fail = "FAIL".equals(r.outcome);
            if (!fail && !(includeBlocked && "BLOCKED".equals(r.outcome) && !"LOCKOUT_GUARD".equals(r.failureCategory))) continue;
            String key = group ? r.endpointId + "|" + r.failureCategory + "|" + r.outcome : r.testCaseId;
            Issue i = map.get(key);
            if (i == null) {
                i = new Issue();
                i.severity = r.severity == null ? ("BLOCKED".equals(r.outcome) ? "low" : "medium") : r.severity;
                i.category = r.failureCategory == null ? r.outcome : r.failureCategory;
                i.module = r.module == null ? "General" : r.module;
                i.owasp = r.owasp == null ? "" : r.owasp;
                i.title = ("BLOCKED".equals(r.outcome) ? "[Blocked] " : "") + (r.apiName == null ? r.endpointId : r.apiName) + ": " + human(i.category) + (group ? "" : " (" + r.testCaseId + ")");
                i.description = describe(project, r);
                map.put(key, i);
            }
            i.testIds.add(r.testCaseId);
        }
        List<Issue> out = new ArrayList<>(map.values());
        for (Issue i : out) {
            if (i.testIds.size() > 1) i.description += "\n\nAlso affected (" + (i.testIds.size() - 1) + " more tests): " + String.join(", ", i.testIds.subList(1, Math.min(i.testIds.size(), 25))) + (i.testIds.size() > 25 ? " ..." : "");
        }
        out.sort((a, b) -> rank(b.severity) - rank(a.severity));
        return out;
    }

    private static String describe(String project, TestResult r) {
        StringBuilder d = new StringBuilder();
        d.append("Project: ").append(project).append("\nTest: ").append(r.testCaseId).append(" - ").append(r.title == null ? "" : r.title).append("\nAPI: ").append(r.method == null ? "" : r.method).append(" ").append(r.url == null ? "" : r.url)
                .append("\nCategory: ").append(r.category).append("\nExpected status: ").append(r.expectedStatus).append("   Actual: ").append(r.httpStatus == null ? "no response" : r.httpStatus)
                .append("\nFinding: ").append(r.remark == null ? "" : r.remark);
        if (r.owasp != null && !r.owasp.isBlank() && !"-".equals(r.owasp)) d.append("\nReference: ").append(r.owasp);
        d.append("\nRun: ").append(r.runId).append("  (rule pack ").append(r.ruleVersion).append(")");
        d.append("\n\nRequest (redacted):\n").append(cut(r.requestEvidence, 1500)).append("\n\nResponse (redacted):\n").append(cut(r.responseEvidence, 1500));
        d.append("\n\nHow to reproduce: send the request above to the same environment; compare the status and body with the expectation.");
        return d.toString();
    }

    public static String jiraCsv(List<Issue> issues) {
        StringBuilder sb = new StringBuilder("Summary,Description,Issue Type,Priority,Labels,Component/s\n");
        for (Issue i : issues) {
            sb.append(q(i.title)).append(',').append(q(i.description)).append(",Bug,").append(jiraPriority(i.severity)).append(',')
                    .append(q(("api-quality " + i.category + " " + (i.owasp.isEmpty() ? "" : i.owasp.replaceAll("[^A-Za-z0-9]+", "-"))).trim().replaceAll("\\s+", " "))).append(',').append(q(i.module)).append('\n');
        }
        return sb.toString();
    }

    public static String azureCsv(List<Issue> issues) {
        StringBuilder sb = new StringBuilder("Work Item Type,Title,Repro Steps,Priority,Severity,Tags\n");
        for (Issue i : issues) {
            String html = "<pre>" + i.description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
            sb.append("Bug,").append(q(i.title)).append(',').append(q(html)).append(',').append(azurePriority(i.severity)).append(',').append(q(azureSeverity(i.severity))).append(',')
                    .append(q("api-quality; " + i.category + "; " + i.module)).append('\n');
        }
        return sb.toString();
    }

    private static int rank(String s) {
        switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
            case "critical": return 4;
            case "high": return 3;
            case "medium": return 2;
            default: return 1;
        }
    }

    private static String jiraPriority(String s) {
        switch (rank(s)) {
            case 4: return "Highest";
            case 3: return "High";
            case 2: return "Medium";
            default: return "Low";
        }
    }

    private static int azurePriority(String s) {
        return 5 - rank(s);
    }

    private static String azureSeverity(String s) {
        switch (rank(s)) {
            case 4: return "1 - Critical";
            case 3: return "2 - High";
            case 2: return "3 - Medium";
            default: return "4 - Low";
        }
    }

    private static String human(String category) {
        return category == null ? "" : category.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String cut(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
