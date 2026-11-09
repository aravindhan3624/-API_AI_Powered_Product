package com.api.automation.negative;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;
import com.api.automation.utils.PiiMasker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * VAPT verdict of one negative test. A test is a VAPT FAIL only when the response PROVES security impact:
 *   data exposure, unauthorised action, auth bypass, injection proof (DB error / boolean differential / time delay),
 *   XSS proof (payload unencoded in text/html), internal detail leak, encryption / integrity not enforced,
 *   missing resource limits.
 * Safe handling (rejection, empty list on a search, parameter ignored) is PASS. Business-rule gaps with no
 * security impact (dates, flags on report filters, server errors without a leak) are FUNCTIONAL - QA, not VAPT.
 * Checks that do not apply to the API (CSRF on read-only APIs, tampered encryption without an encrypted body,
 * userId on system-wide settings) are NOT APPLICABLE.
 *
 * Used live by ExecutionEngine and offline by tools.VaptRescore (re-scores a saved run), so both give the same verdict.
 */
public final class VaptRules {

    public enum Outcome { PASS, FAIL, NA, INCONCLUSIVE }

    /** VAPT = security finding, FUNCTIONAL = QA defect (not VAPT), NA = check not applicable, INCONCLUSIVE = cannot be judged. */
    public enum Cls { VAPT, FUNCTIONAL, NA, INCONCLUSIVE }

    public static final class Verdict {
        public final Outcome outcome;
        public final Cls cls;
        public final String severity;   // Critical / High / Medium / Low for VAPT FAIL, Info for functional, "" otherwise
        public final String reason;
        /** VAPT point the failure belongs to (e.g. INTERNAL_LEAK for a leak found by a BOUNDARY test); null = the test's own check */
        public final String point;

        public Verdict(Outcome o, Cls c, String sev, String reason, String point) {
            this.outcome = o;
            this.cls = c;
            this.severity = sev;
            this.reason = reason;
            this.point = point;
        }

        static Verdict pass(String why) { return new Verdict(Outcome.PASS, Cls.VAPT, "", why, null); }
        static Verdict fail(String sev, String why) { return new Verdict(Outcome.FAIL, Cls.VAPT, sev, why, null); }
        static Verdict fail(String sev, String why, String point) { return new Verdict(Outcome.FAIL, Cls.VAPT, sev, why, point); }
        static Verdict functional(String why) { return new Verdict(Outcome.FAIL, Cls.FUNCTIONAL, "Info", "Functional (QA, not VAPT): " + why, null); }
        static Verdict na(String why) { return new Verdict(Outcome.NA, Cls.NA, "", "NOT APPLICABLE: " + why, null); }
        static Verdict inconclusive(String why) { return new Verdict(Outcome.INCONCLUSIVE, Cls.INCONCLUSIVE, "", "INCONCLUSIVE - " + why, null); }
    }

    /** Everything the verdict needs; filled by the engine (live) or the re-score tool (saved run). */
    public static final class Input {
        public String category = "", method = "", endpoint = "", field, tc;
        public int http;
        public String contentType = "";
        public String response = "";
        public boolean pageRejected;        // business error inside an HTTP 200 (statusCode 400, status FAILURE ...)
        public boolean encryptedBody;       // an encrypted body was really sent (POST/PUT with a body)
        public String positiveResponse;     // response of the valid request (or of user A for the two-user IDOR check)
        public boolean twoUser;             // the two-user IDOR check (user B replays user A's request)
        public String proof;                // SQL injection proof found by the extra probes (boolean / time based)
    }

    public static final String XSS_PAYLOAD = "<script>alert('x')</script>";
    /** start of the reason of a "no input validation on a field that reaches the database" finding (grouped as one finding) */
    public static final String NO_VALIDATION = "No input validation on '";

    // ── run state (cleared at the start of every run) ───────────────────
    /** "path|field" pairs where an invalid value reached the database (DB error text in the response). */
    private static final Set<String> DB_ERROR_FIELDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<String, String> DB_ERROR_TEST = new java.util.concurrent.ConcurrentHashMap<>();
    /** decrypted response of each positive test, by TC_ID */
    public static final Map<String, String> POSITIVE_BODY = new java.util.concurrent.ConcurrentHashMap<>();
    /** {method, endpoint template, body template, headers template} of each positive test, by TC_ID */
    public static final Map<String, String[]> POSITIVE_REQUEST = new java.util.concurrent.ConcurrentHashMap<>();
    /** response of the valid request sent right before the negatives of an API (state-check snapshot), by TC_ID */
    public static final Map<String, String> BASELINE = new java.util.concurrent.ConcurrentHashMap<>();
    /** negatives of an API the server ACCEPTED (HTTP < 400), by TC_ID - named when the state check finds a change */
    public static final Map<String, List<String>> ACCEPTED = new java.util.concurrent.ConcurrentHashMap<>();
    /** two-user IDOR: what user A received for each API, by TC_ID */
    public static final Map<String, String> IDOR_A = new java.util.concurrent.ConcurrentHashMap<>();

    public static void reset() {
        DB_ERROR_FIELDS.clear();
        DB_ERROR_TEST.clear();
        POSITIVE_BODY.clear();
        POSITIVE_REQUEST.clear();
        BASELINE.clear();
        ACCEPTED.clear();
        IDOR_A.clear();
    }

    public static void noteDbError(String endpoint, String field, String tc) {
        if (field == null || field.isBlank()) return;
        String k = ApiKind.path(endpoint) + "|" + field;
        DB_ERROR_FIELDS.add(k);
        DB_ERROR_TEST.putIfAbsent(k, tc == null ? "" : tc);
    }

    private VaptRules() {}

    // ════════════════════════════════════════════════════════════════════
    public static Verdict evaluate(Input in) {
        String cat = in.category == null ? "" : in.category;
        String resp = in.response == null ? "" : in.response;
        ApiKind kind = ApiKind.of(in.method, in.endpoint);

        // 1) proofs that count whatever the HTTP code is
        Leak leak = findLeak(resp);
        if (leak != null) {
            if (leak.sql) noteDbError(in.endpoint, in.field, in.tc);
            if ("INJECTION".equals(cat) && leak.sql) {
                return Verdict.fail("High", "SQL injection: the injection probe produced a database error, so the value reaches the SQL statement. "
                        + leak.describe() + " [CWE-89]");
            }
            return Verdict.fail("Medium", "Internal error details leaked in the response: " + leak.describe()
                    + ". Return a generic message; log the details on the server only [CWE-209]", "INTERNAL_LEAK");
        }
        if (in.proof != null && !in.proof.isBlank()) {
            return Verdict.fail("High", "SQL injection proved: " + in.proof + " [CWE-89]");
        }
        if (in.http >= 500) {
            return ConfigManager.getBool("vapt.server.error.as.vapt", false)
                    ? Verdict.fail("Low", "Server error HTTP " + in.http + " for invalid input (no internal detail leaked) [CWE-755]", "SERVER_ERROR")
                    : Verdict.functional("server error HTTP " + in.http + " for invalid input; no internal detail leaked, so no security impact. "
                    + "The API should return a 400 validation message");
        }
        if ("XSS_SCRIPT".equals(cat) && isHtml(in.contentType) && resp.contains(XSS_PAYLOAD)) {
            return Verdict.fail("High", "Reflected XSS: the script payload comes back unencoded in a text/html response [CWE-79]");
        }

        // 2) the two-user IDOR check
        if (in.twoUser) return twoUser(in);

        boolean rejected = in.http >= 400 || in.pageRejected;
        if ("STATE_CHECK".equals(cat)) return Verdict.pass("state check is judged against the snapshot");   // decided by the engine
        if (rejected) {
            if ("TAMPERED_CSRF".equals(cat) && !kind.writes()) return Verdict.na("CSRF protects state-changing APIs only; this API only reads");
            if ("TAMPERED_ENCRYPTION".equals(cat) && !in.encryptedBody) return Verdict.na("the request has no encrypted body (GET / empty body)");
            return Verdict.pass("Rejected as expected (HTTP " + in.http + ")");
        }

        // 3) the input was ACCEPTED - is that a security issue?
        boolean data = hasData(resp);
        switch (cat) {
            case "NO_SESSION":
            case "TAMPERED_TOKEN":
                if (data) return Verdict.fail("Critical", "Authentication bypass: the API returned data with "
                        + ("NO_SESSION".equals(cat) ? "no session / token" : "a tampered token") + " [CWE-287]");
                if (kind.writes()) return Verdict.fail("High", "Authentication bypass: a state-changing API accepted the request with "
                        + ("NO_SESSION".equals(cat) ? "no session / token" : "a tampered token") + " [CWE-287]");
                return Verdict.pass("Accepted, but no data was returned and the API changes nothing");
            case "WRONG_PASSWORD":
                return Verdict.fail("Critical", "Login succeeded with wrong credentials [CWE-287]");
            case "TAMPERED_CSRF":
                if (!kind.writes()) return Verdict.na("CSRF protects state-changing APIs only; this API only reads");
                return Verdict.fail("High", "CSRF not enforced: a state-changing API accepted a wrong CSRF token [CWE-352]");
            case "TAMPERED_ENCRYPTION":
                if (!in.encryptedBody) return Verdict.na("the request has no encrypted body (GET / empty body)");
                return Verdict.fail("Low", "Encrypted request integrity not enforced: a corrupted clientsecret / payload was still processed [CWE-345]");
            case "PLAIN_BODY":
                return Verdict.fail("Low", "Encryption not enforced: a plain (unencrypted) JSON body was accepted on an encrypted endpoint [CWE-319]");
            case "LARGE_PAYLOAD":
                return Verdict.fail("Low", "No request-body size limit: an oversized body was accepted (expected 413 / 400) [CWE-770]");
            case "WRONG_METHOD":
                return Verdict.fail("Low", "The API accepts an HTTP method it does not need [CWE-650]");
            case "XXE":
                return Verdict.fail("Medium", "The XML parser accepted a document with an external entity (DTD not disabled) [CWE-611]");
            case "MALFORMED_XML":
                return Verdict.fail("Low", "Malformed XML was accepted [CWE-20]");
            case "OTHER_USER":
                if (ApiKind.isSystemConfig(in.endpoint)) return Verdict.na("system-wide configuration API - changing userId is not IDOR (no object belongs to another user)");
                return Verdict.na("userId swapped within one (admin) login is not an authorization test; the two-user IDOR check (idor.enabled) decides VAPT-005");
            case "DATE_INVALID": case "DATE_FORMAT": case "DATE_RANGE": case "DATE_FUTURE":
            case "DOB_TODAY": case "DOB_FUTURE": case "DOB_INVALID": case "DOB_TOO_OLD":
                return Verdict.functional("invalid / out-of-range date accepted (" + cat + ") - business validation, no security impact");
            default:
                return validation(in, cat, kind, data, resp);
        }
    }

    /** NOT_EXISTING, WRONG_TYPE, MISSING_FIELD, EMPTY_VALUE, SPACES_ONLY, SPECIAL_CHARS, UNICODE, BOUNDARY, INVALID_CODE, INJECTION, XSS_SCRIPT */
    private static Verdict validation(Input in, String cat, ApiKind kind, boolean data, String resp) {
        String k = ApiKind.path(in.endpoint) + "|" + in.field;
        if (in.field != null && DB_ERROR_FIELDS.contains(k)) {
            String tc = DB_ERROR_TEST.getOrDefault(k, "");
            return Verdict.fail("Low", NO_VALIDATION + in.field + "': this value is accepted (HTTP " + in.http
                    + ") and other invalid values reach the database and fail there" + (tc.isEmpty() ? "" : " (" + tc + ")") + " [CWE-20]");
        }
        if (kind == ApiKind.GET_BY_ID && in.positiveResponse != null && !hasData(in.positiveResponse)) {
            return Verdict.inconclusive("the positive test returned no data, so this negative proves nothing. Fix the positive test data first");
        }
        boolean same = data && in.positiveResponse != null && sameData(resp, in.positiveResponse);
        if (kind == ApiKind.SEARCH_LIST) {
            if ("INVALID_CODE".equals(cat)) {
                return Verdict.functional("out-of-range code / flag / viewMode accepted by a report or list filter (HTTP " + in.http
                        + ") - business validation, no security impact");
            }
            return Verdict.pass(!data ? "Search / list API: no data for the invalid value (safe handling)"
                    : same ? "Search / list API: same data as the valid request (the parameter is ignored, not injected)"
                    : "Search / list API: optional filter - the list is returned without the filter (no security impact)");
        }
        if (kind == ApiKind.GET_BY_ID) {
            // mandatory-field rules belong to create / update APIs; a read that returns nothing for a bad id is safe
            if (!data) return Verdict.pass("No data for the invalid value (safe handling, nothing exposed)");
            if (same) return Verdict.pass("Same data as the valid request (the parameter is ignored)");
            return Verdict.fail("Low", "Invalid '" + in.field + "' returned different data than the valid request - re-check whether other records are exposed [CWE-20]");
        }
        // state-changing APIs (normally not sent invalid data, see NegativeGenerator)
        return Verdict.fail("Low", "Invalid input accepted by a state-changing API [CWE-20]");
    }

    private static Verdict twoUser(Input in) {
        Set<String> a = records(in.positiveResponse);
        if (a.isEmpty()) return Verdict.inconclusive("user A received no records for this API, so there is nothing to protect");
        if (in.http >= 400 || in.pageRejected) return Verdict.pass("User B was refused (HTTP " + in.http + ")");
        Set<String> b = records(in.response);
        b.retainAll(a);
        if (b.isEmpty()) return Verdict.pass("User B received none of user A's records");
        return Verdict.fail("High", "Broken object level authorization: user B received " + b.size() + " of user A's " + a.size()
                + " records with user A's request [API1:2023 BOLA, CWE-639]");
    }

    // ════════════════════════════════════════════════════════════════════
    //  Internal detail leak
    // ════════════════════════════════════════════════════════════════════
    private static final Pattern SQL_ERR = Pattern.compile(
            "(?i)(SQLException|SQLSyntaxErrorException|CallableStatementCallback|PreparedStatementCallback|StatementCallback|bad SQL grammar"
                    + "|ORA-\\d{4,5}|\\bJDBC\\b|SQLSTATE|syntax error at or near|You have an error in your SQL syntax|Incorrect syntax near"
                    + "|unterminated quoted string|quoted string not properly terminated)");
    private static final Pattern JAVA_LEAK = Pattern.compile(
            "(\\b(?:java|javax|jakarta|org|com|net|io)\\.(?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*"   // fully qualified class name
                    + "|\\b[A-Z][A-Za-z0-9]*Exception\\b"                                          // exception class name
                    + "|\\bat\\s+[\\w.$]+\\([\\w$]+\\.java:\\d+\\))");                             // stack frame
    private static final Pattern SQL_TEXT = Pattern.compile(
            "(?i)\\bselect\\s+(?:\\*|[\\w.]+(?:\\s*,\\s*[\\w.]+)*)\\s+from\\s+[\\w.]+\\s+(?:where|join|order|group)\\b"
                    + "|\\binsert\\s+into\\s+[\\w.]+\\s*\\(|\\bdelete\\s+from\\s+[\\w.]+\\s+where\\b");

    public static final class Leak {
        public final String field, text;
        public final boolean sql;

        Leak(String field, String text, boolean sql) {
            this.field = field;
            this.text = text;
            this.sql = sql;
        }

        public String describe() {
            return "field '" + field + "' contains \"" + text + "\"";
        }
    }

    /** First internal detail in the response (exception / SQL / class name / stack frame) with the JSON field it is in; null = none. */
    public static Leak findLeak(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            JsonNode n = M.readTree(response);
            if (n != null && (n.isObject() || n.isArray())) return leakIn(n, "");
        } catch (Exception ignored) {
            // not JSON: scan the text
        }
        return leakInText(response, "(body)");
    }

    private static Leak leakIn(JsonNode n, String path) {
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Leak l = leakIn(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey());
                if (l != null) return l;
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                Leak l = leakIn(n.get(i), path + "[" + i + "]");
                if (l != null) return l;
            }
        } else if (n.isTextual()) {
            return leakInText(n.asText(), path.isEmpty() ? "(body)" : path);
        }
        return null;
    }

    private static Leak leakInText(String s, String field) {
        Matcher m = SQL_ERR.matcher(s);
        boolean sql = m.find();
        if (!sql) {
            m = SQL_TEXT.matcher(s);
            sql = m.find();
        }
        if (!sql) {
            m = JAVA_LEAK.matcher(s);
            if (!m.find()) return null;
        }
        int from = Math.max(0, m.start() - 60), to = Math.min(s.length(), m.end() + 100);
        String text = (from > 0 ? "..." : "") + s.substring(from, to).replaceAll("\\s+", " ").trim() + (to < s.length() ? "..." : "");
        return new Leak(field, PiiMasker.mask(text), sql);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Response helpers
    // ════════════════════════════════════════════════════════════════════
    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> META_KEYS = Set.of(
            "statuscode", "message", "status", "requestid", "total", "totalcount", "count", "success", "errormessage",
            "pageno", "pagesize", "page", "size");
    /** values that change on every call and say nothing about the data */
    private static final Pattern VOLATILE = Pattern.compile("(?i).*(requestid|timestamp|time|token|session|nonce|servertime|generatedat)$");

    /** true when the response carries records: anything except status / message, nulls, or empty lists / objects. */
    public static boolean hasData(String response) {
        try {
            return hasData(M.readTree(response), "");
        } catch (Exception e) {
            return response != null && !response.isBlank() && !"EMPTY".equals(response);
        }
    }

    private static boolean hasData(JsonNode n, String key) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (META_KEYS.contains(key.toLowerCase())) return false;
        if (n.isArray()) {
            for (JsonNode x : n) if (hasData(x, "")) return true;
            return false;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (hasData(e.getValue(), e.getKey())) return true;
            }
            return false;
        }
        if (n.isNumber()) return n.asDouble() != 0;   // counters such as "pending": 0 are not records
        if (n.isBoolean()) return n.asBoolean();
        return !n.asText().isBlank();
    }

    /** Same data, ignoring values that change on every call (request id, time, token ...). */
    public static boolean sameData(String a, String b) {
        try {
            return strip(M.readTree(a)).equals(strip(M.readTree(b)));
        } catch (Exception e) {
            return a != null && a.equals(b);
        }
    }

    private static JsonNode strip(JsonNode n) {
        if (n == null) return null;
        if (n.isObject()) {
            ObjectNode c = M.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!VOLATILE.matcher(e.getKey()).matches()) c.set(e.getKey(), strip(e.getValue()));
            }
            return c;
        }
        if (n.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode c = M.createArrayNode();
            for (JsonNode x : n) c.add(strip(x));
            return c;
        }
        return n;
    }

    /** Number of records: the size of the largest list in the response (0 = none). */
    public static int rowCount(String response) {
        try {
            return rows(M.readTree(response));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int rows(JsonNode n) {
        if (n == null) return 0;
        int best = n.isArray() ? n.size() : 0;
        for (JsonNode x : n) best = Math.max(best, rows(x));
        return best;
    }

    /** Records (objects inside lists) as comparable text, volatile values removed. */
    public static Set<String> records(String response) {
        Set<String> out = new LinkedHashSet<>();
        try {
            collect(strip(M.readTree(response)), out);
        } catch (Exception ignored) {
            // not JSON: no records
        }
        return out;
    }

    private static void collect(JsonNode n, Set<String> out) {
        if (n == null) return;
        if (n.isArray()) {
            for (JsonNode x : n) {
                if (x.isObject() && hasData(x, "")) out.add(x.toString());
                collect(x, out);
            }
        } else if (n.isObject()) {
            for (JsonNode x : n) collect(x, out);
        }
    }

    /** HTTP 200 with a business error inside: {"statusCode":400 ...}, {"status":"FAILURE"} ... */
    public static boolean businessRejected(String response) {
        try {
            JsonNode n = M.readTree(response);
            if (n == null || !n.isObject()) return false;
            JsonNode sc = n.get("statusCode");
            if (sc != null && sc.asInt(200) >= 400) return true;
            for (String k : new String[]{"status", "result", "resultFlag"}) {
                JsonNode s = n.get(k);
                if (s != null && s.isTextual() && s.asText().matches("(?i)fail(ed|ure)?|error|invalid|rejected")) return true;
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return false;
    }

    public static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    /** "mcModule = \"AAA\"" / "<cmId> removed" / "p = (empty)" -> the field the negative changed; null for security checks. */
    public static String fieldOf(String change) {
        if (change == null) return null;
        Matcher m = Pattern.compile("^<?([A-Za-z_][\\w.]*)>?\\s*(=|removed)").matcher(change.trim());
        return m.find() ? m.group(1) : null;
    }

    /** SQL time-based probes (one per database type); override with vapt.sqli.time.probes=probe1||probe2 */
    public static List<String> timeProbes() {
        String cfg = ConfigManager.get("vapt.sqli.time.probes", "");
        List<String> out = new ArrayList<>();
        if (cfg != null && !cfg.isBlank()) {
            for (String p : cfg.split("\\|\\|")) if (!p.isBlank()) out.add(p.trim());
            return out;
        }
        int s = delaySeconds();
        out.add("' AND (SELECT 1 FROM (SELECT SLEEP(" + s + "))x)-- -");                      // MySQL / MariaDB
        out.add("'; WAITFOR DELAY '0:0:" + s + "'-- -");                                      // SQL Server
        out.add("'||(SELECT 1 FROM pg_sleep(" + s + "))||'");                                  // PostgreSQL
        out.add("'||DBMS_PIPE.RECEIVE_MESSAGE('a'," + s + ")||'");                             // Oracle
        return out;
    }

    public static int delaySeconds() {
        return Math.max(2, ConfigManager.getInt("vapt.sqli.time.seconds", 5));
    }
}
