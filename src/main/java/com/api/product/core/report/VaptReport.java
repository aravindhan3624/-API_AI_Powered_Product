package com.api.product.core.report;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.api.product.core.engine.TestResult;

/**
 * Vulnerability-assessment (VAPT-style) view of a run: security-relevant failures grouped into findings with OWASP API Top 10
 * (2023) and CWE references, severity, sanitized evidence and remediation, plus the controls that held and the limits of the
 * test. Findings are automated indicators that need confirmation; they are never claimed from an HTTP status alone.
 */
public final class VaptReport {

    public static final class Info {
        public final String title;
        public final String owasp;
        public final String cwe;
        public final String severity;
        public final String description;
        public final String impact;
        public final String fix;

        Info(String title, String owasp, String cwe, String severity, String description, String impact, String fix) {
            this.title = title;
            this.owasp = owasp;
            this.cwe = cwe;
            this.severity = severity;
            this.description = description;
            this.impact = impact;
            this.fix = fix;
        }
    }

    public static final class Finding {
        public String id;
        public String key;
        public Info info;
        public String severity;
        public final List<String> affected = new ArrayList<>();
        public final Set<String> tests = new LinkedHashSet<>();
        public int count;
        public String evidenceRequest = "";
        public String evidenceResponse = "";
        public String status = "Open";
    }

    public static final class Report {
        public final List<Finding> findings = new ArrayList<>();
        public final Map<String, Integer> controlsHeld = new TreeMap<>();
        public int securityTests;
        public int blocked;
        public int notExecuted;
        public int total;
    }

    private static final Map<String, Info> CATALOG = new LinkedHashMap<>();
    private static final Set<String> SECURITY = Set.of("NO_SESSION", "TAMPERED_TOKEN", "TAMPERED_CSRF", "OTHER_USER",
            "TAMPERED_ENCRYPTION", "LARGE_PAYLOAD", "WRONG_METHOD", "XXE", "MALFORMED_XML", "WRONG_PASSWORD");

    static {
        add("NO_SESSION", "Authentication not enforced", "API2:2023 Broken Authentication", "CWE-306", "critical",
                "The endpoint processed a request that carried no session cookie, token or CSRF value.",
                "Anyone who can reach the API can call this function without logging in.",
                "Require and validate a session or token on every protected endpoint (deny by default) and answer 401.");
        add("TAMPERED_TOKEN", "Forged or modified token accepted", "API2:2023 Broken Authentication", "CWE-347", "critical",
                "A token whose last characters were changed (invalid signature) was accepted.",
                "Attackers can forge identities if the signature is not verified.",
                "Verify the token signature, expiry and audience on every request; reject with 401.");
        add("TAMPERED_CSRF", "Anti-CSRF protection missing or not enforced", "OWASP A01:2021 Broken Access Control", "CWE-352", "high",
                "A state-changing request with a wrong CSRF token was accepted.",
                "A malicious site can make a logged-in user's browser perform actions.",
                "Validate the anti-CSRF token on every state-changing method and refuse with 403; use SameSite cookies.");
        add("OTHER_USER", "Broken object level authorization (IDOR)", "API1:2023 Broken Object Level Authorization", "CWE-639", "critical",
                "A request naming another user's identifier was accepted for the logged-in session.",
                "Users can read or change other users' data.", "Authorise every object access on the server using the session identity, never the client-supplied id.");
        add("TAMPERED_ENCRYPTION", "Tampered encrypted request accepted", "API8:2023 Security Misconfiguration", "CWE-345", "high",
                "A request whose wrapped encryption key was corrupted was not refused.",
                "Message integrity is not enforced, so the encrypted channel cannot be trusted.",
                "Refuse any request that cannot be authenticated and decrypted (400/401) and never fall back to plain handling.");
        add("LARGE_PAYLOAD", "No request size limit", "API4:2023 Unrestricted Resource Consumption", "CWE-770", "medium",
                "A body of about 1 MB was accepted.", "Large bodies can exhaust memory or CPU (denial of service).",
                "Enforce maximum request sizes at the gateway and application and answer 413.");
        add("WRONG_METHOD", "Unexpected HTTP method accepted", "API8:2023 Security Misconfiguration", "CWE-749", "low",
                "The endpoint answered a method it is not documented to support.", "Enlarges the attack surface and can bypass method-based controls.",
                "Allow only the documented methods and answer 405 for others.");
        add("XXE", "XML external entity processing", "OWASP A05:2021 Security Misconfiguration", "CWE-611", "high",
                "An XML document declaring an external entity was not rejected.", "May allow reading server files or server-side requests.",
                "Disable DTD and external entity processing in the XML parser.");
        add("MALFORMED_XML", "Malformed XML accepted", "OWASP A05:2021 Security Misconfiguration", "CWE-20", "low",
                "A document that is not well-formed XML was processed.", "Lenient parsing hides errors and weakens validation.", "Reject malformed XML with 400.");
        add("WRONG_PASSWORD", "Wrong credentials accepted", "API2:2023 Broken Authentication", "CWE-287", "critical",
                "A login with an incorrect password succeeded.", "Account takeover.", "Verify credentials strictly and add lockout / rate limiting.");
        add("INFORMATION_LEAK", "Verbose errors expose internals", "API8:2023 Security Misconfiguration", "CWE-209", "high",
                "Error responses contained exception, SQL or stack-trace text.", "Reveals technology and structure that helps targeted attacks.",
                "Return generic error messages to clients and log details on the server only.");
        add("SERVER_ERROR_ON_BAD_INPUT", "Server errors on malformed input", "API8:2023 Security Misconfiguration", "CWE-20", "medium",
                "Invalid input produced HTTP 5xx instead of a validation error.", "Indicates missing input validation and may enable denial of service or reveal internals.",
                "Validate input early and answer with 4xx; handle unexpected types without crashing.");
        add("REFLECTED_INPUT", "Script content reflected unescaped", "OWASP A03:2021 Injection (XSS)", "CWE-79", "medium",
                "A script-tag marker was returned in the response without escaping.", "Cross-site scripting if any client renders the field as HTML.",
                "Encode output for its context and reject markup where it is not expected.");
        add("VALIDATION_MISSING", "Missing server-side input validation", "API3:2023 / OWASP ASVS V5", "CWE-20", "medium",
                "Invalid values (wrong type, empty, too long, injection markers, impossible dates) were accepted.", "Bad data reaches business logic and storage; raises the risk of injection and integrity problems.",
                "Validate type, length, format and range on the server for every field and answer 400/422.");
        add("STATE_CHANGED_BY_NEGATIVES", "Invalid requests change session or data state", "API6:2023 Unrestricted Access to Sensitive Business Flows", "CWE-840", "high",
                "A valid request failed after the negative tests ran.", "Invalid input can break sessions or data for legitimate users.",
                "Make failed or invalid requests side-effect free and re-check session handling.");
    }

    private static void add(String key, String title, String owasp, String cwe, String sev, String desc, String impact, String fix) {
        CATALOG.put(key, new Info(title, owasp, cwe, sev, desc, impact, fix));
    }

    private VaptReport() {}

    public static Report build(List<TestResult> results) {
        Report rep = new Report();
        Map<String, Finding> by = new LinkedHashMap<>();
        for (TestResult r : results) {
            rep.total++;
            if ("BLOCKED".equals(r.outcome)) rep.blocked++;
            if ("NOT_EXECUTED".equals(r.outcome)) rep.notExecuted++;
            if (r.category != null && SECURITY.contains(r.category) && ("PASS".equals(r.outcome) || "FAIL".equals(r.outcome))) rep.securityTests++;
            if ("PASS".equals(r.outcome) && r.category != null && SECURITY.contains(r.category)) rep.controlsHeld.merge(r.category, 1, Integer::sum);
            if (!"FAIL".equals(r.outcome)) continue;
            String key = keyOf(r);
            if (key == null) continue;
            Finding f = by.computeIfAbsent(key, k -> {
                Finding n = new Finding();
                n.key = k;
                n.info = CATALOG.get(k);
                n.severity = n.info.severity;
                n.evidenceRequest = trim(r.requestEvidence, 700);
                n.evidenceResponse = trim(r.responseEvidence, 700);
                return n;
            });
            f.count++;
            f.tests.add(r.testCaseId);
            String ep = (r.method == null ? "" : r.method + " ") + pathOf(r.url);
            if (!f.affected.contains(ep)) f.affected.add(ep);
            if (rank(r.severity) > rank(f.severity)) f.severity = r.severity;
        }
        List<Finding> list = new ArrayList<>(by.values());
        list.sort(Comparator.comparingInt((Finding f) -> -rank(f.severity)).thenComparing(f -> f.info.title));
        int n = 1;
        for (Finding f : list) f.id = String.format("VAPT-%03d", n++);
        rep.findings.addAll(list);
        return rep;
    }

    private static String keyOf(TestResult r) {
        String fc = r.failureCategory == null ? "" : r.failureCategory;
        switch (fc) {
            case "INFORMATION_LEAK":
            case "SERVER_ERROR_ON_BAD_INPUT":
            case "REFLECTED_INPUT":
            case "STATE_CHANGED_BY_NEGATIVES":
                return fc;
            case "INVALID_REQUEST_ACCEPTED":
            case "VALIDATION_MISSING":
                return r.category != null && CATALOG.containsKey(r.category) && SECURITY.contains(r.category) ? r.category : "VALIDATION_MISSING";
            default:
                return null;
        }
    }

    public static int rank(String s) {
        if (s == null) return 0;
        switch (s.toLowerCase()) {
            case "critical": return 4;
            case "high": return 3;
            case "medium": return 2;
            case "low": return 1;
            default: return 0;
        }
    }

    private static String pathOf(String url) {
        if (url == null) return "";
        try {
            URI u = URI.create(url);
            return u.getPath() == null ? url : u.getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    public static Map<String, Integer> bySeverity(Report r) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : new String[] { "critical", "high", "medium", "low" }) m.put(s, 0);
        for (Finding f : r.findings) m.merge(f.severity.toLowerCase(), 1, Integer::sum);
        return m;
    }

    private static final String[] LIMITS = {
            "Results are automated indicators. Each finding needs manual confirmation before it is reported as a vulnerability; an HTTP status alone is never treated as proof.",
            "Object-level authorization (other users' data) needs a second test user; it was not tested unless such a user was configured.",
            "Business-logic flaws, multi-step abuse, role escalation and rate-limit behaviour under load are outside this automated pass.",
            "Injection checks use safe markers only; no exploitation, data extraction or destructive payloads were attempted.",
            "Create/update/delete endpoints receive only security checks by default to avoid creating junk records; data-validation checks there are opt-in.",
            "Login endpoints are tested with a single wrong-password attempt to avoid locking accounts."
    };

    // ------------------------------------------------------------------ output

    public static void writePdf(Path file, ReportGenerator.Meta meta, String started, String ended, Report rep) throws IOException {
        try (PdfBuilder pdf = new PdfBuilder("Security assessment - " + meta.project + " - run " + meta.runId)) {
            pdf.title("Security Assessment Report", meta.project + "  |  " + (meta.environment == null ? "" : meta.environment) + "  |  OWASP API Security Top 10 (2023)");
            pdf.h1("1. Scope and method");
            pdf.table(new String[] { "Item", "Value" }, new float[] { 0.25f, 0.75f }, List.of(
                    new String[] { "Target (base URL)", String.valueOf(meta.baseUrl) },
                    new String[] { "Environment", String.valueOf(meta.environment) },
                    new String[] { "Source of the API definition", String.valueOf(meta.source) },
                    new String[] { "Executed", started + "  to  " + ended },
                    new String[] { "Tester / run id", meta.tester + "  /  " + meta.runId },
                    new String[] { "Rule pack", String.valueOf(meta.ruleVersion) }));
            pdf.p("Method: every security test changes one thing of a valid (positive) request - session, token, CSRF value, encryption key, request size, HTTP method, object identifier or a field value - and checks that the API refuses it cleanly. "
                    + "Findings are mapped to the OWASP API Security Top 10 (2023) and CWE. Testing was automated, rate-limited and non-destructive.");
            pdf.h1("2. Summary");
            Map<String, Integer> sev = bySeverity(rep);
            pdf.table(new String[] { "Critical", "High", "Medium", "Low", "Security tests run", "Blocked / not executed" }, new float[] { 0.14f, 0.14f, 0.14f, 0.14f, 0.2f, 0.24f },
                    java.util.Collections.singletonList(new String[] { String.valueOf(sev.get("critical")), String.valueOf(sev.get("high")), String.valueOf(sev.get("medium")), String.valueOf(sev.get("low")),
                            String.valueOf(rep.securityTests), rep.blocked + " / " + rep.notExecuted }));
            if (rep.findings.isEmpty()) {
                pdf.p("No automated finding was raised by the executed tests. This does not prove the absence of vulnerabilities (see limitations).");
            }
            pdf.table(new String[] { "ID", "Finding", "Severity", "OWASP", "CWE", "Endpoints" }, new float[] { 0.1f, 0.28f, 0.1f, 0.26f, 0.14f, 0.12f },
                    rep.findings.stream().map(f -> new String[] { f.id, f.info.title, f.severity, f.info.owasp, f.info.cwe, String.valueOf(f.affected.size()) }).collect(java.util.stream.Collectors.toList()));
            pdf.h1("3. Findings");
            for (Finding f : rep.findings) {
                pdf.h2(f.id + "  " + f.info.title + "   [" + f.severity.toUpperCase() + "]");
                pdf.p("Reference: " + f.info.owasp + "  |  " + f.info.cwe + "  |  Status: " + f.status + "  |  Failed checks: " + f.count + " (" + String.join(", ", f.tests.stream().limit(8).toList()) + (f.tests.size() > 8 ? ", ..." : "") + ")");
                pdf.p("Description: " + f.info.description);
                pdf.p("Impact: " + f.info.impact);
                pdf.p("Affected: " + String.join(";  ", f.affected.stream().limit(12).toList()) + (f.affected.size() > 12 ? "  ... +" + (f.affected.size() - 12) + " more" : ""));
                if (!f.evidenceRequest.isBlank()) pdf.p("Evidence (sanitized) - request: " + f.evidenceRequest.replace('\n', ' '));
                if (!f.evidenceResponse.isBlank()) pdf.p("Evidence (sanitized) - response: " + f.evidenceResponse.replace('\n', ' '));
                pdf.p("Recommendation: " + f.info.fix);
                pdf.spacer(4);
            }
            pdf.h1("4. Controls that held");
            if (rep.controlsHeld.isEmpty()) {
                pdf.p("No security check passed in this run.");
            } else {
                List<String[]> rows = new ArrayList<>();
                rep.controlsHeld.forEach((k, v) -> rows.add(new String[] { CATALOG.containsKey(k) ? CATALOG.get(k).title.replace("accepted", "refused") : k, k, String.valueOf(v) }));
                pdf.table(new String[] { "Control", "Check", "Endpoints that refused correctly" }, new float[] { 0.5f, 0.28f, 0.22f }, rows);
            }
            pdf.h1("5. Limitations and next steps");
            for (String l : LIMITS) pdf.bullet(l);
            pdf.save(file);
        }
    }

    public static void writeHtml(Path file, ReportGenerator.Meta meta, String started, String ended, Report rep) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Security assessment - ").append(esc(meta.project)).append("</title><style>")
                .append("body{font:14px/1.5 Segoe UI,Arial,sans-serif;margin:0;background:#f4f6fb;color:#1b2438}header{background:#14284b;color:#fff;padding:18px 28px}main{max-width:1000px;margin:auto;padding:20px 28px}")
                .append("h1{margin:0;font-size:22px}h2{margin-top:28px;border-bottom:1px solid #d8deec;padding-bottom:4px}.f{background:#fff;border:1px solid #e1e6f0;border-radius:10px;padding:12px 16px;margin:12px 0}")
                .append(".s{display:inline-block;padding:2px 10px;border-radius:999px;color:#fff;font-size:12px;font-weight:700}.critical{background:#a4161a}.high{background:#d3322c}.medium{background:#c47a0a}.low{background:#2f5bd8}")
                .append("table{border-collapse:collapse;width:100%;background:#fff}td,th{border:1px solid #e1e6f0;padding:6px 10px;text-align:left;vertical-align:top}th{background:#eef1f8}pre{background:#f4f6fb;padding:8px;white-space:pre-wrap;word-break:break-all;font-size:12px}")
                .append("@media print{body{background:#fff}.f{break-inside:avoid}}</style></head><body><header><h1>Security Assessment Report</h1><div>")
                .append(esc(meta.project)).append(" | ").append(esc(String.valueOf(meta.environment))).append(" | OWASP API Security Top 10 (2023)</div></header><main>");
        h.append("<h2>Scope</h2><table><tr><th>Target</th><td>").append(esc(String.valueOf(meta.baseUrl))).append("</td></tr><tr><th>Executed</th><td>").append(esc(started)).append(" to ").append(esc(ended))
                .append("</td></tr><tr><th>Tester / run</th><td>").append(esc(meta.tester)).append(" / ").append(esc(meta.runId)).append("</td></tr><tr><th>Source</th><td>").append(esc(String.valueOf(meta.source))).append("</td></tr></table>");
        Map<String, Integer> sev = bySeverity(rep);
        h.append("<h2>Summary</h2><p>");
        sev.forEach((k, v) -> h.append("<span class=\"s ").append(k).append("\">").append(k).append(" ").append(v).append("</span> "));
        h.append(" &nbsp; Security tests run: <b>").append(rep.securityTests).append("</b> &nbsp; Blocked / not executed: <b>").append(rep.blocked).append(" / ").append(rep.notExecuted).append("</b></p>");
        h.append("<table><tr><th>ID</th><th>Finding</th><th>Severity</th><th>OWASP</th><th>CWE</th><th>Endpoints</th></tr>");
        for (Finding f : rep.findings) {
            h.append("<tr><td>").append(f.id).append("</td><td>").append(esc(f.info.title)).append("</td><td><span class=\"s ").append(f.severity.toLowerCase()).append("\">").append(f.severity).append("</span></td><td>")
                    .append(esc(f.info.owasp)).append("</td><td>").append(f.info.cwe).append("</td><td>").append(f.affected.size()).append("</td></tr>");
        }
        h.append("</table>");
        if (rep.findings.isEmpty()) h.append("<p>No automated finding was raised by the executed tests. This does not prove the absence of vulnerabilities.</p>");
        h.append("<h2>Findings</h2>");
        for (Finding f : rep.findings) {
            h.append("<div class=\"f\"><b>").append(f.id).append(" - ").append(esc(f.info.title)).append("</b> <span class=\"s ").append(f.severity.toLowerCase()).append("\">").append(f.severity).append("</span>")
                    .append("<p>").append(esc(f.info.owasp)).append(" | ").append(f.info.cwe).append(" | Status: ").append(f.status).append(" | failed checks: ").append(f.count).append("</p>")
                    .append("<p><b>Description.</b> ").append(esc(f.info.description)).append("</p><p><b>Impact.</b> ").append(esc(f.info.impact)).append("</p><p><b>Affected.</b> ")
                    .append(esc(String.join("; ", f.affected.stream().limit(25).toList()))).append("</p>");
            if (!f.evidenceRequest.isBlank()) h.append("<p><b>Evidence - request (sanitized)</b></p><pre>").append(esc(f.evidenceRequest)).append("</pre>");
            if (!f.evidenceResponse.isBlank()) h.append("<p><b>Evidence - response (sanitized)</b></p><pre>").append(esc(f.evidenceResponse)).append("</pre>");
            h.append("<p><b>Recommendation.</b> ").append(esc(f.info.fix)).append("</p></div>");
        }
        h.append("<h2>Controls that held</h2><table><tr><th>Check</th><th>Endpoints that refused correctly</th></tr>");
        rep.controlsHeld.forEach((k, v) -> h.append("<tr><td>").append(esc(k)).append("</td><td>").append(v).append("</td></tr>"));
        h.append("</table><h2>Limitations</h2><ul>");
        for (String l : LIMITS) h.append("<li>").append(esc(l)).append("</li>");
        h.append("</ul></main></body></html>");
        Files.writeString(file, h.toString(), StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
