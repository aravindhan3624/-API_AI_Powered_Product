package com.api.automation.report;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.api.automation.config.ConfigManager;
import com.api.automation.negative.ExpectedStatus;

/**
 * VAPT points of a run: one numbered point (VAPT-001 ...) per security check, with its status
 * PASS / FAIL / SKIP / NA, the endpoints it was applied to, and the evidence.
 *
 * Only rows classified as VAPT count (see negative.VaptRules): FUNCTIONAL rows (QA defects, no security impact)
 * go to a separate section, NOT APPLICABLE and INCONCLUSIVE rows are listed but never counted as failures.
 *
 * The numbers are fixed by {@link #ORDER}, so VAPT-004 means the same in every project and every run;
 * new checks are appended at the END of ORDER (VAPT-033 ...), whatever category they are shown under.
 * Used by the Excel VAPT sheet and the two PDF reports.
 */
public final class VaptModel {

    /** Category of every check, in report order. */
    public static final String[][] CATEGORIES = {
            {"A. Authentication and session", "NO_SESSION", "TAMPERED_TOKEN", "TAMPERED_CSRF", "WRONG_PASSWORD"},
            {"B. Authorization (access to other users' data)", "OTHER_USER"},
            {"C. Injection and scripting", "INJECTION", "XSS_SCRIPT", "XXE", "MALFORMED_XML"},
            {"D. Data integrity and encryption", "TAMPERED_ENCRYPTION", "STATE_CHECK", "PLAIN_BODY"},
            {"E. Input validation", "NOT_EXISTING", "WRONG_TYPE", "MISSING_FIELD", "EMPTY_VALUE", "SPACES_ONLY", "SPECIAL_CHARS", "UNICODE", "BOUNDARY", "INVALID_CODE"},
            {"F. Dates and KYC rules", "DATE_INVALID", "DATE_FORMAT", "DATE_RANGE", "DATE_FUTURE", "DOB_TODAY", "DOB_FUTURE", "DOB_INVALID", "DOB_TOO_OLD"},
            {"G. API hardening", "WRONG_METHOD", "LARGE_PAYLOAD"},
            {"H. Error handling", "SERVER_ERROR", "INTERNAL_LEAK"}
    };

    /** Fixed order = fixed VAPT number. Append new checks at the END only. */
    public static final List<String> ORDER = Arrays.asList(
            "NO_SESSION", "TAMPERED_TOKEN", "TAMPERED_CSRF", "WRONG_PASSWORD",                       // 001-004
            "OTHER_USER",                                                                          // 005
            "INJECTION", "XSS_SCRIPT", "XXE", "MALFORMED_XML",                                     // 006-009
            "TAMPERED_ENCRYPTION", "STATE_CHECK",                                                  // 010-011
            "NOT_EXISTING", "WRONG_TYPE", "MISSING_FIELD", "EMPTY_VALUE", "SPACES_ONLY",
            "SPECIAL_CHARS", "UNICODE", "BOUNDARY", "INVALID_CODE",                                // 012-020
            "DATE_INVALID", "DATE_FORMAT", "DATE_RANGE", "DATE_FUTURE",
            "DOB_TODAY", "DOB_FUTURE", "DOB_INVALID", "DOB_TOO_OLD",                               // 021-028
            "WRONG_METHOD", "LARGE_PAYLOAD",                                                       // 029-030
            "SERVER_ERROR", "INTERNAL_LEAK",                                                       // 031-032
            "PLAIN_BODY");                                                                         // 033

    public static String categoryOf(String key) {
        for (String[] c : CATEGORIES) if (Arrays.asList(c).subList(1, c.length).contains(key)) return c[0];
        return "Other";
    }

    public static final List<String> SEV = Arrays.asList("Critical", "High", "Medium", "Low", "Info");
    private static final Properties TEXT = new Properties();

    static {
        try (InputStream in = VaptModel.class.getResourceAsStream("/rules/vapt-points.properties")) {
            if (in != null) TEXT.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("⚠ VAPT text not loaded: " + e.getMessage());
        }
    }

    public static final class Point {
        public String id, key, category, title, what, impact, fix, owasp, expected, why, status = "NA";
        public int passed, failed, skipped, notApplicable;
        public String severity = "–";
        public final Set<String> endpointsTested = new LinkedHashSet<>();
        public final List<Map<String, String>> failedRows = new ArrayList<>();
        public final List<Map<String, String>> passedRows = new ArrayList<>();
        /** NOT APPLICABLE and INCONCLUSIVE rows (with their reason) */
        public final List<Map<String, String>> otherRows = new ArrayList<>();

        public int tested() { return passed + failed; }
        public String anchor() { return "v-" + id.toLowerCase(); }
    }

    /**
     * One confirmed finding = one security issue, with every API and test that shows it (e.g. "internal error details
     * leaked" on 6 APIs). "No input validation on a field that reaches the database" is one finding, whatever check found it.
     */
    public static final class Finding {
        public String id, title, severity = "Info";
        public Point point;
        public final Set<String> apis = new LinkedHashSet<>();
        public final List<Map<String, String>> rows = new ArrayList<>();
        public String anchor() { return "f-" + id.toLowerCase(); }
    }

    private VaptModel() {}

    public static List<Point> build(List<Map<String, String>> rows) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < ORDER.size(); i++) {
            String k = ORDER.get(i);
            Point p = new Point();
            p.key = k;
            p.category = categoryOf(k);
            p.id = String.format("VAPT-%03d", i + 1);
            p.title = TEXT.getProperty("title." + k, k);
            p.what = TEXT.getProperty("what." + k, "");
            p.impact = TEXT.getProperty("impact." + k, "");
            p.fix = TEXT.getProperty("fix." + k, "");
            ExpectedStatus.Expect ex = ExpectedStatus.of(k);
            p.owasp = ExpectedStatus.owasp(k);
            p.expected = ex == null ? "A controlled rejection (no server error, no data change)" : "HTTP " + ex.text();
            p.why = ex == null ? "" : ex.why;
            pts.add(p);
        }
        boolean serverErrorIsVapt = ConfigManager.getBool("vapt.server.error.as.vapt", false);
        for (Map<String, String> r : rows) {
            String res = r.getOrDefault("Result", "");
            String cls = r.getOrDefault("VAPT_Class", "");
            int http = parseInt(r.get("HTTP"));
            boolean executed = "PASS".equalsIgnoreCase(res) || "FAIL".equalsIgnoreCase(res);
            String own = r.getOrDefault("VAPT_Point", r.getOrDefault("NegCategory", ""));
            if (own.isEmpty()) own = r.getOrDefault("NegCategory", "");

            // 1) the check the negative test belongs to (a leak found by e.g. a BOUNDARY test belongs to INTERNAL_LEAK, below)
            if ("Y".equals(r.get("Negative")) && !"FUNCTIONAL".equals(cls) && !"INTERNAL_LEAK".equals(own)
                    && !"SERVER_ERROR".equals(own)) {
                Point p = find(pts, own);
                if (p != null) {
                    if ("NA".equals(cls) || "INCONCLUSIVE".equals(cls)) {
                        if ("NA".equals(cls)) p.notApplicable++; else p.skipped++;
                        p.otherRows.add(r);
                    } else add(p, r, res);
                }
            }
            // 2) every executed request, positive or negative: leaked internals (field + text), server errors (optional)
            if (executed && http > 0) {
                Point lk = find(pts, "INTERNAL_LEAK");
                boolean leak = !r.getOrDefault("Leak_Evidence", "").isEmpty() && !"INJECTION".equals(own);
                if (leak) addAs(lk, r, "FAIL"); else addAs(lk, r, "PASS");
                if (serverErrorIsVapt) {
                    Point se = find(pts, "SERVER_ERROR");
                    if (http >= 500 && !leak) addAs(se, r, "FAIL"); else addAs(se, r, "PASS");
                }
            }
        }
        for (Point p : pts) {
            p.status = p.failed > 0 ? "FAIL" : p.passed > 0 ? "PASS" : p.skipped > 0 ? "SKIP" : "NA";
            String hi = "–";
            for (Map<String, String> r : p.failedRows) {
                String s = rowSeverity(r, p.key);
                if (SEV.contains(s) && (hi.equals("–") || SEV.indexOf(s) < SEV.indexOf(hi))) hi = s;
            }
            p.severity = hi;
        }
        return pts;
    }

    /** Severity of a failed row: the proven severity from VaptRules; Medium for a leak seen on a positive test. */
    public static String rowSeverity(Map<String, String> r, String pointKey) {
        String s = r.getOrDefault("VAPT_Severity", "");
        if (!s.isEmpty()) return s;
        if ("INTERNAL_LEAK".equals(pointKey)) return "Medium";
        if ("SERVER_ERROR".equals(pointKey)) return "Low";
        return r.getOrDefault("Severity", "Low");
    }

    /** Confirmed findings, most severe first: failed rows grouped by security issue. */
    public static List<Finding> findings(List<Point> pts) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        for (Point p : pts) {
            for (Map<String, String> r : p.failedRows) {
                boolean noValidation = r.getOrDefault("Error", "").startsWith(com.api.automation.negative.VaptRules.NO_VALIDATION);
                String k = noValidation ? "NO_VALIDATION" : p.key;
                Finding f = byKey.computeIfAbsent(k, x -> {
                    Finding n = new Finding();
                    n.point = p;
                    n.title = noValidation ? "No input validation: invalid values reach the database" : p.title;
                    return n;
                });
                f.apis.add(com.api.automation.negative.ApiKind.path(r.getOrDefault("Endpoint", "")));
                f.rows.add(r);
                String s = rowSeverity(r, p.key);
                if (SEV.indexOf(s) >= 0 && SEV.indexOf(s) < SEV.indexOf(f.severity)) f.severity = s;
            }
        }
        List<Finding> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int c = Integer.compare(SEV.indexOf(a.severity), SEV.indexOf(b.severity));
            return c != 0 ? c : Integer.compare(ORDER.indexOf(a.point.key), ORDER.indexOf(b.point.key));
        });
        for (int i = 0; i < out.size(); i++) out.get(i).id = String.format("F-%02d", i + 1);
        return out;
    }

    /** QA defects with no security impact (dates, flags on report filters, server errors without a leak). */
    public static List<Map<String, String>> functional(List<Map<String, String>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : rows) if ("FUNCTIONAL".equals(r.get("VAPT_Class"))) out.add(r);
        return out;
    }

    private static Point find(List<Point> pts, String key) {
        for (Point p : pts) if (p.key.equals(key)) return p;
        return null;
    }

    private static void add(Point p, Map<String, String> r, String res) {
        if ("PASS".equalsIgnoreCase(res)) addAs(p, r, "PASS");
        else if ("FAIL".equalsIgnoreCase(res)) addAs(p, r, "FAIL");
        else if (!"N/A".equalsIgnoreCase(res)) p.skipped++;
    }

    private static void addAs(Point p, Map<String, String> r, String res) {
        if (p == null) return;
        p.endpointsTested.add(r.getOrDefault("TC_ID", "").replaceAll("_[A-Z0-9]+$", ""));
        if ("PASS".equals(res)) {
            p.passed++;
            p.passedRows.add(r);
        } else {
            p.failed++;
            p.failedRows.add(r);
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
