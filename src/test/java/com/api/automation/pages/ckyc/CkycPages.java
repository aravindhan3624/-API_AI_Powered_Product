package com.api.automation.pages.ckyc;

import java.util.List;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** CKYC 7.0 (iFlow CKYC) pages. Responses are already decrypted by the framework. */
public final class CkycPages {

    private CkycPages() {}

    public static List<ApiPage> all() {
        return List.of(new LoginPage(), new LogoutPage(), new DocumentImagePage());
    }

    /** /CKYC_IMAGE/... - a scanned document image (binary), not JSON. */
    public static class DocumentImagePage extends ApiPage {

        @Override public String name() { return "Document / Image File"; }

        @Override protected String[] endpointKeys() { return new String[]{"output"}; }

        @Override
        public boolean matches(String endpoint) {
            return endpoint != null && endpoint.contains("CKYC_IMAGE") || "${imgUrl}".equals(endpoint);
        }

        @Override public String expectedResult() { return "The document image is downloadable (HTTP 200, non-empty image)"; }

        @Override public PageResult validate(ApiResponse r) {
            boolean hasBody = r.body != null && !r.body.isBlank() && !"EMPTY".equals(r.body);
            return new Checks.Binary(r)
                    .that("Image content returned", "non-empty file", hasBody ? r.body : "empty", hasBody)
                    .result(hasBody ? "Downloaded " + r.body.replaceAll("^\\[binary file: |]$", "") : "No file returned", null);
        }
    }

    /**
     * /auth/login
     * success : {"statusCode":200,"message":"Login Successful","responseList":[{"userId":266,...,"csrfToken":"..."}]}
     * busy    : {"responseList":[{"sessionActive":1,"message":"You are already logged in on another session..."}]}
     * otp     : {"responseList":[{"statusCode":202,"otpSessionRef":"...","otpMaskedTarget":"..."}]}
     */
    public static class LoginPage extends ApiPage {

        @Override public String name() { return "Login"; }

        @Override protected String[] endpointKeys() { return new String[]{"auth/login"}; }

        @Override public String expectedResult() {
            return "User is signed in: statusCode 200 'Login Successful' with userId and a session (csrfToken / token), "
                    + "or 202 OTP challenge. 'Already logged in on another session' is not a successful login.";
        }

        @Override public PageResult validate(ApiResponse r) {
            String code = r.first("responseList[0].statusCode", "statusCode");
            String active = r.get("responseList[0].sessionActive");
            boolean otp = "202".equals(code);
            boolean session = r.first("responseList[0].csrfToken", "responseList[0].token", "responseList[0].jwtToken") != null;
            if ("1".equals(active)) {
                // Handled branch: the next step "Login (take over active session)" signs in with sessionId 1
                return new Checks(r)
                        .equalsAny("Login status", "responseList[0].statusCode", "200")
                        .that("Active session detected", "takeover step continues", "sessionActive = 1", true)
                        .result("Active session found for " + r.get("responseList[0].userName")
                                + " - taken over by the next step (Login - take over active session)", null);
            }
            return new Checks(r)
                    .equalsAny("Login status", "responseList[0].statusCode", "200", "202")
                    .that("Session created", "csrfToken / token (or OTP challenge)", session ? "present" : "missing", session || otp)
                    .result(otp ? "OTP required - sent to " + r.get("responseList[0].otpMaskedTarget")
                                    : r.get("responseList[0].message") + " | user " + r.get("responseList[0].userName")
                                    + " | userId " + r.get("responseList[0].userId") + " | role " + r.get("responseList[0].role"),
                            r.first("responseList[0].message", "message"));
        }
    }

    /** /auth/userLogOut - releases the session so the next run can log in without a takeover. */
    public static class LogoutPage extends ApiPage {

        @Override public String name() { return "Logout"; }

        @Override protected String[] endpointKeys() { return new String[]{"auth/userlogout"}; }

        @Override public String expectedResult() { return "Session is closed: statusCode 200"; }

        @Override public PageResult validate(ApiResponse r) {
            return new Checks(r)
                    .equalsAny("Logout status", "statusCode", "200")
                    .result("Logout: " + r.first("message", "responseList[0].message"), r.first("message"));
        }
    }
}
