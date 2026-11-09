package com.api.product.ai;

import java.util.Locale;

import com.api.product.core.engine.Redactor;
import com.api.product.core.engine.TestResult;

/**
 * Plain-English explanation of one failed or blocked test: what happened, the most likely cause, what to do. The built-in explanation
 * works from the result itself (category, status, message) and is always available. When a language model is switched on it adds a
 * short wording of the same facts; the model gets only the redacted evidence and is told not to invent anything.
 */
public final class PlainExplainer {

    public static final class Explanation {
        public String headline = "";
        public String cause = "";
        public String fix = "";
        /** built-in | AI (model) */
        public String source = "built-in";
        public String aiText = "";
        public String aiNote = "";
    }

    private PlainExplainer() {}

    public static Explanation explain(TestResult r) {
        Explanation e = new Explanation();
        String remark = r.remark == null ? "" : r.remark;
        String lr = remark.toLowerCase(Locale.ROOT);
        String resp = r.responseEvidence == null ? "" : r.responseEvidence.toLowerCase(Locale.ROOT);
        String cat = r.failureCategory == null ? "" : r.failureCategory;
        Integer http = r.httpStatus;
        boolean positive = "POSITIVE".equals(r.category);

        if (resp.contains("token not available") || (positive && http != null && (http == 401 || http == 422) && (lr.contains("token") || resp.contains("token")))) {
            e.headline = "The server did not receive a sign-in token";
            e.cause = "The request went out without a valid token, so the server refused it. The login step did not run first or did not succeed (wrong or missing password, locked account, or a later test was run on its own).";
            e.fix = "Open the first login test of the run and read its response. If it says the account is locked, ask the administrator to unlock it. Enter the credentials (Overview, Set credentials) and run the whole project so the login runs first.";
        } else if (resp.contains("locked") && resp.contains("login")) {
            e.headline = "The account is locked";
            e.cause = "Too many wrong sign-in attempts locked the account, so no token can be issued.";
            e.fix = "Ask the administrator to unlock it, enter the correct password and run again. Login negative tests are limited per run to protect the account.";
        } else if ("AUTH_NOT_CONFIGURED".equals(cat)) {
            e.headline = "No sign-in is set up for this API";
            e.cause = "The API answered 401 or 403 and the project has no credentials for it.";
            e.fix = "Open Overview, Set credentials, and choose how the application signs in.";
        } else if ("INVALID_REQUEST_ACCEPTED".equals(cat)) {
            e.headline = "The API accepted a request it should have rejected";
            e.cause = "A deliberately wrong request (" + (r.category == null ? "" : r.category) + ") was answered with success" + (http == null ? "" : " (HTTP " + http + ")") + ". The server does not validate this input or this check.";
            e.fix = "Add server-side validation so the API answers a clear 4xx for this input" + (r.owasp == null || r.owasp.equals("-") ? "." : " (reference " + r.owasp + ").") + " If the behaviour is intended, edit the test's expected result.";
        } else if ("VALIDATION_MISSING".equals(cat)) {
            e.headline = "Input validation is missing";
            e.cause = "The wrong value was accepted without an error message and no data came back. It is harmless in effect but the API should reject it.";
            e.fix = "Return 400 or 422 with a message for this input (CWE-20).";
        } else if ("SERVER_ERROR_ON_BAD_INPUT".equals(cat)) {
            e.headline = "The server crashed on bad input";
            e.cause = "A wrong request caused an internal error (HTTP " + http + ") instead of a clean rejection.";
            e.fix = "Handle this input in the server: validate it and return 400. Check the server log for the exception at the time of this test.";
        } else if ("INFORMATION_LEAK".equals(cat)) {
            e.headline = "Internal details leaked in the response";
            e.cause = "The error response contains technical text (stack trace, SQL or class names) that helps an attacker.";
            e.fix = "Return a generic error message to the client and keep the details in the server log (CWE-209).";
        } else if ("UNEXPECTED_STATUS".equals(cat) || "POSITIVE_NOT_PASSING".equals(cat)) {
            e.headline = http != null && http == 404 ? "The address was not found" : http != null && http == 403 ? "The account is not allowed to call this API" : http != null && http == 405 ? "The method is not allowed" : "The valid request did not succeed";
            e.cause = "The valid request answered HTTP " + http + " but " + (r.expectedStatus == null ? "a success code" : r.expectedStatus.toString()) + " was expected. "
                    + (http != null && http == 404 ? "The path is wrong, the API is not deployed, or an id in the request does not exist." : http != null && http == 403 ? "The user lacks the permission or role for this API." : "The data in the request may not exist in this environment.");
            e.fix = "Check the full URL and the test data in the request on the right. Fix the valid request first: its negative checks run automatically afterwards.";
        } else if ("CONTRACT_VIOLATION".equals(cat)) {
            e.headline = "The response does not match the specification";
            e.cause = remark;
            e.fix = "Fix the API or update the specification, then use Compare with a newer spec.";
        } else if ("ASSERTION_FAILED".equals(cat)) {
            e.headline = "A check on the response failed";
            e.cause = remark;
            e.fix = "Open the response, compare it with the expectation of the test and correct either the API or the expected value.";
        } else if ("SOAP_FAULT".equals(cat) || "GRAPHQL_ERRORS".equals(cat)) {
            e.headline = "The service reported an error for a valid request";
            e.cause = remark;
            e.fix = "Check the request values against the service's rules; read the fault or error message in the response.";
        } else if ("STATE_CHANGED_BY_NEGATIVES".equals(cat)) {
            e.headline = "The bad requests changed the state of the system";
            e.cause = "The valid request worked at first but failed after the negative tests, so the bad inputs ended the session or changed data.";
            e.fix = "Review which negative test ran just before; the server should not change state for rejected input (OWASP API6).";
        } else if ("BLOCKED".equals(r.outcome) || "SKIPPED".equals(r.outcome)) {
            e.headline = "The test was not sent";
            e.cause = remark.isEmpty() ? "A prerequisite was missing." : remark;
            e.fix = lr.contains("credentials") ? "Enter the credentials (Overview, Set credentials)." : lr.contains("variable") ? "A value from an earlier step is missing: fix that step first (see Data flow)." : "Fix what the reason says; the test then runs automatically.";
        } else {
            e.headline = "The result did not match the expectation";
            e.cause = remark.isEmpty() ? "Expected " + r.expectedStatus + " but got " + http + "." : remark;
            e.fix = "Open the request and response, then correct the API or the test.";
        }
        return e;
    }

    /** Adds a model wording of the same facts when the AI engine is on. The explanation above is never replaced. */
    public static void addAi(Explanation e, TestResult r, OllamaClient llm) {
        if (llm == null || !llm.switchedOn()) {
            e.aiNote = "The AI engine is off (left menu, AI engine). The built-in explanation is shown.";
            return;
        }
        if (!llm.available()) {
            e.aiNote = "The AI engine is switched on but no model answers. The built-in explanation is shown.";
            return;
        }
        String system = "You explain failed API tests to a non-programmer in 3 short sentences: what happened, the likely cause, what to do. Use only the facts given. Do not invent endpoints, fields or tools.";
        String user = "Test: " + r.testCaseId + " " + (r.title == null ? "" : r.title) + "\nCall: " + r.method + " " + r.url + "\nExpected HTTP: " + r.expectedStatus + "  Actual: " + r.httpStatus
                + "\nFinding: " + (r.remark == null ? "" : r.remark) + "\nResponse (redacted): " + trim(Redactor.mask(r.responseEvidence == null ? "" : r.responseEvidence), 900)
                + "\nBuilt-in analysis: " + e.headline + ". " + e.cause;
        String out = llm.chat(system, user, llm.settings() == null ? 180 : llm.settings().timeoutSeconds);
        if (out == null) {
            e.aiNote = "The model did not answer in time. The built-in explanation is shown.";
            return;
        }
        e.aiText = out;
        e.source = "built-in + AI (" + llm.model() + ")";
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
