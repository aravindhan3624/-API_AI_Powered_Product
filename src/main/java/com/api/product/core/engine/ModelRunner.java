package com.api.product.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
import com.api.product.core.model.ApiModel.AuthProfile;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.Extract;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Headless, model-driven execution (Phase 1). No project-specific Java, no UI, no static run state:
 * one ModelRunner instance == one run with its own variable bag.
 */
public final class ModelRunner {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern LEAK = Pattern.compile("(?i)(stack ?trace|exception in thread|java\\.lang\\.|org\\.hibernate|sqlexception|ORA-\\d{5}|syntax error at or near|mysql_fetch|npgsql|System\\.Data\\.|at [a-z0-9_.$]+\\([A-Za-z0-9_]+\\.java:\\d+\\))");
    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern JSON_VAR = Pattern.compile("\"\\$\\{json:([A-Za-z0-9_]+)}\"");

    private final ApiModel model;
    private final RulePack rules;
    private final Environment env;
    private final boolean includeDraft;
    private final Map<String, String> vars = new LinkedHashMap<>();
    private final String runId;
    private HybridEnvelope envelope;
    private final Map<String, Integer> loginNegatives = new LinkedHashMap<>();
    private final Map<String, TestResult> positiveByEndpoint = new LinkedHashMap<>();
    /** Browser-like session: cookies are kept between calls of one run (like axios withCredentials). */
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, String> loginState = new LinkedHashMap<>(); // profileId -> OK | error text

    public ModelRunner(ApiModel model, RulePack rules, String envName, boolean includeDraft) {
        this.model = model;
        this.rules = rules;
        this.includeDraft = includeDraft;
        this.env = pickEnv(model, envName);
        String baseOverride = System.getProperty("run.baseurl");   // CLI / CI: test another environment without editing the project
        if (baseOverride != null && !baseOverride.isBlank()) this.env.baseUrl = baseOverride.trim();
        this.runId = "RUN-" + System.currentTimeMillis();
        vars.putAll(env.variables);
        initEnvelope();
        // run-time overrides: -Dvar.apiKey=... (keeps secrets out of model files)
        System.getProperties().forEach((k, v) -> {
            if (k.toString().startsWith("var.")) vars.put(k.toString().substring(4), String.valueOf(v));
        });
    }

    private void initEnvelope() {
        ApiModel.Envelope e = model.envelope;
        if (e == null || !"hybrid-rsa-aes-gcm".equals(e.type)) return;
        try {
            envelope = new HybridEnvelope(resolve(e.serverPublicKey));
        } catch (Exception ex) {
            throw new IllegalStateException("Envelope server public key is invalid: " + ex.getMessage(), ex);
        }
    }

    public String runId() {
        return runId;
    }

    public String environmentName() {
        return env.name;
    }

    public String baseUrl() {
        return resolve(env.baseUrl);
    }

    public String ruleVersion() {
        return rules.version;
    }

    public List<TestResult> run() {
        return run(null, null, null);
    }

    /**
     * @param onlyIds   run only these test ids (null = all)
     * @param onResult  called after every test (live progress)
     * @param cancelled polled before every test; true stops the run
     */
    public List<TestResult> run(java.util.Set<String> onlyIds, java.util.function.Consumer<TestResult> onResult,
            java.util.function.BooleanSupplier cancelled) {
        return run(onlyIds, onResult, cancelled, null, null);
    }

    /**
     * @param onStart called just before a test is sent (live console: "now executing")
     * @param paused  while true the run waits between two tests (Pause / Resume on the live console)
     */
    public List<TestResult> run(java.util.Set<String> onlyIds, java.util.function.Consumer<TestResult> onResult,
            java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<TestCase> onStart, java.util.function.BooleanSupplier paused) {
        List<TestResult> results = new ArrayList<>();
        List<TestCase> plan = new ArrayList<>();
        for (TestCase tc : model.testCases) {
            if (onlyIds == null || onlyIds.contains(tc.id)) plan.add(tc);
        }
        // data chaining: setup calls first, cleanup (DELETE of what was created) last; stable, so nothing else moves
        if (!model.links.isEmpty()) plan.sort(java.util.Comparator.comparingInt(t -> com.api.product.core.flow.AutoChain.rank(model, t)));
        for (int i = 0; i < plan.size(); i++) {
            TestCase tc = plan.get(i);
            if (cancelled != null && cancelled.getAsBoolean()) break;
            while (paused != null && paused.getAsBoolean() && !(cancelled != null && cancelled.getAsBoolean())) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (cancelled != null && cancelled.getAsBoolean()) break;
            if (onStart != null) onStart.accept(tc);
            boolean runnable = "ACTIVE".equals(tc.lifecycle) || "APPROVED".equals(tc.lifecycle)
                    || (includeDraft && ("DRAFT".equals(tc.lifecycle) || "REVIEWED".equals(tc.lifecycle)));
            // lockout guard: failed logins can lock a shared test account, so negatives on a login endpoint are capped per run
            boolean guarded = false;
            if (runnable && !"POSITIVE".equals(tc.category) && isLoginEndpoint(model.endpoint(tc.endpointId))
                    && loginNegatives.merge(tc.endpointId, 1, Integer::sum) > Integer.getInteger("lockout.max", 4)) {
                guarded = true;
            }
            if (guarded) {
                TestResult g = base(tc);
                g.outcome = "BLOCKED";
                g.failureCategory = "LOCKOUT_GUARD";
                g.remark = "Skipped on purpose: more than " + Integer.getInteger("lockout.max", 4)
                        + " negative attempts on a login endpoint per run could lock the test account (raise with -Dlockout.max=N)";
                results.add(g);
                if (onResult != null) onResult.accept(g);
                continue;
            }
            String gate = runnable ? gateReason(tc) : null;
            TestResult res = gate != null ? gated(tc, gate) : runnable ? execute(tc) : notExecuted(tc, tc.tags.contains("run-flag-N")
                    ? "Run=N in the source test data"
                    : tc.tags.contains("unsupported-body")
                    ? "Request body type (multipart/form-data or binary) is not supported by the runner yet"
                    : "lifecycle=" + tc.lifecycle);
            results.add(res);
            if ("POSITIVE".equals(tc.category) && tc.endpointId != null) positiveByEndpoint.put(tc.endpointId, res);
            if (onResult != null) onResult.accept(res);
            // negatives on a login endpoint can end or change the session: log in again before the next endpoint
            // (same idea as the legacy engine's restoreSession) so later tests are judged on a live session
            if (runnable && !"POSITIVE".equals(tc.category) && isLoginEndpoint(model.endpoint(tc.endpointId))
                    && (i + 1 >= plan.size() || !tc.endpointId.equals(plan.get(i + 1).endpointId))) {
                TestCase relogin = new TestCase();
                relogin.id = "RELOGIN_" + tc.endpointId;
                relogin.endpointId = tc.endpointId;
                relogin.category = "POSITIVE";
                execute(relogin);
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ execution

    private TestResult execute(TestCase tc) {
        TestResult r = base(tc);
        Endpoint ep = model.endpoint(tc.endpointId);
        if (ep == null) return blocked(r, "Unknown endpointId " + tc.endpointId);

        Mutation mu = tc.mutation;
        String method = mu != null && mu.method != null ? mu.method : ep.method;
        String path = mu != null && mu.path != null ? mu.path : ep.path;

        ep.set.forEach((k, v) -> vars.put(k, resolve(v)));

        Map<String, String> headers = new LinkedHashMap<>();
        ep.headers.forEach((k, v) -> headers.put(k, resolve(v)));
        if (ep.contentType != null) headers.put("Content-Type", ep.contentType);
        Map<String, String> authQuery = new LinkedHashMap<>();
        String authProblem = applyAuth(ep, headers, authQuery);
        if (authProblem != null) return blocked(r, authProblem);
        if (mu != null) {
            mu.removeQuery.forEach(authQuery.keySet()::remove);
            mu.removeHeaders.forEach(h -> headers.keySet().removeIf(k -> k.equalsIgnoreCase(h)));
            mu.setHeaders.forEach((k, v) -> headers.put(k, resolve(v)));
        }

        headers.entrySet().removeIf(e -> {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            return v.isEmpty() || v.equalsIgnoreCase("Bearer") || v.equalsIgnoreCase("Basic");
        });

        // human gate: ${OTP} anywhere in the request pauses for a person (never read from a response)
        String raw = path + " " + String.valueOf(ep.requestBody) + " " + String.valueOf(headers.values());
        // the login did not hand out an OTP session (two-factor is off): the OTP step is simply not needed, nobody is asked
        if (raw.contains("${OTP}") && raw.contains("${otpSessionRef}") && !vars.containsKey("otpSessionRef") && !vars.containsKey("OTP")) {
            r.method = method;
            r.outcome = "SKIPPED";
            r.failureCategory = "NOT_NEEDED";
            r.remark = "Not needed: the login did not ask for an OTP (two-factor is off), so this step is skipped.";
            r.endedAt = Instant.now().toString();
            return r;
        }
        if (raw.contains("${OTP}") && !vars.containsKey("OTP")) {
            String otp = HumanGate.ask(model.api.name, tc.id, ep.name, resolve(env.baseUrl) + path);
            if (otp == null) {
                return blocked(r, "Waiting for OTP: enter it in the popup or run with -Dvar.OTP=<code>");
            }
            vars.put("OTP", otp);
        }

        String body = buildBody(ep, mu);
        String resolvedPath = resolve(path);
        String url = resolvedPath.startsWith("http") ? resolvedPath : resolve(env.baseUrl) + resolvedPath;
        String unresolved = firstUnresolved(headers.values(), url, body);
        if (unresolved != null) return blocked(r, "Variable not available: " + unresolved);

        r.method = method;
        r.url = url;
        r.baseUrl = resolve(env.baseUrl);
        r.startedAt = Instant.now().toString();

        HybridEnvelope.Wire wire = null;
        if (body != null && !body.isBlank() && envelope != null && !skipEnvelope(url)) {
            try {
                wire = envelope.encrypt(body);
                if ("TAMPERED_ENCRYPTION".equals(tc.category)) {
                    String bad = new StringBuilder(wire.clientSecret).reverse().toString();
                    wire = new HybridEnvelope.Wire(wire.body, bad, wire.clientPublicKey);
                }
            } catch (Exception ex) {
                r.endedAt = Instant.now().toString();
                return blocked(r, "Encryption failed: " + ex.getMessage());
            }
        }

        if ("WS".equals(method)) return executeWs(tc, ep, r, url, headers, body);
        if ("GRPC".equals(method)) return executeGrpc(tc, ep, r, url, headers, body);

        long t0 = System.nanoTime();
        Response resp;
        try {
            RequestSpecification spec = RestAssured.given().relaxedHTTPSValidation().redirects().follow(false)
                    .config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", 15000)
                            .setParam("http.socket.timeout", ep.timeoutMs != null ? ep.timeoutMs : 25000)));
            for (Map.Entry<String, String> h : headers.entrySet()) spec.header(h.getKey(), h.getValue());
            Map<String, String> effQuery = new LinkedHashMap<>(ep.query);
            if (mu != null) {
                mu.removeQuery.forEach(effQuery.keySet()::remove);
                effQuery.putAll(mu.setQuery);
            }
            for (Map.Entry<String, String> q : effQuery.entrySet()) spec.queryParam(q.getKey(), resolve(q.getValue()));
            for (Map.Entry<String, String> q : authQuery.entrySet()) spec.queryParam(q.getKey(), q.getValue());
            String xsrf = cookies.get("XSRF-TOKEN");
            boolean noSession = "NO_SESSION".equals(tc.category);
            if (!noSession) {
                cookies.forEach((k, v) -> spec.cookie(k, "TAMPERED_TOKEN".equals(tc.category) && authCookie(k) ? tamper(v) : v));
                if (xsrf != null && headers.keySet().stream().noneMatch(h -> h.equalsIgnoreCase("X-XSRF-TOKEN"))) {
                    String dx = java.net.URLDecoder.decode(xsrf, java.nio.charset.StandardCharsets.UTF_8);
                    spec.header("X-XSRF-TOKEN", "TAMPERED_CSRF".equals(tc.category) ? tamper(dx) : dx);
                }
            }
            if (wire != null) {
                spec.body(wire.body).header("clientsecret", wire.clientSecret).header("clientpublickey", wire.clientPublicKey);
            } else if (body != null) {
                spec.body(body);
            }
            resp = spec.request(method, url);
        } catch (Exception ex) {
            r.endedAt = Instant.now().toString();
            return blocked(r, "Transport error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        r.latencyMs = (System.nanoTime() - t0) / 1_000_000;
        if (!"NO_SESSION".equals(tc.category) && !tc.category.startsWith("TAMPERED")) {
            for (io.restassured.http.Cookie c : resp.getDetailedCookies()) {
                if (c.getValue() == null || c.getValue().isEmpty()) cookies.remove(c.getName());
                else cookies.put(c.getName(), c.getValue());
            }
        }
        r.endedAt = Instant.now().toString();
        r.httpStatus = resp.statusCode();
        String respBody = resp.asString();
        String note = "";
        if (envelope != null && HybridEnvelope.isEnvelope(respBody)) {
            try {
                respBody = envelope.decrypt(respBody, resp::getHeader);
                note = "\n[response was encrypted - shown decrypted]";
            } catch (Exception ex) {
                note = "\n[response decryption failed: " + ex.getMessage() + "]";
            }
        }
        StringBuilder rq = new StringBuilder(method + " " + url);
        headers.forEach((k, v) -> rq.append("\n").append(k).append(": ").append(v));
        if (body != null) rq.append("\n\n").append(body);
        if (wire != null) rq.append("\n[sent encrypted: RSA-OAEP wrapped AES-256-GCM envelope]");
        r.requestEvidence = Redactor.mask(rq.toString());
        r.responseEvidence = Redactor.mask(truncate(respBody, 4000)) + note;

        // extraction also runs on non-2xx answers: a rejected login may carry flags such as sessionActive that later steps need
        if (resp.statusCode() < 500) extract(ep, respBody, resp::getHeader);
        judge(tc, r, respBody);
        return r;
    }

    /** gRPC unary call through reflection; the status is mapped to an HTTP-like code (OK 200, INVALID_ARGUMENT 400, UNAUTHENTICATED 401 ...). */
    private TestResult executeGrpc(TestCase tc, Endpoint ep, TestResult r, String url, Map<String, String> headers, String body) {
        java.net.URI u = java.net.URI.create(url);
        boolean tls = "https".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        String full = u.getPath();
        r.method = "GRPC";
        r.url = (tls ? "grpcs://" : "grpc://") + u.getHost() + ":" + port + full;
        r.baseUrl = resolve(env.baseUrl);
        r.startedAt = Instant.now().toString();
        GrpcClient.Result g = GrpcClient.call(u.getHost(), port, tls, full, body, headers, ep.timeoutMs != null ? ep.timeoutMs : 15000);
        r.latencyMs = g.ms;
        r.endedAt = Instant.now().toString();
        StringBuilder rq = new StringBuilder("GRPC " + full + "  (" + u.getHost() + ":" + port + (tls ? ", TLS" : "") + ")");
        headers.forEach((k, v) -> rq.append("\n").append(k).append(": ").append(v));
        if (body != null) rq.append("\n\n").append(body);
        r.requestEvidence = Redactor.mask(rq.toString());
        if (g.unsendable || (java.util.Set.of("WRONG_TYPE", "INVALID_TYPE", "MALFORMED_XML", "XXE").contains(tc.category))) {
            r.outcome = "SKIPPED";
            r.remark = "Not applicable to gRPC: " + (g.error == null ? "the check changes the type or format of a field" : g.error) + " (protobuf is typed, the schema already enforces this)";
            return r;
        }
        if (g.status == 0) return blocked(r, "gRPC error: " + (g.error == null ? "no answer" : g.error));
        r.httpStatus = g.status;
        r.responseEvidence = Redactor.mask(truncate(g.body, 4000)) + (g.grpcCode > 0 ? "\n[gRPC status " + g.grpcName + "]" : "");
        if (g.status < 500) extract(ep, g.body, null);
        judge(tc, r, g.body);
        return r;
    }

    /** WebSocket request: connect, send the message, collect the answers; judged like an HTTP exchange (101 = connected). */
    private TestResult executeWs(TestCase tc, Endpoint ep, TestResult r, String httpUrl, Map<String, String> headers, String body) {
        String url = httpUrl.startsWith("https://") ? "wss://" + httpUrl.substring(8) : httpUrl.startsWith("http://") ? "ws://" + httpUrl.substring(7) : httpUrl;
        r.method = "WS";
        r.url = url;
        r.baseUrl = resolve(env.baseUrl);
        r.startedAt = Instant.now().toString();
        WsClient.Result w = WsClient.exchange(url, headers, body, ep.expectMessages == null ? 1 : ep.expectMessages, ep.timeoutMs != null ? ep.timeoutMs : 5000);
        r.latencyMs = w.ms;
        r.endedAt = Instant.now().toString();
        if (w.status == 0) return blocked(r, "WebSocket error: " + (w.error == null ? "no connection" : w.error));
        r.httpStatus = w.status;
        String first = w.messages.isEmpty() ? "" : w.messages.get(0);
        StringBuilder rq = new StringBuilder("WS CONNECT " + url);
        headers.forEach((k, v) -> rq.append("\n").append(k).append(": ").append(v));
        if (body != null) rq.append("\n\nsend: ").append(body);
        r.requestEvidence = Redactor.mask(rq.toString());
        r.responseEvidence = Redactor.mask(truncate(String.join("\n---\n", w.messages), 4000))
                + (w.closeCode > 0 ? "\n[closed by the server with code " + w.closeCode + "]" : "") + (w.timedOut ? "\n[no answer within the timeout]" : "");
        if (w.status < 500) extract(ep, first, null);
        judge(tc, r, first);
        if ("PASS".equals(r.outcome) && "POSITIVE".equals(tc.category) && w.timedOut && ep.expectMessages != null && w.messages.size() < ep.expectMessages) {
            fail(r, "NO_ANSWER", "medium", "Expected " + ep.expectMessages + " message(s) but received " + w.messages.size() + " before the timeout");
        }
        return r;
    }

    private static boolean isLoginEndpoint(Endpoint ep) {
        if (ep == null || ep.path == null) return false;
        return ep.path.toLowerCase().matches(".*(login|signin|sign-in|authenticate|/token).*")
                && ep.extract.stream().anyMatch(x -> x.var != null && x.var.toLowerCase().contains("token"));
    }

    private boolean skipEnvelope(String url) {
        for (String p : model.envelope.skipPaths) if (!p.isBlank() && url.contains(p)) return true;
        return false;
    }

    private static final java.util.Set<String> SECURITY = java.util.Set.of("NO_SESSION", "WRONG_PASSWORD", "TAMPERED_TOKEN",
            "TAMPERED_CSRF", "OTHER_USER", "TAMPERED_ENCRYPTION", "XXE", "MALFORMED_XML", "WRONG_METHOD", "LARGE_PAYLOAD");
    private static final java.util.Set<String> DOB = java.util.Set.of("DOB_TODAY", "DOB_FUTURE", "DOB_INVALID", "DOB_TOO_OLD");
    private static final java.util.Set<Integer> BROKEN_POSITIVE = java.util.Set.of(401, 403, 404, 405, 501, 502, 503, 504);

    private void judge(TestCase tc, TestResult r, String respBody) {
        String cat = tc.category;
        boolean positive = "POSITIVE".equals(cat);
        boolean state = "STATE_CHECK".equals(cat) || "VERIFY_LOGIN".equals(cat);
        List<Integer> expect = !tc.expected.status.isEmpty() ? tc.expected.status : rules.expected(cat);
        if (expect.isEmpty() && (positive || state)) expect = List.of(200, 201, 202);
        Endpoint epJ = model.endpoint(tc.endpointId);
        if (positive && epJ != null && "WS".equals(epJ.method) && tc.expected.status.isEmpty()) expect = List.of(101);
        r.expectedStatus = expect;
        r.owasp = rules.owasp(cat);
        int http = r.httpStatus;

        if (positive || state) {
            Endpoint ep0 = model.endpoint(tc.endpointId);
            if (positive && (http == 401 || http == 403) && ep0 != null && ep0.authProfileRef == null && cookies.isEmpty()
                    && ep0.headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Authorization")) && !expect.contains(http)) {
                r.outcome = "BLOCKED";
                r.failureCategory = "AUTH_NOT_CONFIGURED";
                r.remark = "Endpoint answered " + http + " and has no auth profile; configure authProfileRef/credentials, then re-run";
                return;
            }
            String soap = positive ? XmlLite.fault(respBody) : null;
            if (soap != null) {
                fail(r, "SOAP_FAULT", "medium", "The service answered a valid request with a SOAP Fault" + (soap.isEmpty() ? "" : ": " + soap));
                return;
            }
            if (positive && http < 300 && graphQlErrors(respBody) != null) {
                fail(r, "GRAPHQL_ERRORS", "medium", "HTTP " + http + " but the GraphQL response carries errors: " + graphQlErrors(respBody));
                return;
            }
            if (!expect.contains(http)) {
                if (state) {
                    fail(r, "STATE_CHANGED_BY_NEGATIVES", "critical", "The valid request failed with HTTP " + http
                            + " after the negatives: the bad inputs changed the session, permissions or data (OWASP API6)");
                } else {
                    fail(r, "UNEXPECTED_STATUS", http >= 500 ? "high" : "medium", "Valid request returned " + http + ", expected " + expect);
                }
                return;
            }
        } else {
            if (http >= 500 && XmlLite.fault(respBody) == null) {
                fail(r, "SERVER_ERROR_ON_BAD_INPUT", "high", "Invalid request caused HTTP " + http + " - should return a proper validation message (CWE-209)");
                return;
            }
            String body = respBody == null ? "" : respBody;
            if (LEAK.matcher(body).find()) {
                fail(r, "INFORMATION_LEAK", "high", "Internal error details leaked in the response for invalid input (CWE-209): " + businessMessage(body));
                return;
            }
            if ("XSS_SCRIPT".equals(cat) && body.toLowerCase().contains("<script>")) {
                fail(r, "REFLECTED_INPUT", "medium", http >= 400
                        ? "Rejected, but the script text is echoed back unescaped in the error message (reflected input, CWE-79)"
                        : "Script tag accepted and sent back unescaped in the response (XSS risk, CWE-79)");
                return;
            }
            boolean rejected = http >= 400 || businessRejected(body) || XmlLite.fault(body) != null;
            if (rejected) {
                r.outcome = "PASS";
                String msg = businessMessage(body);
                if (http >= 400 && !expect.isEmpty() && !expect.contains(http)) {
                    r.remark = "Wrong status code: got " + http + ", the standard is " + expect
                            + (rules.why(cat).isEmpty() ? "" : " because " + rules.why(cat)) + (msg.isEmpty() ? "" : " (" + msg + ")");
                } else {
                    r.remark = "Rejected as expected: " + (msg.isEmpty() ? "HTTP " + http : msg);
                }
            } else if (SECURITY.contains(cat)) {
                fail(r, "INVALID_REQUEST_ACCEPTED", "critical", "Invalid input was ACCEPTED by the API (security check: " + cat + ", HTTP " + http + ")");
                return;
            } else if (DOB.contains(cat)) {
                fail(r, "INVALID_REQUEST_ACCEPTED", "medium", "Invalid date of birth was ACCEPTED by the API (" + cat + ")");
                return;
            } else if ("NOT_EXISTING".equals(cat) && emptyData(body)) {
                r.outcome = "PASS";
                r.remark = "No data returned for a record that does not exist (a 404/400 would be clearer)";
            } else if (emptyData(body)) {
                fail(r, "VALIDATION_MISSING", "medium", "Input validation missing: the invalid value was ACCEPTED (HTTP " + http
                        + ", no error message) and no data came back. Safe in effect, but the API must reject it with 400/422 (CWE-20)");
                return;
            } else {
                fail(r, "INVALID_REQUEST_ACCEPTED", "medium", "Invalid input was ACCEPTED by the API and data was returned (expected rejection)");
                return;
            }
        }
        for (Assertion a : tc.expected.assertions) {
            String err = check(a, r, respBody);
            if (err != null) {
                fail(r, "ASSERTION_FAILED", "medium", err);
                return;
            }
        }
        if (r.outcome == null && positive) {
            Endpoint cep = model.endpoint(tc.endpointId);
            String mode = model.api.contractMode == null ? "warn" : model.api.contractMode;
            if (cep != null && cep.responseSchema != null && cep.responseSchema.isObject() && cep.responseSchema.size() > 0 && !"off".equals(mode)) {
                JsonNode rb = parse(respBody);
                if (rb != null && !rb.isNull() && !rb.isMissingNode()) {
                    List<String> dev = ContractValidator.validate(cep.responseSchema, rb);
                    if (!dev.isEmpty()) {
                        String msg = "Response does not match the contract (" + dev.size() + (dev.size() >= 20 ? "+" : "") + " deviation(s)): "
                                + String.join("; ", dev.subList(0, Math.min(3, dev.size())));
                        if ("fail".equals(mode)) {
                            fail(r, "CONTRACT_VIOLATION", "medium", msg);
                            return;
                        }
                        r.outcome = "PASS";
                        r.remark = msg;
                        return;
                    }
                }
            }
        }
        if (r.outcome == null) r.outcome = "PASS";
    }

    /** A 200 answer that still says "failed": statusCode >= 400, status FAIL/ERROR, success false or an error field. */
    static boolean businessRejected(String body) {
        JsonNode n = parse(body);
        if (n == null || !n.isObject()) return false;
        if (graphQlErrors(body) != null) return true;
        for (String k : new String[] { "statusCode", "status_code", "httpStatus", "code" }) {
            JsonNode v = n.get(k);
            if (v != null && v.canConvertToInt() && v.asInt() >= 400) return true;
        }
        JsonNode st = n.get("status");
        if (st != null && st.isTextual() && st.asText().toUpperCase().matches("FAIL|FAILED|FAILURE|ERROR|REJECTED|INVALID")) return true;
        JsonNode ok = n.get("success");
        if (ok != null && ok.isBoolean() && !ok.asBoolean()) return true;
        JsonNode err = n.get("error");
        return err != null && !err.isNull() && !(err.isTextual() && err.asText().isBlank()) && !(err.isContainerNode() && err.size() == 0);
    }

    /** Text of the first GraphQL error ({"errors":[{"message":..}]}), or null when the body has none. */
    static String graphQlErrors(String body) {
        JsonNode n = parse(body);
        if (n == null || !n.isObject() || !n.has("errors") || !n.get("errors").isArray() || n.get("errors").size() == 0) return null;
        JsonNode first = n.get("errors").get(0);
        String m = first.path("message").asText("");
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    static String businessMessage(String body) {
        String fault = XmlLite.fault(body);
        if (fault != null) return fault;
        String gq = graphQlErrors(body);
        if (gq != null) return gq;
        JsonNode n = parse(body);
        if (n == null || !n.isObject()) return "";
        for (String k : new String[] { "message", "errorMessage", "error_description", "error", "msg", "detail" }) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText().length() > 140 ? v.asText().substring(0, 140) : v.asText();
        }
        return "";
    }

    private static final java.util.Set<String> META = java.util.Set.of("statuscode", "message", "status", "requestid", "total",
            "totalcount", "count", "success", "errormessage");

    /** true when the response carries no records: only status/message fields, nulls or empty lists/objects. */
    static boolean emptyData(String body) {
        JsonNode n = parse(body);
        if (n == null || n.isNull() || n.isMissingNode()) return body == null || body.isBlank();
        return !hasData(n, "");
    }

    private static boolean hasData(JsonNode n, String key) {
        if (n == null || n.isNull() || n.isMissingNode() || META.contains(key.toLowerCase())) return false;
        if (n.isArray()) {
            for (JsonNode x : n) if (hasData(x, "")) return true;
            return false;
        }
        if (n.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (hasData(e.getValue(), e.getKey())) return true;
            }
            return false;
        }
        return !n.asText().isBlank();
    }

    /** Legacy rule: negatives are pointless when their positive test did not run or the endpoint is not reachable. */
    private String gateReason(TestCase tc) {
        if ("POSITIVE".equals(tc.category) || tc.endpointId == null) return null;
        TestResult po = positiveByEndpoint.get(tc.endpointId);
        if (po == null) return null; // positive not part of this run: run the negative anyway
        if ("PASS".equals(po.outcome)) return null;
        if ("FAIL".equals(po.outcome) && po.httpStatus != null && !BROKEN_POSITIVE.contains(po.httpStatus)) return null;
        if ("FAIL".equals(po.outcome)) {
            int h = po.httpStatus == null ? 0 : po.httpStatus;
            return "Positive test " + po.testCaseId + " failed with HTTP " + h + (h == 404 ? " (endpoint not deployed)"
                    : h == 403 ? " (no permission / feature disabled)" : h == 401 ? " (not authorised)" : h >= 502 ? " (server/gateway down)" : "")
                    + ", so this negative test would prove nothing";
        }
        return "Positive test " + po.testCaseId + " was not executed (" + po.outcome + ")";
    }

    private String check(Assertion a, TestResult r, String body) {
        JsonNode root = parse(body);
        switch (a.type == null ? "" : a.type) {
            case "jsonPathExists":
                return JsonPathLite.read(root, a.path) == null ? "Missing " + a.path : null;
            case "jsonPathEquals": {
                String actual;
                if (a.path.contains(".find{")) {
                    actual = findExtract(root, a.path);
                } else {
                    JsonNode v = JsonPathLite.read(root, a.path);
                    actual = v == null ? null : v.asText();
                }
                if (actual == null) return "Missing " + a.path;
                String exp = a.expected == null ? "null" : resolve(a.expected.asText());
                return actual.equals(exp) ? null : a.path + " expected '" + exp + "' but was '" + actual + "'";
            }
            case "bodyContains":
                return body != null && body.contains(a.expected.asText()) ? null : "Response does not contain '" + a.expected.asText() + "'";
            case "jsonPathAbsent":
                return JsonPathLite.read(root, a.path) == null ? null : a.path + " is present but must not be";
            case "xpathExists":
                return XmlLite.xpath(body, a.path) == null ? "Missing XML element " + a.path : null;
            case "xpathEquals": {
                String actual = XmlLite.xpath(body, a.path);
                if (actual == null) return "Missing XML element " + a.path;
                String exp = a.expected == null ? "" : resolve(a.expected.asText());
                return actual.equals(exp) ? null : a.path + " expected '" + exp + "' but was '" + actual + "'";
            }
            case "soapNoFault": {
                String f = XmlLite.fault(body);
                return f == null ? null : "SOAP Fault: " + f;
            }
            case "maxLatencyMs":
                return r.latencyMs <= a.expected.asLong() ? null : "Latency " + r.latencyMs + "ms > " + a.expected.asLong() + "ms";
            case "bodyNotContains":
                return body.contains(a.expected.asText()) ? "Body contains '" + a.expected.asText() + "'" : null;
            default:
                return "Unsupported assertion type: " + a.type;
        }
    }

    private static boolean authCookie(String name) {
        String n = name.toLowerCase();
        return !n.contains("xsrf") && !n.contains("csrf")
                && (n.contains("token") || n.contains("session") || n.contains("jwt") || n.contains("auth") || n.contains("sid"));
    }

    /** Real value with the last characters changed: looks valid but the signature / match is broken. */
    private static String tamper(String v) {
        if (v == null || v.isEmpty()) return "tampered-value-0000";
        if (v.length() <= 6) return v + "X9";
        String tail = v.substring(v.length() - 6);
        return v.substring(0, v.length() - 6) + (tail.equals("Zz9Zz9") ? "Yy8Yy8" : "Zz9Zz9");
    }

    // ------------------------------------------------------------------ auth

    /** Adds credentials from the endpoint auth profile. Returns a BLOCKED reason or null. */
    private String applyAuth(Endpoint ep, Map<String, String> headers, Map<String, String> query) {
        AuthProfile a = model.authProfile(ep.authProfileRef);
        if (ep.authProfileRef != null && a == null) return "Auth profile not found: " + ep.authProfileRef;
        if (a == null || "none".equals(a.type)) return null;
        switch (a.type) {
            case "basic": {
                String u = resolve(a.username), p = resolve(a.password);
                String miss = missing(u, a.username, p, a.password);
                if (miss != null) return miss;
                headers.put("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString((u + ":" + p).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return null;
            }
            case "bearer": {
                String t = resolve(a.token);
                String miss = missing(t, a.token);
                if (miss != null) return miss;
                headers.put("Authorization", "Bearer " + t);
                return null;
            }
            case "apikey": {
                String v = resolve(a.value);
                String miss = missing(v, a.value);
                if (miss != null) return miss;
                if ("query".equalsIgnoreCase(a.in)) query.put(a.name, v);
                else headers.put(a.name, v);
                return null;
            }
            case "login": {
                String state = loginState.computeIfAbsent(a.id, k -> doLogin(a));
                if (!"OK".equals(state)) return state;
                String hv = resolve(a.headerTemplate.replace("${token}", "${" + a.tokenVar + "}"));
                headers.put(a.headerName, hv);
                return null;
            }
            default:
                return "Unsupported auth profile type: " + a.type;
        }
    }

    private String doLogin(AuthProfile a) {
        Endpoint login = model.endpoint(a.loginEndpointId);
        if (login == null) return "Login endpoint not found: " + a.loginEndpointId;
        if (login.authProfileRef != null) return "Login endpoint must not require an auth profile";
        TestCase t = new TestCase();
        t.id = "LOGIN_" + a.id;
        t.endpointId = login.id;
        t.category = "POSITIVE";
        TestResult res = execute(t);
        if (!"PASS".equals(res.outcome)) return "Login failed (" + res.outcome + "): " + res.remark;
        return vars.containsKey(a.tokenVar) ? "OK" : "Login succeeded but token '" + a.tokenVar + "' was not extracted";
    }

    private static String missing(String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            String v = pairs[i];
            if (v == null || v.isBlank()) return "Credentials not configured: " + pairs[i + 1] + " is empty";
            if (v.contains("${")) return "Credentials not configured: " + pairs[i + 1];
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private String buildBody(Endpoint ep, Mutation mu) {
        if (mu != null && mu.bodyOverride != null) return resolve(mu.bodyOverride);
        if (ep.requestBody == null) return null;
        if (ep.requestBody.isTextual()) return resolve(ep.requestBody.asText());
        JsonNode node = ep.requestBody.deepCopy();
        if (node instanceof ObjectNode obj && mu != null) {
            mu.removeBodyFields.forEach(obj::remove);
            mu.setBodyFields.forEach(obj::set);
        }
        return resolve(node.toString());
    }

    private void extract(Endpoint ep, String body, java.util.function.Function<String, String> header) {
        if (ep.extract.isEmpty()) return;
        JsonNode root = parse(body);
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Extract x : ep.extract) {
            if (done.contains(x.var)) continue;
            String found = null;
            if (x.jsonPath.contains(".find{")) {
                found = findExtract(root, x.jsonPath);
            } else if (x.jsonPath.toLowerCase().startsWith("jsonlist:")) {
                found = jsonList(root, x.jsonPath.substring(9));
            } else if (x.jsonPath.toLowerCase().startsWith("xpath:")) {
                found = XmlLite.xpath(body, x.jsonPath.substring(6));
            } else if (x.jsonPath.toLowerCase().startsWith("$header.")) {
                found = header == null ? null : header.apply(x.jsonPath.substring(8));
            } else {
                JsonNode v = JsonPathLite.read(root, x.jsonPath);
                if (v != null) found = v.asText();
            }
            if (found != null && !found.isBlank()) {
                vars.put(x.var, found);
                done.add(x.var);
            }
        }
    }

    private static final Pattern FIND = Pattern.compile("^(.+?)\\.find\\{(.*)}\\.([A-Za-z0-9_]+)$");
    private static final Pattern COND = Pattern.compile("it\\.([A-Za-z0-9_]+)\\s*==\\s*'([^']*)'");

    /** list.find{it.FIELD=='value' && it.F2=='v2'}.TARGET -> TARGET of the first matching element. */
    private String findExtract(JsonNode root, String spec) {
        Matcher m = FIND.matcher(spec);
        if (!m.matches()) return null;
        JsonNode list = JsonPathLite.read(root, m.group(1));
        if (list == null || !list.isArray()) return null;
        for (JsonNode el : list) {
            boolean all = true;
            Matcher c = COND.matcher(m.group(2));
            int n = 0;
            while (c.find()) {
                n++;
                JsonNode f = el.get(c.group(1));
                if (f == null || !f.asText().equals(resolve(c.group(2)))) {
                    all = false;
                    break;
                }
            }
            if (all && n > 0 && el.hasNonNull(m.group(3))) return el.get(m.group(3)).asText();
        }
        return null;
    }

    /** jsonlist:path:max[:offset] -> JSON array string of the first N non-blank values found along the path. */
    private static String jsonList(JsonNode root, String spec) {
        String[] parts = spec.split(":(?=[0-9]+(:|$))");
        String[] seg = parts[0].split("\\.");
        int max = 10, offset = 0;
        try {
            if (parts.length > 1) max = Integer.parseInt(parts[1].trim());
            if (parts.length > 2) offset = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException ignored) {
            // defaults
        }
        List<JsonNode> cur = new ArrayList<>();
        cur.add(root);
        for (String raw : seg) {
            String name = raw.trim().replaceAll("^['\"]|['\"]$", "");
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : cur) {
                if (n.isArray()) {
                    for (JsonNode e : n) if (e.has(name)) next.add(e.get(name));
                } else if (n.has(name)) {
                    next.add(n.get(name));
                }
            }
            cur = next;
        }
        List<String> vals = new ArrayList<>();
        int seen = 0;
        for (JsonNode n : cur) {
            List<JsonNode> flat = new ArrayList<>();
            if (n.isArray()) n.forEach(flat::add);
            else flat.add(n);
            for (JsonNode f : flat) {
                String t = f.asText("").trim();
                if (t.isEmpty()) continue;
                if (seen++ < offset) continue;
                if (vals.size() < max) vals.add(t);
            }
        }
        if (vals.isEmpty()) return null;
        try {
            return M.writeValueAsString(vals);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolve(String s) {
        return resolve(s, 0);
    }

    /** ${var} ${var?} (optional -> empty) ${ENV:NAME} ${NOW:pattern} ${RANDOM:n} ${UUID}; unknown names are left as is. */
    private String resolve(String s, int depth) {
        if (s == null || depth > 5) return s;
        // "${json:name}" (with the quotes) -> the stored raw JSON value, e.g. ["A1","A2"]
        Matcher jm = JSON_VAR.matcher(s);
        StringBuilder js = new StringBuilder();
        while (jm.find()) {
            String raw = vars.get(jm.group(1));
            jm.appendReplacement(js, Matcher.quoteReplacement(raw != null ? raw : jm.group()));
        }
        jm.appendTail(js);
        s = js.toString();
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = lookup(m.group(1), depth);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String lookup(String expr, int depth) {
        boolean optional = expr.endsWith("?");
        String e = optional ? expr.substring(0, expr.length() - 1) : expr;
        String v;
        if (e.startsWith("ENV:")) {
            v = com.api.product.core.store.SecretVault.get(e.substring(4));
        } else if (e.startsWith("NOW:")) {
            try {
                v = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern(e.substring(4)));
            } catch (IllegalArgumentException bad) {
                v = null;
            }
        } else if (e.startsWith("RANDOM:")) {
            int n = 6;
            try {
                n = Math.max(1, Math.min(18, Integer.parseInt(e.substring(7).trim())));
            } catch (NumberFormatException ignored) {
                // default length
            }
            StringBuilder d = new StringBuilder();
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = 0; i < n; i++) d.append(rnd.nextInt(10));
            v = d.toString();
        } else if (e.startsWith("DATE:")) {
            // ${DATE:-30:dd/MM/yyyy} -> the date 30 days ago
            try {
                String arg = e.substring(5);
                int c2 = arg.indexOf(':');
                v = java.time.LocalDate.now().plusDays(Integer.parseInt(arg.substring(0, c2).trim()))
                        .format(java.time.format.DateTimeFormatter.ofPattern(arg.substring(c2 + 1), java.util.Locale.ENGLISH));
            } catch (RuntimeException bad) {
                v = null;
            }
        } else if (e.startsWith("PAD:")) {
            // ${PAD:1025} -> 1025 'A' characters (keeps large-payload tests small in the model file)
            int n = 1024;
            try {
                n = Math.max(1, Math.min(5_000_000, Integer.parseInt(e.substring(4).trim())));
            } catch (NumberFormatException ignored) {
                // default size
            }
            v = "A".repeat(n);
        } else if ("UUID".equals(e)) {
            v = java.util.UUID.randomUUID().toString();
        } else if ("TXN_ID".equals(e)) {
            v = String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        } else if ("TIMESTAMP".equals(e)) {
            v = String.valueOf(System.currentTimeMillis());
        } else if ("TODAY".equals(e)) {
            v = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } else if (e.contains("|") && !e.contains(":")) {
            v = null; // ${a|b|c}: the first one that has a value
            for (String alt : e.split("\\|")) {
                String c = vars.get(alt.trim());
                if (c != null && !c.isBlank()) {
                    v = c.contains("${") ? resolve(c, depth + 1) : c;
                    break;
                }
            }
        } else {
            v = vars.get(e);
            if (v == null) v = System.getenv(e);
            if (v != null && v.contains("${")) v = resolve(v, depth + 1);
        }
        if (v == null && optional) return "";
        return v;
    }

    private String firstUnresolved(Iterable<String> headerValues, String url, String body) {
        List<String> all = new ArrayList<>();
        headerValues.forEach(all::add);
        all.add(url);
        if (body != null) all.add(body);
        for (String v : all) {
            Matcher m = VAR.matcher(v == null ? "" : v);
            if (m.find()) return m.group();
        }
        return null;
    }

    private static Environment pickEnv(ApiModel m, String name) {
        if (m.environments.isEmpty()) throw new IllegalArgumentException("Model has no environments");
        if (name != null) for (Environment e : m.environments) if (name.equalsIgnoreCase(e.name)) return e;
        return m.environments.get(0);
    }

    private TestResult base(TestCase tc) {
        TestResult r = new TestResult();
        r.runId = runId;
        r.projectId = model.api.name;
        r.envId = env.name;
        r.testCaseId = tc.id;
        r.testCaseVersion = tc.version;
        r.endpointId = tc.endpointId;
        Endpoint ep0 = model.endpoint(tc.endpointId);
        r.module = ep0 == null || ep0.module == null || ep0.module.isBlank() ? "General" : ep0.module;
        r.apiName = ep0 == null ? tc.endpointId : (ep0.name != null ? ep0.name : ep0.id);
        r.category = tc.category;
        r.title = tc.title;
        r.ruleVersion = rules.version;
        return r;
    }

    private TestResult gated(TestCase tc, String why) {
        TestResult r = base(tc);
        r.outcome = "BLOCKED";
        r.failureCategory = "POSITIVE_NOT_PASSING";
        r.remark = why;
        return r;
    }

    private TestResult notExecuted(TestCase tc, String why) {
        TestResult r = base(tc);
        r.outcome = "NOT_EXECUTED";
        r.remark = why;
        return r;
    }

    private static TestResult blocked(TestResult r, String why) {
        r.outcome = "BLOCKED";
        r.failureCategory = "ENVIRONMENT_OR_DATA";
        r.remark = why;
        return r;
    }

    private static void fail(TestResult r, String category, String severity, String reason) {
        r.outcome = "FAIL";
        r.failureCategory = category;
        r.severity = severity;
        r.remark = reason;
    }

    private static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            return M.nullNode();
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    /** Minimal $.a.b[0].c reader - enough for extraction/assertions; no external dependency. */
    static final class JsonPathLite {
        static JsonNode read(JsonNode root, String path) {
            if (root == null || path == null) return null;
            String p = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
            JsonNode cur = root;
            for (String part : p.split("\\.")) {
                if (part.isEmpty()) continue;
                Matcher m = Pattern.compile("([^\\[]*)((\\[\\d+])*)").matcher(part);
                if (!m.matches()) return null;
                if (!m.group(1).isEmpty()) cur = cur.get(m.group(1));
                Matcher idx = Pattern.compile("\\[(\\d+)]").matcher(m.group(2));
                while (cur != null && idx.find()) cur = cur.get(Integer.parseInt(idx.group(1)));
                if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
            }
            return cur;
        }
    }
}
