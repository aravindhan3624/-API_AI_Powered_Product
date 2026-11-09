package com.api.automation.negative;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import com.api.automation.config.ConfigManager;

/**
 * Expected HTTP status + VAPT reference for every check, read from the project-independent rulebook
 * src/test/resources/rules/api-test-rules.properties (HTTP standard RFC 9110, OWASP API Security Top 10).
 *
 *   400 Bad Request        invalid request (type / missing / empty / too long / bad characters / date)
 *   401 Unauthorized       not logged in, wrong password
 *   403 Forbidden          logged in but not allowed (another user's data)
 *   404 Not Found          record does not exist
 *   405 Method Not Allowed wrong HTTP method
 *   413 Content Too Large  body too big
 *   422 Unprocessable      well-formed but breaks a business rule (accepted where 400 is expected)
 *
 * Any rule can be overridden for one bank in config.properties:  SPCBL.negative.expect.NO_SESSION=401,403
 */
public final class ExpectedStatus {

    public static final class Expect {
        public final int[] codes;
        public final String label;     // "401 Unauthorized"
        public final String why;       // what the API must recognise
        public final String owasp;     // VAPT reference

        Expect(int[] codes, String why, String owasp) {
            this.codes = codes;
            this.label = codes[0] + " " + name(codes[0]);
            this.why = why == null ? "" : why;
            this.owasp = owasp == null ? "" : owasp;
        }

        public boolean matches(int http) {
            return Arrays.stream(codes).anyMatch(c -> c == http);
        }

        /** "400 Bad Request (or 422)" */
        public String text() {
            if (codes.length == 1) return label;
            StringBuilder b = new StringBuilder(label).append(" (or ");
            for (int i = 1; i < codes.length; i++) b.append(i > 1 ? " / " : "").append(codes[i]);
            return b.append(")").toString();
        }
    }

    private static final Properties RULES = new Properties();

    static {
        try (InputStream in = ExpectedStatus.class.getResourceAsStream("/rules/api-test-rules.properties")) {
            if (in != null) RULES.load(in);
        } catch (Exception e) {
            System.out.println("⚠ API test rulebook not loaded: " + e.getMessage());
        }
    }

    private ExpectedStatus() {}

    /** Rule for a check (NO_SESSION, WRONG_TYPE, POSITIVE ...); null when the check has no fixed status. */
    public static Expect of(String check) {
        if (check == null || check.isEmpty()) return null;
        String codes = ConfigManager.get("negative.expect." + check, null);   // per-project override
        if (codes == null || codes.isBlank()) codes = RULES.getProperty("expect." + check);
        if (codes == null || codes.isBlank()) return null;
        try {
            int[] c = Arrays.stream(codes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).toArray();
            return c.length == 0 ? null : new Expect(c, RULES.getProperty("why." + check), RULES.getProperty("owasp." + check));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** VAPT reference for a finding (e.g. SERVER_ERROR, INTERNAL_LEAK) or a check. */
    public static String owasp(String key) {
        String v = RULES.getProperty("owasp." + key);
        return v == null || "-".equals(v) ? "" : v;
    }

    public static String name(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 402: return "Payment Required";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Content Too Large";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Content";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "";
        }
    }
}
