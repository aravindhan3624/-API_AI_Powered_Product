package com.api.automation.report;

/**
 * Turns a technical failure into something a reader understands:
 *   category  - short label to filter on (ENDPOINT NOT DEPLOYED, DB ERROR, ...)
 *   reason    - plain-English explanation
 *   action    - what is needed and from whom
 */
public final class FailureClassifier {

    public static final class Verdict {
        public final String category;
        public final String reason;
        public final String action;

        Verdict(String category, String reason, String action) {
            this.category = category;
            this.reason = reason;
            this.action = action;
        }
    }

    private FailureClassifier() {}

    /**
     * @param outcome     PASS / FAIL / SKIP
     * @param httpStatus  0 when no call was made
     * @param message     server message or framework reason
     */
    public static Verdict classify(String outcome, int httpStatus, String message) {
        String m = message == null ? "" : message.trim();
        String lm = m.toLowerCase();
        String server = serverMessage(m);

        if ("PASS".equals(outcome)) return new Verdict("", "", "");

        if ("SKIP".equals(outcome)) {
            if (lm.contains("otp not entered") || lm.contains("otp")) {
                if (lm.contains("otpsessionref")) {
                    return new Verdict("NOT NEEDED (no OTP)", "Login did not ask for an OTP (2-factor is off), so the OTP step is not required.",
                            "None - expected when 2-factor login is disabled.");
                }
                return new Verdict("OTP NOT ENTERED", "The OTP was not entered (skipped or timed out).", "Tester: enter the OTP in the popup when it appears.");
            }
            if (lm.contains("negative check is not meaningful")) {
                return new Verdict("NOT NEEDED", "The positive test of this API did not pass, so its negative checks were not run "
                        + "(a rejection would prove nothing).", "Fix the positive test first - the negatives then run automatically.");
            }
            if (lm.contains("sessiontakeover")) {
                return new Verdict("NOT NEEDED", "No other session was active, so the 'take over session' login was not required.", "None - expected.");
            }
            if (lm.contains("its source step failed")) {
                return new Verdict("BLOCKED BY FAILED STEP",
                        "Not executable: the API that supplies this value failed, so there is nothing to send. " + m,
                        "Fix the source API first (see the TC in the reason) - this test then runs automatically.");
            }
            if (lm.contains("not available") || lm.contains("earlier step") || lm.startsWith("needs '")) {
                return new Verdict("NO DATA FROM PREVIOUS STEP",
                        "Skipped because the earlier list returned no record today. " + m,
                        "Provide a record in that queue (or a customer id to use) - see the 'Needs' value.");
            }
            return new Verdict("SKIPPED", m, "Check the reason.");
        }

        // ---- FAIL ----
        if (httpStatus == 0 || lm.contains("exception") || lm.contains("connect") || lm.contains("timed out")) {
            return new Verdict("CONNECTIVITY / FRAMEWORK", "The call could not be completed: " + m,
                    "Check VPN / network / server availability; if it persists send the log to QA automation.");
        }
        if (lm.contains("for invalid input") && httpStatus >= 500) {
            return new Verdict("SERVER ERROR ON INVALID INPUT",
                    "Invalid input crashed the server (HTTP " + httpStatus + ") instead of returning a validation message.",
                    "Developer: validate this input and return a 4xx error with a clear message.");
        }
        if (lm.contains("internal error details leaked")) {
            return new Verdict("INTERNAL ERROR LEAKED",
                    "The response exposes internal details (exception / SQL text) for invalid input: " + m,
                    "Developer: return a generic validation message, never internal errors.");
        }
        if (lm.contains("invalid input was accepted")) {
            return new Verdict("NEGATIVE CASE ACCEPTED", "Invalid input was accepted by the API - it should have been rejected.", "Developer: add validation.");
        }
        if (httpStatus == 404 && (server.equalsIgnoreCase("Not Found") || server.isEmpty())) {
            return new Verdict("ENDPOINT NOT DEPLOYED",
                    "The server has no API at this path (404 Not Found). The web app's own screen calls the same path with the same request, so that screen fails in the app too - the endpoint is missing on this environment.",
                    "Backend / DevOps: deploy the API or confirm the correct path. If the screen is not used, mark the row Run = N.");
        }
        if (httpStatus == 404) {
            return new Verdict("DATA NOT FOUND", "The API works but the requested record does not exist: " + server,
                    "QA / Tester: give a valid id / code that exists in this environment.");
        }
        if (httpStatus == 401 || httpStatus == 422 && (lm.contains("token") || lm.contains("user not available"))) {
            return new Verdict("SESSION / LOGIN", "The request was not accepted as logged-in: " + server,
                    "Check that Login passed and that this API accepts the cookie/CSRF session; tell QA if Login passed.");
        }
        if (httpStatus == 403 && lm.contains("disabled")) {
            return new Verdict("FEATURE DISABLED", "This function is switched off in this environment: " + server,
                    "Admin: enable the function, or mark the row Run = N for this environment.");
        }
        if (httpStatus == 403) {
            return new Verdict("NO PERMISSION", "The test user's role is not allowed to use this API: " + server,
                    "Admin: grant the permission to the test user's role, or use a user that has it.");
        }
        if (lm.contains("sql") || lm.contains("ora-") || lm.contains("callablestatement")) {
            return new Verdict("DATABASE ERROR", "The backend failed in its database / stored procedure: " + server,
                    "Backend: fix the query / procedure (error text is in 'Server message').");
        }
        if (httpStatus == 409) {
            return new Verdict("RECORD IN WRONG STATE", "The record is not in the state this API expects: " + server,
                    "QA / Tester: pick a record that is currently at this stage (e.g. pending at checker).");
        }
        if (httpStatus >= 500) {
            return new Verdict("SERVER ERROR", "The server crashed while processing the request (HTTP " + httpStatus + ")"
                    + (server.isEmpty() ? "." : ": " + server), "Backend: check the server log for this request.");
        }
        if (httpStatus == 400 && lm.contains("not found")) {
            return new Verdict("DATA NOT FOUND", "The API works, but this customer / record does not have the requested data: " + server,
                    "QA: use a customer that has this data (e.g. an Aadhaar document for Aadhaar masking), or accept as a valid negative.");
        }
        if (httpStatus == 400 && (lm.contains("required") || lm.contains("missing") || lm.contains("must not be empty"))) {
            return new Verdict("INPUT MISSING", "A required input is missing or empty: " + server,
                    "QA: add the field / value to the test data (ask the developer for the field name if unclear).");
        }
        if (httpStatus == 400 && lm.contains("something went wrong")) {
            return new Verdict("SERVER REJECTED (GENERIC)", "The server returned a generic error without details: " + server,
                    "Backend: check the server log; QA: confirm the input record (e.g. customer has images) is valid.");
        }
        if (httpStatus == 400) {
            return new Verdict("BAD REQUEST", "The server did not accept the request data: " + server,
                    "QA: correct the test data; ask the developer for the expected input if unclear.");
        }
        if (lm.contains("expected status")) {
            return new Verdict("UNEXPECTED STATUS", m, "Check the response and the expected status in the test data.");
        }
        return new Verdict("RESPONSE CHECK FAILED", "The API answered, but the response did not match the expected result: " + m,
                "Compare Expected vs Actual; update the expected result or report a defect.");
    }

    /** "Expected status 200 but got 404 (Endpoint not found) - Not Found" -> "Not Found" */
    private static String serverMessage(String m) {
        int i = m.lastIndexOf(") - ");
        if (m.startsWith("Expected status") && i > 0) return m.substring(i + 4).trim();
        if (m.startsWith("Expected status")) return "";
        return m;
    }
}
