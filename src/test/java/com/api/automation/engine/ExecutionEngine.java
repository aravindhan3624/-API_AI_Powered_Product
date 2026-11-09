package com.api.automation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.api.automation.ai.AiVerdict;
import com.api.automation.ai.AiVerifier;
import com.api.automation.config.ConfigManager;
import com.api.automation.config.ProjectContext;
import com.api.automation.dashboard.LiveDashboard;
import com.api.automation.dashboard.TestView;
import com.api.automation.negative.VaptRules;
import com.api.automation.otp.OtpContext;
import com.api.automation.otp.OtpRequest;
import com.api.automation.otp.OtpResult;
import com.api.automation.otp.OtpService;
import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Check;
import com.api.automation.pages.PageRegistry;
import com.api.automation.pages.PageResult;
import com.api.automation.payloads.ResolvedPayload;
import com.api.automation.utils.*;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;


public class ExecutionEngine {

    /** Extracted variable -> the test step that provides it (filled by the runner). */
    public static final java.util.Map<String, String> VALUE_SOURCE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] OTP_HINT_PATHS = {
            "OTP", "otp", "result[0].OTP", "result.OTP", "result.otp", "data.OTP", "data.otp"
    };

    /** Same id everywhere: Excel, Allure, dashboard.  TC01-1, TC01-2 ... */
    public static String testCaseId(Map<String, String> row, ResolvedPayload payloadObj, int payloadIndex) {
        String baseTcId = safe(row.get("TC_ID"));
        if (payloadObj == null) return baseTcId;
        if (payloadObj.getNegCode() != null) return baseTcId + "_" + payloadObj.getNegCode();
        String l = payloadObj.getLabel();
        if ("default".equals(l) || "valid".equals(l)) return baseTcId;
        return baseTcId + "-" + (payloadIndex + 1);
    }

    /** Readable API name: page name → Excel API_Name column → from the endpoint. */
    public static String apiName(Map<String, String> row, ResolvedPayload payloadObj) {
        String endpoint = safe(row.get("Endpoint"));
        ApiPage page = PageRegistry.find(ProjectContext.current(), endpoint);
        String name = page != null ? page.name()
                : !safe(row.get("API_Name")).isEmpty() ? safe(row.get("API_Name"))
                : ApiNameResolver.resolve(endpoint);
        if (payloadObj != null && payloadObj.getNegCode() != null) {
            return name + " | " + (payloadObj.isVerifyPositive() ? "CHECK " : "NEG ") + payloadObj.getNegCode() + " " + payloadObj.getNegTitle();
        }
        String label = payloadObj == null ? "default" : payloadObj.getLabel();
        return ("default".equals(label) || "valid".equals(label)) ? name : name + " [" + label + "]";
    }

    // ======================= EXECUTION ENGINE =======================

    /**
     * One API call at a time, framework-wide. While an OTP popup is open the running test
     * holds this lock, so NO other endpoint runs until the OTP is entered or skipped -
     * even if TestNG parallel mode or several runners are used. Only one OTP window can exist.
     */
    private static final java.util.concurrent.locks.ReentrantLock EXECUTION_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    public static TestDecision executeSingle(
            Map<String, String> row,
            ResolvedPayload payloadObj,
            int payloadIndex,
            Map<String, String> reportRow
    ) {
        EXECUTION_LOCK.lock();
        try {
            return executeLocked(row, payloadObj, payloadIndex, reportRow);
        } finally {
            EXECUTION_LOCK.unlock();
        }
    }

    private static TestDecision executeLocked(
            Map<String, String> row,
            ResolvedPayload payloadObj,
            int payloadIndex,
            Map<String, String> reportRow
    ) {

        String project = ProjectContext.current();
        String effectiveTcId = testCaseId(row, payloadObj, payloadIndex);
        String method = payloadObj != null && payloadObj.getMethodOverride() != null
                ? payloadObj.getMethodOverride() : safe(row.get("Method")).toUpperCase();
        String endpointTpl = payloadObj != null && payloadObj.getEndpointOverride() != null
                ? payloadObj.getEndpointOverride() : safe(row.get("Endpoint"));
        boolean generatedNegative = payloadObj != null && payloadObj.isGeneratedNegative();
        boolean negativeRow = payloadObj != null && payloadObj.getNegCode() != null;   // incl. "verify" checks
        String expectedStatus = safe(row.get("ExpectedStatus"));
        String extractRule = safe(row.get("Extract"));
        String validateRule = safe(row.get("Validate"));
        String headersCell = safe(row.get("Headers"));
        String rawBody = payloadObj == null ? "" : payloadObj.getBody();
        String label = payloadObj == null ? "default" : payloadObj.getLabel();
        boolean negative = payloadObj != null && payloadObj.isNegative();

        ApiPage page = PageRegistry.find(project, endpointTpl);
        String apiName = apiName(row, payloadObj);
        com.api.automation.negative.ExpectedStatus.Expect expectHttp = negativeRow
                ? com.api.automation.negative.ExpectedStatus.of(payloadObj.getNegCategory()) : null;
        String expectedResult = negativeRow
                ? (expectHttp == null ? payloadObj.getNegExpected() : "HTTP " + expectHttp.text() + " - " + payloadObj.getNegExpected())
                : expectedResult(row, page, negative, expectedStatus, label);
        // valid (positive) requests follow the rulebook too: ExpectedStatus column, else 200 / 201 / 202
        boolean fixedStatus = expectedStatus.matches("\\d{3}");
        com.api.automation.negative.ExpectedStatus.Expect expectPos = negativeRow || fixedStatus ? null
                : com.api.automation.negative.ExpectedStatus.of("POSITIVE");
        String expectedHttpText = expectHttp != null ? expectHttp.text()
                : negativeRow ? "200 OK (same as positive)"
                : fixedStatus ? expectedStatus + " " + com.api.automation.negative.ExpectedStatus.name(Integer.parseInt(expectedStatus))
                : expectPos != null ? expectPos.text() : "200 OK";
        reportRow.put("Expected_HTTP", expectedHttpText);
        reportRow.put("VAPT_Ref", expectHttp != null ? expectHttp.owasp : "");

        Allure.getLifecycle().updateTestCase(test -> {
            test.setName(effectiveTcId + " - " + apiName);
            test.setFullName(project + " | " + effectiveTcId + " - " + apiName);
            test.setHistoryId(project + "-" + effectiveTcId);
        });

        // ================= REPORT (fields known before the call) =================
        reportRow.put("TC_ID", effectiveTcId);
        reportRow.put("API_Name", apiName);
        reportRow.put("Method", method);
        reportRow.put("Endpoint", endpointTpl);
        reportRow.put("Base URL", ProjectContext.baseUrl());
        reportRow.put("ExpectedResult", expectedResult);
        reportRow.put("RequestBody", rawBody);
        reportRow.put("TestData", negativeRow ? payloadObj.getNegTitle() + ": " + payloadObj.getNegChange() : "NA");
        reportRow.put("Negative", negativeRow ? "Y" : "");
        reportRow.put("NegCategory", negativeRow ? safe(payloadObj.getNegCategory()) : "");
        reportRow.put("NegTitle", negativeRow ? safe(payloadObj.getNegTitle()) : "");
        reportRow.put("ActualResponse", "NA");
        reportRow.put("ActualResult", "");
        reportRow.put("AI_Verdict", "");
        reportRow.put("Exec Time", "0 ms");

        LiveDashboard.testStarted(effectiveTcId);
        TestView view = LiveDashboard.test(effectiveTcId);
        if (view != null) {
            view.expected = expectedResult;
            view.pageName = page == null ? "Generic validation" : page.getClass().getSimpleName();
        }

        OtpContext.Generation generation = null;

        try {
            // ================= SET COLUMN: NAME=value;NAME2=value (stored before the call) =================
            if (!negativeRow) applySet(safe(row.get("Set")));   // negatives reuse the positive's values

            // ================= VARIABLE INJECTION PASS 1 =================
            GlobalStore.takeFallbackNotes();               // start clean for this test
            String payload = GlobalStore.resolve(rawBody);
            String endpoint = GlobalStore.resolve(endpointTpl);
            String headersResolved = GlobalStore.resolve(headersCell);
            // login failed at the start of the run: nothing else can work, so say so once instead of 1,700 confusing failures
            if (ABORT_REASON != null && !(endpointTpl.toLowerCase().contains("/auth/login") && !negativeRow)) {
                return skip(effectiveTcId, reportRow, endpoint, payload, ABORT_REASON);
            }
            if (negativeRow) {
                String blocked = negativesBlockedReason(safe(row.get("TC_ID")));
                if (blocked != null) {
                    return skip(effectiveTcId, reportRow, endpoint, payload, blocked + ", so this negative check is not meaningful");
                }
            }
            if (payloadObj != null && payloadObj.getMode() == ResolvedPayload.Mode.NO_SESSION) {
                headersResolved = java.util.Arrays.stream(headersResolved.split(";"))
                        .filter(h -> !h.toLowerCase().startsWith("authorization") && !h.toLowerCase().startsWith("x-csrf-token"))
                        .collect(java.util.stream.Collectors.joining(";"));
            }
            if (payloadObj != null && (payloadObj.getMode() == ResolvedPayload.Mode.TAMPER_TOKEN
                    || payloadObj.getMode() == ResolvedPayload.Mode.TAMPER_CSRF)) {
                String target = payloadObj.getMode() == ResolvedPayload.Mode.TAMPER_TOKEN ? "authorization" : "x-csrf-token";
                headersResolved = java.util.Arrays.stream(headersResolved.split(";")).map(h -> {
                    int c = h.indexOf(':');
                    if (c < 0 || !h.substring(0, c).trim().equalsIgnoreCase(target)) return h;
                    String v = h.substring(c + 1).trim();
                    String prefix = v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(0, 7) : "";
                    return h.substring(0, c + 1) + prefix + com.api.automation.security.SessionFilter.tamperValue(v.substring(prefix.length()));
                }).collect(java.util.stream.Collectors.joining(";"));
            }

            // ================= SKIP VALIDATION (values from earlier steps) =================
            String missing = GlobalStore.findUnresolved(
                    (payload + " " + endpoint + " " + headersResolved).replace("${OTP}", ""));
            if (missing != null) {
                String var = missing.replace("?", "").split("\\|")[0].trim();
                String source = VALUE_SOURCE.getOrDefault(var, "an earlier step");
                String failed = failedSources(source);
                return skip(effectiveTcId, reportRow, endpoint, payload, failed != null
                        ? "Needs '" + var + "', but its source step failed (" + failed + "), so there is no value to send."
                        : "Needs '" + var + "' from step [" + source + "], but that step returned no record / value in this run.");
            }

            // ================= OTP HANDLING =================
            // Asked EVERY time (a fresh OTP per verify call), and only for valid (positive) data.
            // Negative tests never ask - they send the default OTP; the invalid field is what is tested.
            boolean otpFromTester = false;
            String otpNote = "";
            if (needsOtp(payload, endpoint)) {
                if (negativeRow) {
                    GlobalStore.put("OTP", OtpService.defaultOtp());
                } else {
                    OtpResult otp = askOtp(effectiveTcId, apiName, endpoint, payload, null, 1, null);
                    if (otp.action != OtpResult.Action.SUBMIT) {
                        return skip(effectiveTcId, reportRow, endpoint, payload, "OTP not entered: " + otp.reason);
                    }
                    GlobalStore.put("OTP", otp.otp);
                    otpFromTester = otp.enteredByTester();
                    otpNote = otp.defaulted ? otp.reason : "OTP entered by tester";
                }
            }

            // ================= VARIABLE INJECTION PASS 2 =================
            // Re-resolve from the template: "Resend OTP" may have refreshed chained values
            // (Client_Id, AadharRefNum, Aadhartxn, SMS id...) after pass 1.
            payload = GlobalStore.resolve(rawBody);
            endpoint = GlobalStore.resolve(endpointTpl);
            final String finalPayload = payload;
            final String finalEndpoint = endpoint;
            final String finalHeaders = headersResolved;
            // very large bodies (1 MB / 10 MB checks) are sent in full but shown shortened in reports and the console
            final String shownPayload = finalPayload != null && finalPayload.length() > 65536
                    ? finalPayload.substring(0, 4000) + "\n... (" + finalPayload.length() + " characters in total - shortened for the report)"
                    : finalPayload;
            final String baseTc = safe(row.get("TC_ID"));

            // ================= STATE-CHECK SNAPSHOT: the valid request once, right before the first negative of this API =================
            if (generatedNegative && payloadObj.getMode() != ResolvedPayload.Mode.IDOR_B) takeBaseline(baseTc);

            // ================= OTP RESET (new OTP flow starts) =================
            boolean otpGeneration = OtpContext.isGenerationApi(endpoint) && !generatedNegative;
            if (otpGeneration) {
                System.out.println("🔄 Resetting OTP for new flow → " + endpoint);
                GlobalStore.remove("OTP");
                GlobalStore.remove("OTP_HINT");
            }

            // ================= API CALL =================
            LiveDashboard.step(effectiveTcId, method + " " + PiiMasker.mask(endpoint.split("\\?")[0]));
            long start = System.currentTimeMillis();
            ResolvedPayload.Mode mode = payloadObj == null ? ResolvedPayload.Mode.NORMAL : payloadObj.getMode();
            NEG_MODE.set(mode);
            com.api.automation.security.SessionFilter.tamper(
                    mode == ResolvedPayload.Mode.NO_SESSION ? com.api.automation.security.SessionFilter.Tamper.NO_SESSION
                    : mode == ResolvedPayload.Mode.TAMPER_TOKEN ? com.api.automation.security.SessionFilter.Tamper.TOKEN
                    : mode == ResolvedPayload.Mode.TAMPER_CSRF ? com.api.automation.security.SessionFilter.Tamper.CSRF
                    : com.api.automation.security.SessionFilter.Tamper.NONE);
            Response response;
            try {
                response = RetryUtil.execute(method, otpGeneration || generatedNegative,
                        () -> send(method, finalEndpoint, finalPayload, finalHeaders));
            } finally {
                NEG_MODE.remove();
                com.api.automation.security.SessionFilter.skipSession(false);
            }
            long execTime = System.currentTimeMillis() - start;

            String bodyText = readBody(response);                 // decrypted when the project uses the envelope
            String normalized = ResponseHandler.normalize(bodyText);
            int httpStatus = response.getStatusCode();

            // ================= WRONG OTP → ask ONE more time (only when the tester typed it) =================
            if (otpFromTester && isWrongOtp(httpStatus, normalized)) {
                String msg = String.valueOf(ResponseAnalyzer.businessMessage(normalized));
                LiveDashboard.log("WARN", effectiveTcId, "Wrong OTP (" + msg + ") - asking once more");
                OtpResult again = askOtp(effectiveTcId, apiName, finalEndpoint, finalPayload,
                        "The OTP was not accepted (" + msg + "). Please enter it again - last attempt.", 2, null);
                if (again.action == OtpResult.Action.SUBMIT) {
                    GlobalStore.put("OTP", again.otp);
                    String retryPayload = GlobalStore.resolve(rawBody);
                    long t2 = System.currentTimeMillis();
                    response = send(method, finalEndpoint, retryPayload, finalHeaders);
                    execTime = System.currentTimeMillis() - t2;
                    bodyText = readBody(response);
                    normalized = ResponseHandler.normalize(bodyText);
                    httpStatus = response.getStatusCode();
                    otpNote = "1st OTP was wrong (" + msg + "); " + (again.defaulted ? again.reason : "2nd OTP entered by tester");
                } else {
                    otpNote = "1st OTP was wrong (" + msg + "); 2nd attempt skipped";
                }
            }
            if (needsOtp(rawBody, endpointTpl)) GlobalStore.remove("OTP");   // never reused - next verify asks again

            // ================= NEGATIVE TEST THAT STILL SENT AN OTP → ask the tester to confirm =================
            if (generatedNegative && httpStatus < 400 && otpWasSent(finalEndpoint, normalized)) {
                OtpContext.Generation ng = new OtpContext.Generation();
                ng.tcId = effectiveTcId;
                ng.apiName = apiName;
                ng.endpoint = finalEndpoint;
                ng.body = finalPayload;
                ng.serverMessage = ResponseAnalyzer.businessMessage(normalized);
                OtpContext.describe(ng, normalized);
                OtpResult seen = askOtp(effectiveTcId, apiName, finalEndpoint, finalPayload, null, 1, ng);
                otpNote = "Invalid data still sent an OTP to " + ng.target + " - "
                        + (seen.enteredByTester() ? "tester confirmed the OTP was received" : seen.defaulted ? "OTP not entered within the time limit" : "tester skipped");
            }
            String responseBody = pretty(bodyText);

            System.out.println("RAW RESPONSE:\n" + PiiMasker.maskSecrets(responseBody));

            // ================= EXTRACTION =================
            if (!generatedNegative) applyExtraction(normalized, GlobalStore.resolve(extractRule), response::getHeader);

            if (otpGeneration) {
                captureOtpHint(normalized);
                generation = new OtpContext.Generation();
                generation.tcId = effectiveTcId;
                generation.apiName = apiName;
                generation.method = method;
                generation.endpoint = finalEndpoint;
                generation.body = finalPayload;
                generation.headers = headersCell;
                generation.extractRule = extractRule;
                generation.serverMessage = ResponseAnalyzer.businessMessage(normalized);
                OtpContext.record(generation, normalized);
                LiveDashboard.log("INFO", effectiveTcId, "OTP requested → " + generation.targetLabel + " " + generation.target
                        + (generation.serverMessage == null ? "" : " (" + generation.serverMessage + ")"));
            }

            // ================= TEST DATA =================
            String testData = negativeRow ? "NA" : JsonDiffUtil.diff(rawBody, finalPayload);
            if (testData == null || testData.equals("{}")) {
                testData = "NA";
            }
            if (negativeRow) testData = payloadObj.getNegTitle() + ": " + payloadObj.getNegChange();

            // ================= VALIDATION =================
            LiveDashboard.step(effectiveTcId, "Validating response (" + (page == null ? "generic rules" : page.name()) + ")");
            ApiResponse apiResponse = new ApiResponse(effectiveTcId, method, finalEndpoint, shownPayload,
                    httpStatus, normalized, execTime);
            PageResult pageResult = page != null ? page.validate(apiResponse) : generic(apiResponse);

            List<Check> checks = new ArrayList<>(pageResult.checks);
            List<Check> ruleChecks = ValidationUtil.validate(normalized, validateRule);
            checks.addAll(ruleChecks);
            Check failedRule = ruleChecks.stream().filter(c -> !c.pass).findFirst().orElse(null);

            TestDecision decision;
            VaptRules.Verdict vapt = null;
            if (generatedNegative) {
                // ---- VAPT verdict: FAIL only with proof of security impact (see VaptRules) ----
                VaptRules.Input vin = new VaptRules.Input();
                vin.tc = effectiveTcId;
                vin.category = payloadObj.getNegCategory();
                vin.method = method;
                vin.endpoint = endpointTpl;
                vin.field = VaptRules.fieldOf(payloadObj.getNegChange());
                vin.http = httpStatus;
                vin.contentType = response.getContentType();
                vin.response = normalized;
                vin.pageRejected = !pageResult.pass || VaptRules.businessRejected(normalized);
                vin.encryptedBody = WIRE_REQ.get() != null && !method.matches("GET|HEAD|OPTIONS");
                vin.twoUser = payloadObj.getMode() == ResolvedPayload.Mode.IDOR_B;
                vin.positiveResponse = vin.twoUser ? VaptRules.IDOR_A.get(baseTc) : VaptRules.POSITIVE_BODY.get(baseTc);
                vapt = VaptRules.evaluate(vin);
                if (vapt.outcome == VaptRules.Outcome.PASS && "INJECTION".equals(vin.category) && httpStatus < 400
                        && ConfigManager.getBool("vapt.sqli.proofs", true)) {
                    vin.proof = sqliProof(method, finalEndpoint, finalPayload, finalHeaders, normalized);
                    if (vin.proof != null) vapt = VaptRules.evaluate(vin);
                }
                decision = vapt.outcome == VaptRules.Outcome.PASS ? new TestDecision(TestOutcome.PASS, vapt.reason)
                        : vapt.outcome == VaptRules.Outcome.FAIL ? TestDecision.fail(vapt.reason)
                        : new TestDecision(TestOutcome.SKIP, vapt.reason);
                if (httpStatus < 400 && !vin.pageRejected) {
                    VaptRules.ACCEPTED.computeIfAbsent(baseTc, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(effectiveTcId);
                }
                checks.add(0, new Check("Invalid input handled", payloadObj.getNegExpected(),
                        decision.reason, decision.outcome == TestOutcome.PASS));
                // ---- right HTTP status code? (e.g. no session must be 401, not 422) ----
                if (expectHttp != null) {
                    boolean codeOk = expectHttp.matches(httpStatus);
                    checks.add(1, new Check("HTTP status code", expectHttp.text() + " - " + expectHttp.why,
                            httpStatus + " " + com.api.automation.negative.ExpectedStatus.name(httpStatus), codeOk));
                    reportRow.put("HTTP_Check", codeOk ? "OK" : "MISMATCH");
                    if (!codeOk && decision.outcome == TestOutcome.PASS) {
                        String note = "Wrong status code: got " + httpStatus + " " + com.api.automation.negative.ExpectedStatus.name(httpStatus)
                                + ", the standard is " + expectHttp.text() + " because " + expectHttp.why
                                + (expectHttp.owasp.isEmpty() ? "" : " [VAPT: " + expectHttp.owasp + "]");
                        decision = ConfigManager.getBool("negative.strict.status", false)
                                ? TestDecision.fail("Rejected, but with the wrong status code: " + note)
                                : new TestDecision(TestOutcome.PASS, decision.reason + "  \u26A0 " + note);
                    }
                }
            } else if (negative) {
                boolean rejected = httpStatus >= 400 || !pageResult.pass || failedRule != null;
                String apiMessage = pageResult.message.isEmpty() ? "HTTP " + httpStatus : pageResult.message;
                checks.add(0, new Check("Invalid input rejected", "rejected (4xx or business error, no 5xx)",
                        rejected ? "rejected: " + apiMessage : "accepted", rejected && httpStatus < 500));

                if (httpStatus >= 500) {
                    decision = TestDecision.fail("Server error HTTP " + httpStatus
                            + " for invalid input - should return a proper validation message");
                } else if (rejected) {
                    decision = new TestDecision(TestOutcome.PASS, "Rejected as expected: " + apiMessage);
                } else {
                    decision = TestDecision.fail("Invalid input was ACCEPTED by the API (expected rejection)");
                }
            } else {
                boolean statusOk = expectedStatus.isEmpty() || String.valueOf(httpStatus).equals(expectedStatus);
                checks.add(0, new Check("HTTP status", expectedStatus.isEmpty() ? "any" : expectedStatus,
                        String.valueOf(httpStatus), statusOk));

                boolean expectsError = statusOk && httpStatus >= 400;   // e.g. ExpectedStatus 404 / 401

                if (!statusOk) {
                    decision = TestDecision.fail("Expected status " + expectedStatus + " but got " + httpStatus
                            + (httpStatus >= 400 ? " (" + FailureAnalyzer.analyze(response) + ")" : "")
                            + (pageResult.message.isEmpty() ? "" : " - " + pageResult.message));
                } else if (expectsError) {
                    // An error status was expected and received: business "success" rules don't apply
                    checks.removeIf(c -> pageResult.checks.contains(c));
                    decision = failedRule != null
                            ? TestDecision.fail(failedRule.name + " → actual: " + failedRule.actual)
                            : new TestDecision(TestOutcome.PASS, "Expected error status " + httpStatus + " received");
                } else if (!pageResult.pass) {
                    decision = TestDecision.fail(pageResult.message);
                } else if (failedRule != null) {
                    decision = TestDecision.fail(failedRule.name + " → actual: " + failedRule.actual);
                } else {
                    decision = TestDecision.pass();
                }
            }

            if (generation != null) {
                generation.serverMessage = generation.serverMessage == null ? pageResult.actualSummary : generation.serverMessage;
                if (decision.outcome == TestOutcome.FAIL) generation.serverMessage = "⚠ " + decision.reason;
            }

            // ================= SLA =================
            int sla = ConfigManager.getInt("sla.ms", 5000);
            boolean slow = sla > 0 && execTime > sla;
            String actualResult = "HTTP " + httpStatus
                    + (pageResult.actualSummary.isEmpty() ? "" : " | " + pageResult.actualSummary)
                    + (slow ? " | SLOW (" + execTime + " ms > SLA " + sla + " ms)" : "");

            // ================= POSITIVE: right HTTP status code? =================
            if (!negativeRow) {
                boolean codeOk = fixedStatus ? String.valueOf(httpStatus).equals(expectedStatus)
                        : expectPos == null ? httpStatus < 300 : expectPos.matches(httpStatus);
                reportRow.put("HTTP_Check", codeOk ? "OK" : "MISMATCH");
            }

            // ================= STATE CHECK: compare with the snapshot taken right before this API's negatives =================
            // Not with the positive test of phase 1: later steps (update / delete of a round trip) legitimately change the
            // data in between. Changed = a negative the server ACCEPTED altered data or session (they are named).
            if (payloadObj != null && "STATE_CHECK".equals(payloadObj.getNegCategory())) {
                String snap = VaptRules.BASELINE.get(baseTc);
                List<String> acc = VaptRules.ACCEPTED.getOrDefault(baseTc, List.of());
                if (snap == null) {
                    decision = new TestDecision(TestOutcome.SKIP, "INCONCLUSIVE - no snapshot of the data before the negatives (the valid request could not be sent again)");
                    vapt = new VaptRules.Verdict(VaptRules.Outcome.INCONCLUSIVE, VaptRules.Cls.INCONCLUSIVE, "", decision.reason, null);
                } else if (VaptRules.sameData(snap, normalized)) {
                    decision = new TestDecision(TestOutcome.PASS, "Data unchanged: same response as the snapshot taken right before the negatives of this API");
                    vapt = new VaptRules.Verdict(VaptRules.Outcome.PASS, VaptRules.Cls.VAPT, "", decision.reason, null);
                } else if (acc.isEmpty()) {
                    decision = new TestDecision(TestOutcome.PASS, "The response differs from the snapshot, but every negative of this API was rejected,"
                            + " so the change did not come from them (other activity on the server)");
                    vapt = new VaptRules.Verdict(VaptRules.Outcome.PASS, VaptRules.Cls.VAPT, "", decision.reason, null);
                } else {
                    decision = TestDecision.fail("Data or session changed by the negatives of this API: the response differs from the snapshot taken"
                            + " right before them. Negatives the server accepted: " + String.join(", ", acc) + " [CWE-284]");
                    vapt = new VaptRules.Verdict(VaptRules.Outcome.FAIL, VaptRules.Cls.VAPT, "High", decision.reason, null);
                }
            }

            // ================= AI VERIFICATION =================
            AiVerdict ai = null;
            if (AiVerifier.enabled() && AiVerifier.isAvailable()) {
                LiveDashboard.step(effectiveTcId, "AI verifying expected vs actual");
                ai = AiVerifier.verify(apiName, method, finalEndpoint, negative, expectedResult,
                        shownPayload, httpStatus, normalized);
                if (ai.isFail() && decision.outcome == TestOutcome.PASS
                        && ConfigManager.getBool("ai.can.fail.test", false)) {
                    decision = TestDecision.fail("AI verification: " + ai.reason);
                }
            }

            // ================= REPORT =================
            reportRow.put("Endpoint", PiiMasker.maskForExcel(finalEndpoint));
            reportRow.put("RequestBody", PiiMasker.maskForExcel(shownPayload));
            // VAPT classification (VaptRules): VAPT / FUNCTIONAL / NA / INCONCLUSIVE, proven severity, leak evidence on every request
            VaptRules.Leak leakAny = VaptRules.findLeak(normalized);
            reportRow.put("Leak_Evidence", leakAny == null ? "" : leakAny.describe());
            if (vapt != null) {
                reportRow.put("VAPT_Class", vapt.cls.name());
                reportRow.put("VAPT_Severity", vapt.outcome == VaptRules.Outcome.FAIL && vapt.cls == VaptRules.Cls.VAPT ? vapt.severity : "");
                reportRow.put("VAPT_Point", vapt.point == null ? safe(payloadObj.getNegCategory()) : vapt.point);
                reportRow.put("NegField", safe(VaptRules.fieldOf(payloadObj.getNegChange())));
            }
            reportRow.put("Api_Kind", com.api.automation.negative.ApiKind.of(method, endpointTpl).name());
            reportRow.put("TestData", PiiMasker.maskForExcel(testData));
            reportRow.put("ActualResponse", PiiMasker.maskForExcel(responseBody));
            if (!otpNote.isEmpty()) actualResult += " | OTP: " + otpNote;
            String dataNote = GlobalStore.takeFallbackNotes();
            if (!dataNote.isEmpty()) actualResult += " | Data note: " + dataNote;
            reportRow.put("ActualResult", actualResult);
            reportRow.put("Result", decision.outcome.name());
            if (!negativeRow) {
                POSITIVE_OUTCOME.put(safe(row.get("TC_ID")), decision.outcome);
                POSITIVE_HTTP.put(safe(row.get("TC_ID")), httpStatus);
                VaptRules.POSITIVE_BODY.put(baseTc, normalized);
                VaptRules.POSITIVE_REQUEST.put(baseTc, new String[]{method, endpointTpl, rawBody, headersCell});
            }
            reportRow.put("AI_Verdict", ai == null ? "" : ai.toString());
            reportRow.put("Error", safe(decision.reason));
            reportRow.put("Exec Time", execTime + "ms");
            reportRow.put("HTTP", String.valueOf(httpStatus));
            String format = formatOf(finalPayload, response, WIRE_REQ.get() != null);
            String source = dataSource(row, payloadObj, rawBody, endpointTpl);
            String executedAt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(start),
                    java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
            reportRow.put("Format", format);
            reportRow.put("Data_Source", source);
            reportRow.put("Executed_At", executedAt);
            // what really went over the network (encrypted request / response), for the PDF evidence
            String wireReq = WIRE_REQ.get();
            String wireResp = WIRE_RESP.get();
            if (wireReq != null) {
                reportRow.put("WireRequest", PiiMasker.mask(wireReq));
                if (wireResp != null) reportRow.put("WireResponse", PiiMasker.mask(wireResp));
            }
            if (view != null) {
                view.format = format;
                view.dataSource = source;
                view.executedAt = executedAt;
            }

            // ================= FULL RESPONSE FILE (json + clickable viewer) =================
            String responseFile = com.api.automation.report.ResponseStore.save(effectiveTcId, apiName, method,
                    finalEndpoint, httpStatus, execTime, decision.outcome.name(), pretty(shownPayload), bodyText, start);
            if (responseFile != null) reportRow.put("ResponseFile", responseFile);

            // ================= DASHBOARD =================
            if (view != null) {
                view.httpStatus = httpStatus;
                view.expectedHttp = expectedHttpText;
                view.httpCheck = reportRow.getOrDefault("HTTP_Check", "");
                view.vapt = reportRow.getOrDefault("VAPT_Ref", "");
                setUrl(view, PiiMasker.mask(finalEndpoint));
                view.timeMs = execTime;
                view.slow = slow;
                view.actual = actualResult;
                view.reason = decision.reason;
                view.aiVerdict = ai == null ? null : ai.verdict;
                view.aiReason = ai == null ? null : ai.reason;
                view.requestHeaders = PiiMasker.maskSecrets(finalHeaders.replace(";", "\n"));
                view.wireRequest = WIRE_REQ.get() == null ? null : PiiMasker.mask(WIRE_REQ.get());
                String wr = WIRE_RESP.get();
                view.encrypted = view.wireRequest != null
                        || (wr != null && com.api.automation.security.EnvelopeCrypto.isEnvelope(wr));
                view.wireResponse = view.encrypted && wr != null ? PiiMasker.mask(wr) : null;
                view.request = truncate(PiiMasker.mask(method + " " + finalEndpoint
                        + (shownPayload.isBlank() ? "" : "\n\n" + pretty(shownPayload))), 20000);
                view.response = truncate(PiiMasker.mask(responseBody), 20000);
                view.testData = "NA".equals(testData) ? null : PiiMasker.maskTestData(testData);
                view.checks = checks;
                view.status = decision.outcome.name();
                view.responseFile = responseFile;
            }
            LiveDashboard.testFinished(effectiveTcId);

            // ================= ALLURE =================
            String allureTitle =
                    decision.outcome == TestOutcome.FAIL && decision.reason != null
                            ? effectiveTcId + " | FAILED | " + decision.reason
                            : effectiveTcId + " | " + decision.outcome.name();

            Allure.step(
                    (decision.outcome == TestOutcome.PASS ? "✅ " :
                            decision.outcome == TestOutcome.SKIP ? "⚠️ " : "❌ ")
                            + allureTitle,
                    decision.outcome == TestOutcome.PASS ? Status.PASSED :
                            decision.outcome == TestOutcome.SKIP ? Status.SKIPPED :
                            Status.FAILED
            );

            Allure.addAttachment("Request Details",
                    "Method: " + method +
                            "\nEndpoint: " + PiiMasker.mask(finalEndpoint) +
                            "\n\nBody:\n" + PiiMasker.mask(shownPayload));
            Allure.addAttachment("Response Details",
                    "Status Code: " + httpStatus +
                            "\nExecution Time: " + execTime + " ms" +
                            "\n\nResponse:\n" + PiiMasker.mask(responseBody));
            Allure.addAttachment("Expected vs Actual",
                    "Expected Result: " + expectedResult +
                            "\nActual Result: " + actualResult +
                            "\nExpected Status: " + (negative ? "rejection" : expectedStatus) +
                            "\nActual Status: " + httpStatus);
            Allure.addAttachment("Validation Checks", joinChecks(checks));
            Allure.addAttachment("Test Data (Delta)", PiiMasker.mask(testData));
            Allure.addAttachment("Extracted Values", PiiMasker.mask(GlobalStore.getAll().toString()));
            Allure.addAttachment("Validation Result",
                    "Outcome: " + decision.outcome.name() +
                            (decision.reason != null ? "\nReason: " + decision.reason : "") +
                            (ai != null ? "\nAI Verdict: " + ai : ""));

            System.out.println("GlobalStore: " + PiiMasker.mask(GlobalStore.getAll().toString()));
            return decision;

        } catch (Exception e) {
            // ✅ failed tests are still written to the Excel report
            String reason = "Exception: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            reportRow.put("ActualResponse", "Exception occurred");
            reportRow.put("ActualResult", reason);
            reportRow.put("Result", "FAIL");
            reportRow.put("Error", reason);
            if (view != null) {
                view.status = "FAIL";
                view.reason = reason;
            }
            LiveDashboard.testFinished(effectiveTcId);
            Allure.step("❌ " + effectiveTcId + " | " + reason, Status.FAILED);
            e.printStackTrace();
            return TestDecision.fail(reason);
        }
    }

    /** Full URL + its parts for the console (base URL / API path). */
    public static void setUrl(TestView view, String url) {
        if (view == null || url == null || url.contains("${")) return;
        view.endpoint = url;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://[^/]+)(/.*)?$").matcher(url);
        if (m.find()) {
            view.baseUrl = m.group(1);
            view.apiPath = m.group(2) == null ? "/" : m.group(2);
        }
    }

    // ================================================================
    // 🔹 NEGATIVE TESTS
    // ================================================================
    private static final ThreadLocal<ResolvedPayload.Mode> NEG_MODE = new ThreadLocal<>();
    /** Result of each positive test by TC_ID; its negatives only run after a PASS. */
    public static final Map<String, TestOutcome> POSITIVE_OUTCOME = new java.util.concurrent.ConcurrentHashMap<>();
    /** HTTP status of each positive test by TC_ID (0 = not called). */
    public static final Map<String, Integer> POSITIVE_HTTP = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Can the negatives of this positive test run? null = yes, otherwise the reason why not.
     * They run after a PASS, and also after a FAIL where the API itself answered (e.g. 400 / 500 / wrong data),
     * because the invalid-input and security checks still mean something. Not when the endpoint is
     * not deployed / not allowed / down (401, 403, 404, 405, 501-504) or the positive was skipped.
     */
    public static String negativesBlockedReason(String tc) {
        TestOutcome po = POSITIVE_OUTCOME.get(tc);
        int http = POSITIVE_HTTP.getOrDefault(tc, 0);
        if (po == TestOutcome.PASS) return null;
        if (po == TestOutcome.FAIL && http > 0 && !java.util.Set.of(401, 403, 404, 405, 501, 502, 503, 504).contains(http)) return null;
        if (po == null || po == TestOutcome.SKIP) return "Positive test " + tc + " was not executed";
        return "Positive test " + tc + " failed with HTTP " + http + (http == 404 ? " (endpoint not deployed)"
                : http == 403 ? " (no permission / feature disabled)" : http == 401 ? " (not authorised)" : http >= 502 ? " (server/gateway down)" : "");
    }

    /** "TC175 HTTP 403" when every source step of a value FAILED (not just returned no data), else null. */
    private static String failedSources(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("TC\\d+").matcher(source);
        List<String> failed = new ArrayList<>();
        boolean any = false;
        while (m.find()) {
            any = true;
            String tc = m.group();
            if (POSITIVE_OUTCOME.get(tc) != TestOutcome.FAIL) return null;
            failed.add(tc + " HTTP " + POSITIVE_HTTP.getOrDefault(tc, 0));
        }
        return any ? String.join(", ", failed) : null;
    }
    /**
     * State-check snapshot: the valid request of this API sent once more right before its first negative, so the
     * STATE_CHECK at the end compares with the data as it was at that moment (read APIs only).
     */
    private static void takeBaseline(String tc) {
        if (tc.isEmpty() || VaptRules.BASELINE.containsKey(tc)) return;
        String[] req = VaptRules.POSITIVE_REQUEST.get(tc);
        if (req == null || com.api.automation.negative.ApiKind.of(req[0], req[1]).writes()) return;
        try {
            String ep = GlobalStore.resolve(req[1]);
            String body = GlobalStore.resolve(req[2]);
            String headers = GlobalStore.resolve(req[3]);
            if (GlobalStore.findUnresolved(ep + " " + body + " " + headers) != null || needsOtp(body, ep)) return;
            com.api.automation.security.SessionFilter.tamper(com.api.automation.security.SessionFilter.Tamper.NONE);
            Response r = send(req[0], ep, body, headers);
            VaptRules.BASELINE.put(tc, ResponseHandler.normalize(readBody(r)));
        } catch (Exception e) {
            System.out.println("⚠ State-check snapshot of " + tc + " not taken: " + e.getMessage());
        }
    }

    private static final String SQLI_TRUE = "' OR '1'='1";
    private static final String SQLI_TRUE_URL = "%27%20OR%20%271%27%3D%271";

    /**
     * SQL injection proof for an injection test the API accepted (vapt.sqli.proofs=true):
     *   boolean differential - ' OR '1'='1 returns more rows than ' AND '1'='2 and than a random value;
     *   time based           - a sleep probe (MySQL / SQL Server / PostgreSQL / Oracle) delays the response by
     *                          vapt.sqli.time.seconds (5) or more, confirmed by sending it twice.
     * Read APIs only (the generator never sends data probes to state-changing APIs). null = no proof.
     */
    private static String sqliProof(String method, String endpoint, String body, String headers, String trueResponse) {
        boolean inBody = body != null && body.contains(SQLI_TRUE);
        boolean inUrl = endpoint.contains(SQLI_TRUE_URL);
        if (!inBody && !inUrl) return null;
        String wireReq = WIRE_REQ.get(), wireResp = WIRE_RESP.get();   // keep the evidence of the test itself
        try {
            java.util.function.Function<String, Object[]> call = v -> {
                String ep = inUrl ? endpoint.replace(SQLI_TRUE_URL,
                        java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")) : endpoint;
                String b = inBody ? body.replace(SQLI_TRUE, v.replace("\\", "\\\\").replace("\"", "\\\"")) : body;
                com.api.automation.security.SessionFilter.tamper(com.api.automation.security.SessionFilter.Tamper.NONE);
                long t = System.currentTimeMillis();
                Response r = send(method, ep, b, headers);
                String txt = ResponseHandler.normalize(readBody(r));
                return new Object[]{r.getStatusCode(), txt, System.currentTimeMillis() - t};
            };
            Object[] f = call.apply("' AND '1'='2");
            Object[] x = call.apply("ZQ" + Long.toHexString(System.nanoTime()));
            int tr = VaptRules.rowCount(trueResponse), fr = VaptRules.rowCount((String) f[1]), xr = VaptRules.rowCount((String) x[1]);
            if ((int) f[0] < 400 && (int) x[0] < 400 && tr > fr && tr > xr) {
                return "boolean differential - the true probe ' OR '1'='1 returned " + tr + " rows, the false probe ' AND '1'='2 returned "
                        + fr + " and a random value " + xr;
            }
            if (ConfigManager.getBool("vapt.sqli.time", true)) {
                long normal = (long) x[2];
                long limit = normal + VaptRules.delaySeconds() * 900L;
                for (String probe : VaptRules.timeProbes()) {
                    Object[] p = call.apply(probe);
                    if ((long) p[2] < limit) continue;
                    Object[] again = call.apply(probe);
                    if ((long) again[2] >= limit) {
                        return "time based - the probe " + probe + " delayed the response to " + p[2] + " ms and " + again[2]
                                + " ms (normal " + normal + " ms)";
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("⚠ SQL injection probes not completed: " + e.getMessage());
            return null;
        } finally {
            WIRE_REQ.set(wireReq);
            WIRE_RESP.set(wireResp);
        }
    }

    /**
     * Two-user IDOR check: sends a positive request of this row with the CURRENT session (user A) and applies its
     * Extract rule, so later requests use user A's own ids. Returns {httpStatus, decrypted response}; null when the
     * request cannot be sent (missing values / OTP needed).
     */
    public static String[] callAsCurrentUser(Map<String, String> row, ResolvedPayload p) {
        String method = safe(row.get("Method")).toUpperCase();
        String ep = GlobalStore.resolve(p != null && p.getEndpointOverride() != null ? p.getEndpointOverride() : safe(row.get("Endpoint")));
        String body = GlobalStore.resolve(p == null ? "" : p.getBody());
        String headers = GlobalStore.resolve(safe(row.get("Headers")));
        if (GlobalStore.findUnresolved(ep + " " + body + " " + headers) != null || needsOtp(body, ep)) return null;
        try {
            com.api.automation.security.SessionFilter.tamper(com.api.automation.security.SessionFilter.Tamper.NONE);
            Response r = send(method, ep, body, headers);
            String text = ResponseHandler.normalize(readBody(r));
            if (r.getStatusCode() < 400) applyExtraction(text, GlobalStore.resolve(safe(row.get("Extract"))), r::getHeader);
            return new String[]{String.valueOf(r.getStatusCode()), text};
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // 🔹 REQUEST
    // ================================================================
    // ── -Dcrypto.trace=<part of endpoint> : print + save every stage of that call (for documentation) ──
    private static final ThreadLocal<StringBuilder> TRACE = new ThreadLocal<>();

    private static void trace(String title, String text) {
        StringBuilder sb = TRACE.get();
        if (sb == null) return;
        String block = "\n==================== " + title + " ====================\n" + text + "\n";
        sb.append(block);
        System.out.print(block);
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + " ...(" + s.length() + " chars)" : s;
    }

    /** "JSON → JSON", "XML → XML", "JSON (encrypted) → PDF", "no body → JSON" */
    private static String formatOf(String body, Response r, boolean encrypted) {
        String b = body == null ? "" : body.trim();
        String req = b.isEmpty() ? "no body" : b.startsWith("<") ? "XML" : (b.startsWith("{") || b.startsWith("[")) ? "JSON" : "TEXT";
        if (encrypted && !b.isEmpty()) req += " (encrypted)";
        String ct = r == null || r.getContentType() == null ? "" : r.getContentType().toLowerCase();
        String resp = ct.contains("json") ? "JSON" : ct.contains("xml") ? "XML" : ct.contains("html") ? "HTML"
                : ct.contains("pdf") ? "PDF" : ct.startsWith("image/") ? "IMAGE" : (ct.contains("octet") || ct.contains("zip")) ? "FILE"
                : ct.contains("text") ? "TEXT" : ct.isEmpty() ? "none" : ct;
        return req + " → " + resp;
    }

    private static final java.util.regex.Pattern VAR_USE = java.util.regex.Pattern.compile("\\$\\{(?:json:)?([A-Za-z_][\\w|]*)\\??}");
    private static final java.util.Set<String> SECRET_VARS = java.util.Set.of("token", "csrf_token", "password", "OTP", "OTP_HINT");

    /**
     * Where this test's data came from (traceability):
     *   CKYC_testdata.xlsx › Sheet1 › row 48
     *   Payload: payloads/CKYC/DVS04_Individual_Checker_Details.json
     *   Generated: negative of TC047 - rule TAMPERED_TOKEN
     *   cmId = 3  ← TC046 02 DVS > Individual - Maker Queue (live)
     */
    private static String dataSource(Map<String, String> row, ResolvedPayload p, String rawBody, String endpointTpl) {
        StringBuilder b = new StringBuilder();
        String file = java.nio.file.Paths.get(ProjectContext.testDataPath()).getFileName().toString();
        b.append(file).append(" › ").append(row.getOrDefault("__SHEET", "sheet 1")).append(" › row ").append(row.getOrDefault("__ROW", "?"));
        String pl = safe(row.get("Payload")).trim();
        if (!pl.isEmpty() && !"NA".equalsIgnoreCase(pl) && !pl.startsWith("{") && !pl.startsWith("[") && !pl.startsWith("<")) {
            b.append("\nPayload: payloads/").append(ProjectContext.current()).append("/").append(pl);
        }
        if (p != null && p.getNegCode() != null) {
            b.append("\nGenerated: ").append(p.isVerifyPositive() ? "check" : "negative").append(" of ").append(safe(row.get("TC_ID")))
                    .append(" - rule ").append(p.getNegCategory());
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = VAR_USE.matcher(safe(rawBody) + " " + safe(endpointTpl));
        while (m.find()) {
            for (String name : m.group(1).split("\\|")) {
                if (SECRET_VARS.contains(name) || !GlobalStore.has(name)) continue;
                if (seen.add(name)) {
                    String src = VALUE_SOURCE.get(name);
                    b.append("\n").append(name).append(" = ").append(PiiMasker.mask(GlobalStore.get(name)))
                            .append("  ← ").append(src == null ? "config / Set column" : src.split(" / ")[0] + " (live)");
                }
                break;   // first available value of a ${a|b|c} chain is the one used
            }
        }
        return b.toString();
    }

    /** Set by the runner when the first login fails: every later test is skipped with this reason (cleared at the start of a run). */
    public static volatile String ABORT_REASON;

    /** One call with the project's encryption and session handling; returns {httpStatus, decrypted response body}. Used by the auto-unlock helper. */
    public static String[] callRaw(String method, String endpointTpl, String bodyTpl, String headersTpl) {
        String ep = GlobalStore.resolve(endpointTpl);
        String body = GlobalStore.resolve(bodyTpl);
        String headers = GlobalStore.resolve(headersTpl);
        com.api.automation.security.SessionFilter.tamper(com.api.automation.security.SessionFilter.Tamper.NONE);
        Response r = send(method, ep, body, headers);
        String text = readBody(r);
        return new String[]{String.valueOf(r.getStatusCode()), text};
    }

    /** What really went over the network for the current call (shown in the console details panel). */
    private static final ThreadLocal<String> WIRE_REQ = new ThreadLocal<>();
    private static final ThreadLocal<String> WIRE_RESP = new ThreadLocal<>();

    private static Response send(String method, String endpoint, String body, String headers) {
        WIRE_REQ.set(null);
        WIRE_RESP.set(null);
        String tr = System.getProperty("crypto.trace", "");
        TRACE.set(!tr.isBlank() && endpoint.contains(tr) ? new StringBuilder() : null);
        if (TRACE.get() != null) {
            trace("1. PLAIN REQUEST (what the test data / app screen builds)",
                    method + " " + endpoint + "\nBody:\n" + PiiMasker.maskSecrets(pretty(body == null ? "" : body)));
        }

        // very large bodies (1 MB / 10 MB checks) are not written to the console log or Allure
        RequestSpecification spec = RequestBuilder.getRequest(headers, body == null || body.length() <= 100_000);
        if (body != null && body.trim().startsWith("<")) {
            spec.contentType(ConfigManager.get("xml.content.type", "application/xml"));   // XML / SOAP body
        }
        boolean plainOnPurpose = NEG_MODE.get() == ResolvedPayload.Mode.PLAIN_BODY;
        if (body != null && !body.isBlank()) {
            // GET calls normally have no body; a GET row WITH a payload is encrypted too
            if (com.api.automation.security.EnvelopeCrypto.enabled() && !skipEncryption(endpoint) && !plainOnPurpose) {
                // Same hybrid RSA-OAEP + AES-256-GCM envelope the web app sends
                try {
                    com.api.automation.security.EnvelopeCrypto.Envelope env =
                            com.api.automation.security.EnvelopeCrypto.encrypt(body);
                    String secret = env.clientSecret;
                    if (NEG_MODE.get() == ResolvedPayload.Mode.TAMPER_ENCRYPTION) {
                        // corrupt the wrapped AES key: the server must refuse to decrypt
                        secret = new StringBuilder(secret).reverse().toString();
                    }
                    spec.body(env.body)
                            .header("clientsecret", secret)
                            .header("clientpublickey", env.clientPublicKey);
                    WIRE_REQ.set("Header clientsecret    : " + cut(secret, 90)
                            + "\n   (random AES-256 key, RSA-OAEP encrypted with the server public key"
                            + (NEG_MODE.get() == ResolvedPayload.Mode.TAMPER_ENCRYPTION ? " - DELIBERATELY CORRUPTED for this negative test" : "") + ")\n"
                            + "Header clientpublickey : " + cut(env.clientPublicKey, 90)
                            + "\n   (this run's RSA public key - the server encrypts its reply with it)\n\n"
                            + "Body:\n" + cut(env.body, 3000)
                            + "\n   (payload = Base64( 12-byte IV + AES-256-GCM ciphertext + 16-byte tag ))");
                    trace("2. ENCRYPTED REQUEST (what actually goes over the network)",
                            "Header clientsecret     : " + cut(env.clientSecret, 60) + "   <- random AES-256 key, encrypted with the SERVER public RSA key\n"
                                    + "Header clientpublickey  : " + cut(env.clientPublicKey, 60) + "   <- this run's own RSA public key (server encrypts the reply with it)\n"
                                    + "Body                    : " + cut(env.body, 140) + "\n"
                                    + "                          (payload = Base64( 12-byte IV + AES-256-GCM ciphertext + 16-byte tag ))");
                } catch (Exception e) {
                    throw new IllegalStateException("Request encryption failed: " + e.getMessage(), e);
                }
            } else {
                spec.body(body);
                if (plainOnPurpose) {
                    WIRE_REQ.set("Body sent as PLAIN JSON on purpose (no clientsecret / clientpublickey headers, no envelope) - "
                            + "negative test: an encrypted API must refuse unencrypted requests.\n\n" + cut(PiiMasker.mask(body), 3000));
                }
                trace("2. REQUEST SENT AS PLAIN JSON", "This endpoint is not encrypted (crypto.skip.paths / envelope off).");
            }
        }
        return spec.request(method, endpoint);
    }

    private static void saveTrace(Response r) {
        StringBuilder sb = TRACE.get();
        if (sb == null) return;
        TRACE.remove();
        try {
            java.nio.file.Path out = ProjectContext.reportFolder().resolve("Encryption_Trace_"
                    + System.getProperty("crypto.trace").replaceAll("[^A-Za-z0-9]+", "_") + ".txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Encryption trace saved: " + out);
        } catch (Exception ignored) {
            // trace is only for documentation
        }
    }

    /** crypto.skip.paths=/email/,/x/ - modules the app calls with skipEncryption (plain JSON). */
    private static boolean skipEncryption(String endpoint) {
        String paths = ConfigManager.get("crypto.skip.paths", "");
        for (String p : paths.split(",")) {
            if (!p.isBlank() && endpoint.contains(p.trim())) return true;
        }
        return false;
    }

    /** Response text; decrypted when the project uses the encrypted envelope. */
    private static String readBody(Response r) {
        String ct = r.getContentType() == null ? "" : r.getContentType().toLowerCase();
        if (ct.startsWith("image/") || ct.contains("pdf") || ct.contains("octet-stream") || ct.contains("zip")) {
            return "[binary file: " + ct + ", " + r.asByteArray().length + " bytes]";
        }
        String raw = r.asString();
        WIRE_RESP.set(cut(raw, 6000));
        trace("3. RAW RESPONSE (as received from the server)", "HTTP " + r.getStatusCode() + "\n" + cut(raw, 300));
        if (!com.api.automation.security.EnvelopeCrypto.enabled()
                || !com.api.automation.security.EnvelopeCrypto.isEnvelope(raw)) {
            saveTrace(r);
            return raw;
        }
        try {
            String plain = com.api.automation.security.EnvelopeCrypto.decrypt(raw, r::getHeader);
            System.out.println("🔓 Response decrypted");
            trace("4. DECRYPTED RESPONSE (what the test validates and the report shows)",
                    cut(PiiMasker.mask(pretty(plain)), 1500));
            saveTrace(r);
            return plain;
        } catch (Exception e) {
            System.out.println("⚠ Response decryption failed: " + e.getMessage());
            return raw;
        }
    }

    // ================================================================
    // 🔹 VALIDATION
    // ================================================================
    private static PageResult generic(ApiResponse r) {
        ValidationResult result = ResponseAnalyzer.analyze(r.body);
        String msg = ResponseAnalyzer.businessMessage(r.body);
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Business status (generic rules)", "success", result.isPass ? "success" : result.message, result.isPass));
        return new PageResult(result.isPass, result.message, msg == null ? "" : msg, checks);
    }

    private static String expectedResult(Map<String, String> row, ApiPage page, boolean negative,
                                         String expectedStatus, String label) {
        if (negative) {
            return "Invalid input (" + label + ") must be rejected with a proper error message (no 5xx)";
        }
        String fromExcel = safe(row.get("ExpectedResult"));
        if (!fromExcel.isEmpty() && !fromExcel.equalsIgnoreCase("NA")) return fromExcel;
        if (page != null) return page.expectedResult();
        return "HTTP " + (expectedStatus.isEmpty() ? "2xx" : expectedStatus) + " with a successful business status";
    }

    // ================================================================
    // 🔹 EXTRACTION + OTP HELPERS
    // ================================================================
    private static boolean promptMode() {
        return !"RESPONSE".equalsIgnoreCase(ConfigManager.get("otp.source", "PROMPT"));
    }

    /**
     * Stores extracted values.
     * PROMPT mode: an "OTP=..." extract rule is IGNORED - the OTP is always typed in the popup.
     */
    /** Excel "Set" column: NAME=value;NAME2=value - stored before the call (values may use ${NOW:..} etc). */
    private static void applySet(String rule) {
        if (rule.isEmpty() || rule.equalsIgnoreCase("NA")) return;
        for (String pair : rule.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).trim();
            String value = GlobalStore.resolve(pair.substring(eq + 1).trim());
            GlobalStore.put(key, value);
            System.out.println("Set " + key + " = " + PiiMasker.maskSecrets(key + "=" + value).substring(key.length() + 1));
        }
    }

    private static void applyExtraction(String normalized, String rule,
                                        java.util.function.Function<String, String> headers) {
        String effectiveRule = rule;
        if (promptMode() && rule != null && rule.toUpperCase().contains("OTP=")) {
            effectiveRule = java.util.Arrays.stream(rule.split(";"))
                    .filter(p -> !p.trim().toUpperCase().startsWith("OTP="))
                    .collect(java.util.stream.Collectors.joining(";"));
            System.out.println("ℹ OTP extract rule ignored (otp.source=PROMPT) - OTP will be entered in the popup");
        }
        ExtractionUtil.extract(normalized, effectiveRule, headers).forEach(GlobalStore::put);
    }

    /** RESPONSE mode only: take the OTP from the generation response. PROMPT mode never reads it. */
    private static void captureOtpHint(String normalized) {
        if (promptMode()) return;
        io.restassured.path.json.JsonPath json = ResponseHandler.jsonOrNull(normalized);
        if (json == null) return;
        for (String path : OTP_HINT_PATHS) {
            String otp = ResponseAnalyzer.str(json, path);
            if (otp != null && otp.matches("[A-Za-z0-9]{4,8}")) {
                if (!GlobalStore.has("OTP")) {
                    GlobalStore.put("OTP", otp);
                    System.out.println("🔥 OTP Auto Captured from [" + path + "] → " + otp);
                }
                return;
            }
        }
    }

    private static boolean needsOtp(String payload, String endpoint) {
        return (payload != null && payload.contains("${OTP}")) || (endpoint != null && endpoint.contains("${OTP}"));
    }

    /**
     * Opens the OTP popup.
     * @param error       shown in red (e.g. wrong OTP - enter it again)
     * @param attempt     1 = first ask, 2 = after a wrong OTP
     * @param negativeGen set when a NEGATIVE request still sent an OTP (its own target / OVD)
     */
    private static OtpResult askOtp(String tcId, String apiName, String endpoint, String body,
                                    String error, int attempt, OtpContext.Generation negativeGen) {
        OtpRequest req = new OtpRequest();
        req.project = ProjectContext.displayName();
        req.tcId = tcId;
        req.apiName = apiName;
        req.endpoint = PiiMasker.mask(endpoint);
        req.hint = null;   // OTP is never pre-filled from a response
        req.error = error;
        req.attempt = attempt;
        req.canResend = false;

        OtpContext.Generation g = negativeGen != null ? negativeGen : OtpContext.last();
        if (g != null) {
            req.channel = g.channel;
            req.target = g.target;
            req.targetLabel = g.targetLabel;
            req.ovd = g.ovd;
            req.ovdLabel = g.ovdLabel;
            req.sentBy = g.tcId + " · " + g.apiName;
            req.serverMessage = g.serverMessage;
        } else {
            String[] t = OtpContext.detectTarget(endpoint, body, null);
            req.channel = t[0];
            req.target = t[1];
            req.targetLabel = t[2];
        }
        if (req.ovd == null) {
            String[] o = OtpContext.detectOvd(endpoint + " " + body, null);
            if (o != null) {
                req.ovdLabel = o[0];
                req.ovd = o[1];
            }
        }
        if (negativeGen != null) {
            req.negative = true;
            req.note = "This NEGATIVE test (invalid data) still sent an OTP. Enter the OTP you received to confirm it was really sent.";
        }

        LiveDashboard.step(tcId, "Waiting for OTP - sent to " + req.targetLabel + " " + req.target);
        return OtpService.obtain(req, ExecutionEngine::resendOtp);
    }

    private static final java.util.regex.Pattern WRONG_OTP = java.util.regex.Pattern.compile(
            "(?i)(invalid|incorrect|wrong|mismatch|does not match|not match|expired|not valid|failed)[^\"]{0,30}\\botp\\b"
                    + "|\\botp\\b[^\"]{0,40}(invalid|incorrect|wrong|mismatch|does not match|not match|expired|not valid|failed)");

    /** The verify call said the OTP itself was wrong / expired. */
    private static boolean isWrongOtp(int http, String normalized) {
        return normalized != null && WRONG_OTP.matcher(normalized).find() && (http >= 400 || !normalized.contains("\"success\""));
    }

    private static final java.util.regex.Pattern OTP_SENT = java.util.regex.Pattern.compile(
            "(?i)\\botp\\b[^\"]{0,40}\\b(sent|generated|delivered|triggered)\\b|\\b(sent|generated|delivered)\\b[^\"]{0,40}\\botp\\b");

    /** The response says an OTP was sent (used for negative tests). */
    private static boolean otpWasSent(String endpoint, String normalized) {
        return normalized != null && OTP_SENT.matcher(normalized).find()
                && !WRONG_OTP.matcher(normalized).find();
    }

    /** "Resend OTP" button: fires the last generation request again. */
    private static String resendOtp() {
        OtpContext.Generation g = OtpContext.last();
        if (g == null) throw new IllegalStateException("No OTP generation step found in this run");

        GlobalStore.remove("OTP_HINT");
        Response r = send(g.method, g.endpoint, g.body, g.headers);
        String normalized = ResponseHandler.normalize(readBody(r));
        applyExtraction(normalized, g.extractRule, r::getHeader);
        captureOtpHint(normalized);

        String msg = ResponseAnalyzer.businessMessage(normalized);
        g.serverMessage = msg;
        OtpContext.record(g, normalized);
        LiveDashboard.log(r.getStatusCode() < 400 ? "INFO" : "FAIL", null,
                "OTP resent via " + g.tcId + " → HTTP " + r.getStatusCode() + (msg == null ? "" : " - " + msg));
        if (r.getStatusCode() >= 400) {
            throw new IllegalStateException("HTTP " + r.getStatusCode() + (msg == null ? "" : " - " + msg));
        }
        return msg == null ? "OTP resent" : msg;
    }

    // ================================================================
    // 🔹 HELPERS
    // ================================================================
    private static TestDecision skip(String tcId, Map<String, String> reportRow,
                                     String endpoint, String payload, String reason) {
        reportRow.put("Endpoint", PiiMasker.maskForExcel(endpoint));
        reportRow.put("RequestBody", PiiMasker.maskForExcel(payload));
        reportRow.put("ActualResult", "Not executed");
        reportRow.put("Result", "SKIP");
        reportRow.put("Error", reason);
        TestView view = LiveDashboard.test(tcId);
        if (view != null) {
            view.status = "SKIP";
            view.reason = reason;
            view.request = PiiMasker.mask(endpoint + (payload == null || payload.isBlank() ? "" : "\n\n" + payload));
        }
        LiveDashboard.testFinished(tcId);
        Allure.step("⚠️ " + tcId + " | SKIPPED | " + reason, Status.SKIPPED);
        return TestDecision.skip(reason);
    }

    private static String joinChecks(List<Check> checks) {
        StringBuilder sb = new StringBuilder();
        for (Check c : checks) sb.append(c).append('\n');
        return sb.length() == 0 ? "No checks" : sb.toString();
    }

    private static String pretty(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.writerWithDefaultPrettyPrinter().writeValueAsString(m.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "\n...(truncated)" : s;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
