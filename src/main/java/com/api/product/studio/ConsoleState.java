package com.api.product.studio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.api.product.core.engine.HumanGate;
import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Feeds the live console page (console.html) and the saved report with the same state: one run with every planned test
 * (QUEUED / RUNNING / PASS / FAIL / SKIP / NA), the event log, pause state and a pending OTP request.
 * Outcome mapping: PASS, FAIL stay; BLOCKED and SKIPPED become SKIP (could not be sent); a negative that was not run because the valid
 * request of its API did not pass, and NOT_EXECUTED tests, become NA (not executable).
 */
public final class ConsoleState {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, String> CATEGORY = new HashMap<>();

    static {
        CATEGORY.put("INVALID_REQUEST_ACCEPTED", "NEGATIVE CASE ACCEPTED");
        CATEGORY.put("VALIDATION_MISSING", "VALIDATION MISSING");
        CATEGORY.put("SERVER_ERROR_ON_BAD_INPUT", "SERVER ERROR ON BAD INPUT");
        CATEGORY.put("INFORMATION_LEAK", "INFORMATION LEAK");
        CATEGORY.put("UNEXPECTED_STATUS", "UNEXPECTED HTTP STATUS");
        CATEGORY.put("POSITIVE_NOT_PASSING", "NOT NEEDED");
        CATEGORY.put("AUTH_NOT_CONFIGURED", "NO SIGN-IN CONFIGURED");
        CATEGORY.put("ENVIRONMENT_OR_DATA", "NO DATA FROM PREVIOUS STEP");
        CATEGORY.put("NOT_NEEDED", "NOT NEEDED");
        CATEGORY.put("LOCKOUT_GUARD", "ACCOUNT PROTECTED");
        CATEGORY.put("CONTRACT_VIOLATION", "CONTRACT VIOLATION");
        CATEGORY.put("STATE_CHANGED_BY_NEGATIVES", "STATE CHANGED BY NEGATIVES");
    }

    private ConsoleState() {}

    public static Map<String, Object> state(RunService.Run run) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("serverTime", System.currentTimeMillis());
        s.put("runs", List.of(runView(run)));
        s.put("currentRunId", run.id);
        HumanGate.Pending p = HumanGate.pending();
        if (p != null && p.project.equals(run.project)) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", p.testId);
            req.put("project", p.project);
            req.put("tcId", p.testId);
            req.put("apiName", p.api);
            req.put("endpoint", p.url);
            req.put("where", "browser");
            req.put("timeoutSeconds", 300);
            req.put("defaultOtp", "none");
            req.put("targetLabel", "the phone or e-mail registered for the account");
            req.put("serverMessage", "Studio asks for the OTP only when the login says it sent one.");
            Map<String, Object> otp = new LinkedHashMap<>();
            otp.put("request", req);
            otp.put("remainingMs", Math.max(1000, 300_000 - (System.currentTimeMillis() - p.since)));
            otp.put("expired", false);
            s.put("otp", otp);
        }
        return s;
    }

    private static Map<String, Object> runView(RunService.Run run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", run.id);
        r.put("project", run.project);
        r.put("displayName", run.project);
        r.put("env", run.envName);
        r.put("baseUrl", run.baseUrl);
        r.put("status", "RUNNING".equals(run.state) ? "RUNNING" : "COMPLETED");
        r.put("startedAt", run.startedAt);
        r.put("finishedAt", run.finishedAt);
        r.put("currentTestId", run.currentTestId);
        r.put("testedBy", System.getProperty("user.name", "tester"));
        r.put("paused", run.paused);
        r.put("pausedAt", run.pausedAt);
        r.put("pausedTotalMs", run.pausedTotalMs + (run.paused ? System.currentTimeMillis() - run.pausedAt : 0));
        r.put("recEnabled", false);
        r.put("recState", "OFF");
        if (run.htmlUrl != null) {
            r.put("htmlReportUrl", run.consoleReportUrl != null ? run.consoleReportUrl : run.htmlUrl);
            r.put("excelReportUrl", run.excelUrl);
            r.put("vaptPdfUrl", run.vaptPdfUrl);
            r.put("hubUrl", "/app");
            r.put("htmlReport", fileOf(run.consoleReportUrl != null ? run.consoleReportUrl : run.htmlUrl));
            r.put("excelReport", fileOf(run.excelUrl));
            r.put("vaptPdf", fileOf(run.vaptPdfUrl));
        }
        r.put("tests", tests(run));
        r.put("events", new ArrayList<>(run.events));
        return r;
    }

    /** "/files/Proj/x.html" -> "reports/Proj/x.html" (the page shows the file name and uses it for the saved copy). */
    static String fileOf(String url) {
        return url == null ? null : "reports/" + url.replaceFirst("^/files/", "");
    }

    private static List<Map<String, Object>> tests(RunService.Run run) {
        Map<String, TestResult> done = new HashMap<>();
        synchronized (run.live) {
            for (TestResult t : run.live) done.put(t.testCaseId, t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestCase tc : run.plan) {
            TestResult res = done.get(tc.id);
            out.add(res != null ? view(res, tc, false) : queued(run, tc));
        }
        return out;
    }

    private static Map<String, Object> queued(RunService.Run run, TestCase tc) {
        Map<String, Object> t = base(tc, run.model);
        t.put("status", tc.id.equals(run.currentTestId) && "RUNNING".equals(run.state) ? "RUNNING" : "QUEUED");
        return t;
    }

    private static Map<String, Object> base(TestCase tc, ApiModel model) {
        Map<String, Object> t = new LinkedHashMap<>();
        ApiModel.Endpoint ep = model == null ? null : model.endpoint(tc.endpointId);
        t.put("id", tc.id);
        t.put("tcId", tc.id);
        t.put("apiName", ep == null || ep.name == null ? tc.endpointId : ep.name);
        t.put("module", ep == null || ep.module == null ? "General" : ep.module);
        t.put("pageName", ep == null || ep.module == null ? "General" : ep.module);
        t.put("method", ep == null ? "" : ep.method);
        t.put("endpoint", ep == null ? "" : ep.path);
        t.put("apiPath", ep == null ? "" : ep.path);
        t.put("label", tc.title);
        t.put("negative", !"POSITIVE".equals(tc.category));
        return t;
    }

    static Map<String, Object> view(TestResult r, TestCase tc, boolean full) {
        Map<String, Object> t = base(tc, null);
        t.put("apiName", r.apiName == null ? t.get("apiName") : r.apiName);
        t.put("module", r.module == null ? "General" : r.module);
        t.put("pageName", r.module == null ? "General" : r.module);
        t.put("method", r.method == null ? "" : r.method);
        t.put("endpoint", r.url == null ? "" : r.url);
        t.put("baseUrl", r.baseUrl == null ? "" : r.baseUrl);
        String path = r.url == null ? "" : r.url;
        if (r.baseUrl != null && path.startsWith(r.baseUrl)) path = path.substring(r.baseUrl.length());
        t.put("apiPath", path);
        t.put("label", r.title == null ? tc.title : r.title);
        t.put("negative", !"POSITIVE".equals(r.category));
        String status = status(r);
        t.put("status", status);
        t.put("httpStatus", r.httpStatus);
        t.put("timeMs", r.latencyMs);
        t.put("slow", r.latencyMs > 5000);
        List<Integer> exp = r.expectedStatus == null ? List.of() : r.expectedStatus;
        String expText = exp.isEmpty() ? "" : String.join(" or ", exp.stream().map(String::valueOf).toList());
        t.put("expectedHttp", expText);
        boolean match = r.httpStatus != null && exp.contains(r.httpStatus);
        t.put("httpCheck", r.httpStatus == null ? "" : match ? "OK" : "MISMATCH");
        t.put("vapt", r.owasp == null || "-".equals(r.owasp) ? "" : r.owasp);
        String remark = r.remark == null ? "" : r.remark;
        t.put("remark", remark);
        t.put("reason", remark);
        t.put("category", category(r));
        t.put("expected", expText.isEmpty() ? "" : "HTTP " + expText);
        t.put("actual", r.httpStatus == null ? "no response" : "HTTP " + r.httpStatus);
        t.put("action", action(r, status));
        t.put("request", r.requestEvidence == null ? "" : r.requestEvidence);
        t.put("requestHeaders", "");
        t.put("response", r.responseEvidence == null ? "" : r.responseEvidence);
        t.put("encrypted", false);
        t.put("executedAt", r.startedAt == null ? "" : r.startedAt.replace('T', ' ').replaceAll("\\..*", ""));
        t.put("format", "JSON");
        t.put("dataSource", "");
        t.put("testData", "");
        t.put("checks", new ArrayList<>());
        return t;
    }

    static String status(TestResult r) {
        switch (r.outcome == null ? "" : r.outcome) {
            case "PASS": return "PASS";
            case "FAIL": return "FAIL";
            case "BLOCKED":
                return "POSITIVE_NOT_PASSING".equals(r.failureCategory) || "LOCKOUT_GUARD".equals(r.failureCategory) ? "NA" : "SKIP";
            case "SKIPPED": return "SKIP";
            default: return "NA";
        }
    }

    private static String category(TestResult r) {
        if (r.failureCategory == null || r.failureCategory.isBlank()) return "";
        String c = CATEGORY.get(r.failureCategory);
        return c != null ? c : r.failureCategory.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static String action(TestResult r, String status) {
        if ("NA".equals(status)) return "Fix the valid request of this API first; its negative checks then run automatically.";
        if ("SKIP".equals(status)) return r.remark != null && r.remark.toLowerCase(Locale.ROOT).contains("credentials") ? "Enter the credentials (Overview, Set credentials) and run again." : "See the reason; it runs automatically once what it needs is available.";
        if ("FAIL".equals(status)) return "Open the request and response, then fix the API or correct the test.";
        return "";
    }

    // ------------------------------------------------------------------ page

    private static String template;

    static synchronized String template() throws IOException {
        if (template == null) {
            try (InputStream in = ConsoleState.class.getResourceAsStream("/studio/console.html")) {
                if (in == null) throw new IOException("console page not found in this build");
                template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return template;
    }

    /** The live page: polls the Studio API for the run given in the query string. */
    public static String livePage() throws IOException {
        return brand(template().replace("__PAGE_VERSION__", "studio").replace("'/api/state'", "('/api/console/state'+location.search)")
                .replace("'/api/control'", "('/api/console/control'+location.search)"));
    }

    private static String brand(String html) {
        return html.replace("API Automation Console", "API Quality Studio").replace("Banking API test execution · live", "Test execution · live");
    }

    /** The saved report: the same page with the final state embedded, no server needed. */
    public static String staticPage(RunService.Run run) throws IOException {
        Map<String, Object> st = state(run);
        st.put("static", true);
        st.remove("otp");
        String json = M.writeValueAsString(st).replace("</", "<\\/");
        return brand(template().replace("__PAGE_VERSION__", "static").replace("<!--STATIC_STATE-->", "<script>window.__STATIC_STATE__ = " + json + ";</script>"));
    }
}
