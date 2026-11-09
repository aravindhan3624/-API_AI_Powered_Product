package com.api.automation.utils;

import java.util.regex.Pattern;

import io.restassured.path.json.JsonPath;

/**
 * Generic business validation, used when a project has no page class for an API
 * (see com.api.automation.pages).
 */
public class ResponseAnalyzer {

    private static final Pattern FAILURE_WORDS = Pattern.compile(
            "\\b(fail(ed|ure)?|error|invalid|not found|no http resource|exceed(ed)?|denied|"
                    + "mismatch|not matching|unauthori[sz]ed|expired|incorrect|wrong|rejected|already)\\b",
            Pattern.CASE_INSENSITIVE);

    // "valid" only as a whole word, so "invalid" is NOT treated as success
    private static final Pattern SUCCESS_WORDS = Pattern.compile(
            "\\b(success(ful(ly)?)?|verified|matched|valid|sent)\\b",
            Pattern.CASE_INSENSITIVE);

    public static ValidationResult analyze(String responseBody) {

        String cleaned = ResponseHandler.normalize(responseBody);
        String type = ResponseHandler.detectType(cleaned);

        switch (type) {

            case "EMPTY":
                return ValidationResult.fail("Empty response from API");

            case "HTML":
                return ValidationResult.fail("HTML response received (likely error page)");

            case "XML":
            case "TEXT":
                return byKeywords(cleaned, type + " response");

            case "JSON":
                JsonPath json = ResponseHandler.jsonOrNull(cleaned);
                if (json == null) {
                    return ValidationResult.fail("Invalid JSON response");
                }

                // 🌐 CKYC style: { "statusCode": 200, "message": "...", "responseList": [ {...} ] }
                String sc = str(json, "statusCode");
                if (sc == null) sc = str(json, "responseList[0].statusCode");
                if (sc == null) sc = str(json, "responseList[0].STATUSCODE");
                if (sc != null && sc.matches("\\d+")) {
                    int code = Integer.parseInt(sc);
                    if (code == 200 || code == 201 || code == 0) return ValidationResult.pass();
                    String msg = str(json, "message");
                    if (msg == null) msg = str(json, "responseList[0].message");
                    return ValidationResult.fail(orDefault(msg, "Business failure (statusCode " + sc + ")"));
                }

                // 🌐 RAJKOT style: { "status": "SUCCESS" } / { "status": "1" }
                ValidationResult r = statusField(json, "status", "message", "msg");
                if (r != null) return r;

                // 🌐 RAJKOT style messageCode
                String code = str(json, "messageCode");
                if (code != null) {
                    return ("0".equals(code) || "200".equals(code))
                            ? ValidationResult.pass()
                            : ValidationResult.fail(orDefault(str(json, "messageDesc"), "Business failure (messageCode " + code + ")"));
                }

                // 🌐 SURAT: response.status
                r = statusField(json, "response.status", "response.details", null);
                if (r != null) return r;

                // 🌐 SURAT: Statuscode
                code = str(json, "Statuscode");
                if (code != null) {
                    return ("200".equals(code) || "1".equals(code))
                            ? ValidationResult.pass()
                            : ValidationResult.fail(orDefault(str(json, "Errormsg"), "Business failure (Statuscode " + code + ")"));
                }

                // 🌐 SURAT: Status
                code = str(json, "Status");
                if (code != null) {
                    return ("1".equals(code) || "200".equals(code))
                            ? ValidationResult.pass()
                            : ValidationResult.fail(orDefault(str(json, "msg"), "Business failure (Status " + code + ")"));
                }

                // ASP.NET error body: { "Message": "No HTTP resource was found ..." }
                String aspMessage = str(json, "Message");
                if (aspMessage != null && FAILURE_WORDS.matcher(aspMessage).find()) {
                    return ValidationResult.fail(aspMessage);
                }

                // 🌐 FINAL fallback — no known status field; don't fail for no reason
                return ValidationResult.pass();

            default:
                return ValidationResult.fail("Unknown response type: " + type);
        }
    }

    private static ValidationResult statusField(JsonPath json, String path, String msgPath, String altMsgPath) {
        String status = str(json, path);
        if (status == null) return null;

        String s = status.trim().toLowerCase();
        if (s.equals("success") || s.equals("ok") || s.equals("1") || s.equals("200") || s.equals("true")) {
            return ValidationResult.pass();
        }
        if (s.equals("fail") || s.equals("failed") || s.equals("failure") || s.equals("error")
                || s.equals("0") || s.equals("false")) {
            String msg = msgPath == null ? null : str(json, msgPath);
            if (msg == null && altMsgPath != null) msg = str(json, altMsgPath);
            return ValidationResult.fail(orDefault(msg, "Business failure (" + path + " = " + status + ")"));
        }
        return null; // unknown value → let other patterns decide
    }

    private static ValidationResult byKeywords(String text, String label) {
        if (FAILURE_WORDS.matcher(text).find()) {
            return ValidationResult.fail(label + " indicates failure: " + abbreviate(text));
        }
        if (SUCCESS_WORDS.matcher(text).find()) {
            return ValidationResult.pass();
        }
        return ValidationResult.fail(label + " without success indicator: " + abbreviate(text));
    }

    /** Short human message from a response (used for Actual Result / OTP popup). */
    public static String businessMessage(String normalized) {
        JsonPath json = ResponseHandler.jsonOrNull(normalized);
        if (json == null) return abbreviate(normalized);
        String[] paths = {"response.details", "result.message", "result[0].MESSAGE_DESC", "Errormsg",
                "messageDesc", "message", "Message", "msg", "details", "result.status"};
        for (String p : paths) {
            String v = str(json, p);
            if (v != null && !v.startsWith("{") && !v.startsWith("[")) return v;
        }
        return null;
    }

    public static String str(JsonPath json, String path) {
        try {
            Object v = json.get(path);
            if (v == null) return null;
            if (v instanceof java.util.List && ((java.util.List<?>) v).isEmpty()) return null;
            String s = String.valueOf(v).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
