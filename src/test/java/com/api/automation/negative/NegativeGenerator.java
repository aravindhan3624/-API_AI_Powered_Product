package com.api.automation.negative;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;
import com.api.automation.payloads.ResolvedPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds negative tests from a POSITIVE test row. Each negative changes ONE thing of the
 * positive request (which uses the live customer / id), so it fails only because of that change.
 *
 *   Data:      not-existing record, wrong data type, missing field, empty value, invalid code,
 *              boundary (300 chars), SQL injection, special characters, script tags (XSS),
 *              unicode / emoji, only spaces
 *   Dates:     date of birth = today / future / 31 Feb / 1800 / wrong format;
 *              other dates: future, 31 Feb, wrong format, from-date after to-date
 *   Security:  no session, tampered token, wrong CSRF (state-changing APIs only), tampered encryption and
 *              plain (unencrypted) body (only when an encrypted body is sent), very large payload (1 MB, 10 MB),
 *              wrong HTTP method (every other method; PUT/PATCH/DELETE only on read-only APIs)
 *   The verdict of every negative is decided by {@link VaptRules}.
 *
 * Settings:  negative.enabled=true|false   negative.large.payload=true|false   negative.large.payload.mb=1,10
 *            negative.wrong.method=true|false   negative.other.user.single=false (userId swap in one login - not IDOR proof)
 */
public final class NegativeGenerator {

    private static final ObjectMapper M = new ObjectMapper();

    /** Fields that identify the record a request works on (first one found is mutated). */
    private static final List<String> ID_FIELDS = Arrays.asList(
            "cmId", "customerId", "custId", "customerIds", "customerNo", "downloadRefId", "ckycNo",
            "personId", "roleId", "reportId", "jobId", "groupId", "scenarioCode", "templateCode",
            "fiName", "category", "searchValue", "otpSessionRef", "documentId", "dcmId");

    /** Code / flag fields that only accept a fixed set of values. */
    private static final List<String> ENUM_FIELDS = Arrays.asList(
            "custType", "flag", "roleFlag", "recordFlag", "viewMode", "searchType", "applType",
            "constitutionType", "skipStatus", "type");

    /** Free-text fields preferred for the character checks (special / script / unicode / spaces). */
    private static final List<String> TEXT_FIELDS = Arrays.asList(
            "searchValue", "name", "customerName", "fullName", "remarks", "fiName", "category", "custId",
            "customerId", "customerRef", "pan", "mobile", "email", "fileName", "scenarioCode", "templateCode");

    private static final String SPECIAL = "!@#$%^&*()_+=[]{}|;:'\",.<>/?`~\\";
    private static final String XSS = "<script>alert('x')</script>";
    private static final String UNICODE = "தமிழ் 测试 😀";

    private static final Pattern DATE_FIELD = Pattern.compile("(?i).*(date|dob|birth|dt)$|(?i)(dob|dateOfBirth|birthDate)");
    private static final Pattern DOB_FIELD = Pattern.compile("(?i).*(dob|birth).*");

    private static final List<String> METHODS = Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE");
    /** Endpoints that change data: wrong-method checks use only GET / POST there. */
    private static final Pattern WRITE_API = Pattern.compile(
            "(?i)/[^/]*(save|update|delete|insert|add|create|approve|reject|submit|upload|push|confirm|assign|remove|reset|change|generate|send|archive)[^/]*$");

    private static final Pattern QUERY_VAR = Pattern.compile("([?&])(scenarioCode|templateCode|groupId|jobId|customerId|cmId)=([^&]*)");

    private NegativeGenerator() {}

    public static boolean enabled() {
        return ConfigManager.getBool("negative.enabled", true);
    }

    /** Rows that get no negatives: helpers, public pages, session handling. */
    public static boolean eligible(Map<String, String> row) {
        String name = row.getOrDefault("API_Name", "");
        String module = row.getOrDefault("Module", "");
        String ep = row.getOrDefault("Endpoint", "").toLowerCase();
        if (module.contains("Customer Discovery")) return false;
        if (name.matches("(?i).*(logout|take over|verify otp|resend otp).*")) return false;
        if (ep.contains("getloginpageconfig") || ep.contains("getssologinmeta") || ep.contains("initiatesso")) return false;
        if (ep.contains("ckyc_image")) return false;          // binary document download
        return true;
    }

    public static List<ResolvedPayload> generate(Map<String, String> row, ResolvedPayload positive) {
        List<ResolvedPayload> out = new ArrayList<>();
        String endpoint = row.getOrDefault("Endpoint", "");
        String method = row.getOrDefault("Method", "POST").trim().toUpperCase();
        String body = positive == null ? "" : positive.getBody();

        if (endpoint.toLowerCase().contains("/auth/login")) {
            return loginNegatives(body);
        }

        ObjectNode json = parseObject(body);
        // write APIs (save / update / delete ...) get only the SECURITY checks: invalid-data checks there would
        // create junk records, and sending the valid request again would create duplicates
        boolean writeApi = WRITE_API.matcher(endpoint.replaceAll("\\?.*", "")).find();
        int[] n = {0};
        java.util.function.Supplier<String> code = () -> String.format("%02d", ++n[0]);

        if (json != null && !writeApi) {
            String idField = firstPresent(json, ID_FIELDS);
            if (idField == null) idField = firstOther(json);
            JsonNode orig = idField == null ? null : json.get(idField);
            boolean numeric = orig != null && (orig.isNumber() || looksNumeric(orig.asText()) || idField.matches("(?i).*(cmId|Id)$") && !idField.matches("(?i)(customerId|custId|downloadRefId|scenarioCode|templateCode|searchValue)"));
            String textField = firstPresent(json, TEXT_FIELDS);
            if (textField == null) textField = idField;

            // long / odd values FIRST: when one of them reaches the database (DB error), the short invalid values
            // accepted afterwards on the same field are reported as "no input validation" (VaptRules)
            if (idField != null) {
                String big = (numeric ? "9" : "A").repeat(300);
                out.add(neg(code.get(), "BOUNDARY", "Boundary / length", idField + " = 300 characters",
                        "Rejected (too long) or no data returned, no server error",
                        with(json, idField, M.getNodeFactory().textNode(big))));
            }
            if (textField != null) {
                out.add(neg(code.get(), "SPECIAL_CHARS", "Special characters", textField + " = " + SPECIAL,
                        "Rejected (special characters not allowed) or no data; no server error",
                        with(json, textField, M.getNodeFactory().textNode(SPECIAL))));
                out.add(neg(code.get(), "XSS_SCRIPT", "Script / HTML tags", textField + " = " + XSS,
                        "Rejected, or not sent back unescaped (XSS)",
                        with(json, textField, M.getNodeFactory().textNode(XSS))));
                out.add(neg(code.get(), "UNICODE", "Unicode / emoji characters", textField + " = " + UNICODE,
                        "Rejected or no data; no server error",
                        with(json, textField, M.getNodeFactory().textNode(UNICODE))));
            }
            if (idField != null) {
                out.add(neg(code.get(), "INJECTION", "SQL injection characters", idField + " = \"' OR '1'='1\"",
                        "Rejected or no data; no SQL / internal error leaked; no extra rows (boolean / time probes)",
                        with(json, idField, M.getNodeFactory().textNode("' OR '1'='1"))));
            }

            if (idField != null) {
                out.add(neg(code.get(), "NOT_EXISTING", "Not-existing record",
                        idField + " = " + (numeric ? "999999999" : "\"ZZNOTEXIST999\""),
                        "Rejected as 'not found', or no data returned",
                        with(json, idField, orig != null && orig.isArray() ? arr("ZZNOTEXIST999") : M.getNodeFactory().textNode(numeric ? "999999999" : "ZZNOTEXIST999"))));
                out.add(neg(code.get(), "WRONG_TYPE", "Wrong data type",
                        idField + (numeric ? " = \"ABC\" (text instead of number)" : " = 12345 (number instead of text)"),
                        "Rejected with a validation message (4xx), no server error",
                        with(json, idField, numeric ? M.getNodeFactory().textNode("ABC") : M.getNodeFactory().numberNode(12345))));
                out.add(neg(code.get(), "MISSING_FIELD", "Missing mandatory field", idField + " removed",
                        "Rejected: '" + idField + "' is required", without(json, idField)));
                out.add(neg(code.get(), "EMPTY_VALUE", "Empty value", idField + " = \"\"",
                        "Rejected: '" + idField + "' must not be empty",
                        with(json, idField, orig != null && orig.isArray() ? M.createArrayNode() : M.getNodeFactory().textNode(""))));
            }
            String enumField = firstPresent(json, ENUM_FIELDS);
            if (enumField != null) {
                boolean num = json.get(enumField).isNumber() || looksNumeric(json.get(enumField).asText());
                out.add(neg(code.get(), "INVALID_CODE", "Invalid code / flag", enumField + " = " + (num ? "99" : "\"XX\""),
                        "Rejected: invalid " + enumField,
                        with(json, enumField, num ? M.getNodeFactory().numberNode(99) : M.getNodeFactory().textNode("XX"))));
            }
            if (textField != null) {
                out.add(neg(code.get(), "SPACES_ONLY", "Only spaces", textField + " = \"     \" (5 spaces)",
                        "Rejected: '" + textField + "' must not be blank (spaces are not a value)",
                        with(json, textField, M.getNodeFactory().textNode("     "))));
            }
            dateNegatives(json, out, code);
            if (ConfigManager.getBool("negative.large.payload", false)) {
                // negative.large.payload.mb=1,10 : one test per size; 10 MB must be refused with 413 / 400
                for (String mb : ConfigManager.get("negative.large.payload.mb", "1,10").split(",")) {
                    int size;
                    try { size = Integer.parseInt(mb.trim()); } catch (NumberFormatException e) { continue; }
                    if (size <= 0) continue;
                    out.add(neg(code.get(), "LARGE_PAYLOAD", "Very large payload (" + size + " MB)", "extra field with " + size + " MB of text",
                            "Rejected (413 / 4xx), no slowdown or crash",
                            with(json, "zzLargeField", M.getNodeFactory().textNode("X".repeat(size * 1024 * 1024)))));
                }
            }
        } else if (body != null && body.trim().startsWith("<")) {
            xmlNegatives(body.trim(), writeApi, out, code);
        } else if (endpoint.contains("?") && !writeApi) {
            // GET with query parameters (e.g. Email module)
            Matcher m = QUERY_VAR.matcher(endpoint);
            if (m.find()) {
                String p = m.group(2);
                out.add(neg(code.get(), "NOT_EXISTING", "Not-existing record", p + " = ZZNOTEXIST999",
                        "Rejected as 'not found', or no data returned", body)
                        .withEndpoint(replaceQuery(endpoint, m, "ZZNOTEXIST999")));
                out.add(neg(code.get(), "EMPTY_VALUE", "Empty value", p + " = (empty)",
                        "Rejected: '" + p + "' is required, or no data returned", body)
                        .withEndpoint(replaceQuery(endpoint, m, "")));
                out.add(neg(code.get(), "INJECTION", "Special / injection characters", p + " = ' OR '1'='1",
                        "Rejected or no data; no SQL / internal error leaked", body)
                        .withEndpoint(replaceQuery(endpoint, m, "%27%20OR%20%271%27%3D%271")));
            }
        }

        // ---- applies to every protected API ----
        out.add(neg(code.get(), "NO_SESSION", "No login / session", "cookies, CSRF and token removed",
                "Rejected: not signed in (401 / 403 / 422)", body).withMode(ResolvedPayload.Mode.NO_SESSION));

        // ---- modified credentials: the real token / CSRF value with a few characters changed ----
        String hdr = row.getOrDefault("Headers", "").toLowerCase();
        if (hdr.contains("authorization")) {
            out.add(neg(code.get(), "TAMPERED_TOKEN", "Tampered bearer token",
                    "Authorization: Bearer <real token with the last 6 characters changed> + session cookies altered",
                    "Rejected: token is invalid (401)", body).withMode(ResolvedPayload.Mode.TAMPER_TOKEN));
        }
        // CSRF tokens protect state-changing APIs only (OWASP CSRF cheat sheet); read APIs (also POST reads like /auth/me) are not CSRF-checked
        if (hdr.contains("x-csrf-token") && !method.matches("GET|HEAD|OPTIONS") && ApiKind.of(method, endpoint).writes()) {
            out.add(neg(code.get(), "TAMPERED_CSRF", "Wrong CSRF token", "X-CSRF-Token / X-XSRF-TOKEN value changed",
                    "Rejected: CSRF check failed (403)", body).withMode(ResolvedPayload.Mode.TAMPER_CSRF));
        }

        // userId swapped inside ONE (admin) login proves nothing about authorization; the two-user IDOR check replaces it.
        // negative.other.user.single=true switches the old check back on (never on system-wide settings).
        if (json != null && json.has("userId") && ConfigManager.getBool("negative.other.user.single", false)
                && !ApiKind.isSystemConfig(endpoint)) {
            out.add(neg(code.get(), "OTHER_USER", "Another user's id", "userId = \"1\" (not the logged-in user)",
                    "Rejected: user does not match the session", with(json, "userId", M.getNodeFactory().textNode("1"))));
        }
        // only requests that really carry an encrypted body (not GET / empty body)
        boolean encrypted = ConfigManager.getBool("crypto.envelope", false) && body != null && !body.isBlank()
                && !method.matches("GET|HEAD|OPTIONS") && !isSkipped(endpoint);
        if (encrypted) {
            out.add(neg(code.get(), "TAMPERED_ENCRYPTION", "Tampered encryption", "clientsecret header corrupted",
                    "Rejected: request cannot be decrypted (4xx)", body).withMode(ResolvedPayload.Mode.TAMPER_ENCRYPTION));
            out.add(neg(code.get(), "PLAIN_BODY", "Unencrypted body on an encrypted API", "same request sent as plain JSON (no envelope)",
                    "Rejected: the API must accept encrypted bodies only (4xx)", body).withMode(ResolvedPayload.Mode.PLAIN_BODY));
        }
        if (ConfigManager.getBool("negative.wrong.method", false)) {
            // every other method; PUT / PATCH / DELETE only on read-only APIs, so nothing can be changed by mistake
            for (String wrong : METHODS) {
                if (wrong.equals(method)) continue;
                if (writeApi && !("GET".equals(wrong) || "POST".equals(wrong))) continue;
                out.add(neg(code.get(), "WRONG_METHOD", "Wrong HTTP method", method + " -> " + wrong,
                        "Rejected: 405 Method Not Allowed (or 4xx)", body).withMethod(wrong));
            }
        }
        // last: the original valid request must still work - the bad inputs must not have changed session or data
        if (!writeApi) out.add(ResolvedPayload.negative(code.get(), "STATE_CHECK", "Valid request still works after the negatives",
                "original valid request sent again", "Same result as the positive test (session, permissions and data unchanged)",
                body).asVerifyPositive());
        return out;
    }

    /**
     * Date fields. Date of birth: today / future / impossible date / wrong format / too old must be rejected.
     * Other dates (report filters): future from-date, impossible date, wrong format, from-date after to-date.
     */
    private static void dateNegatives(ObjectNode json, List<ResolvedPayload> out, java.util.function.Supplier<String> code) {
        List<String> dates = new ArrayList<>();
        json.fieldNames().forEachRemaining(f -> {
            if (DATE_FIELD.matcher(f).matches() && json.get(f).isValueNode()) dates.add(f);
        });
        if (dates.isEmpty()) return;
        LocalDate today = LocalDate.now();
        String dob = dates.stream().filter(f -> DOB_FIELD.matcher(f).matches()).findFirst().orElse(null);
        if (dob != null) {
            String fmt = formatOf(json.get(dob).asText());
            out.add(neg(code.get(), "DOB_TODAY", "Date of birth = today", dob + " = " + fmt(today, fmt) + " (today)",
                    "Rejected: date of birth cannot be today", with(json, dob, txt(fmt(today, fmt)))));
            out.add(neg(code.get(), "DOB_FUTURE", "Date of birth in the future", dob + " = " + fmt(today.plusDays(1), fmt) + " (tomorrow)",
                    "Rejected: date of birth cannot be in the future", with(json, dob, txt(fmt(today.plusDays(1), fmt)))));
            out.add(neg(code.get(), "DOB_INVALID", "Impossible date of birth", dob + " = " + impossible(fmt) + " (31 February)",
                    "Rejected: not a valid calendar date", with(json, dob, txt(impossible(fmt)))));
            out.add(neg(code.get(), "DOB_TOO_OLD", "Date of birth too old", dob + " = " + fmt(LocalDate.of(1800, 1, 1), fmt),
                    "Rejected: unrealistic date of birth", with(json, dob, txt(fmt(LocalDate.of(1800, 1, 1), fmt)))));
            String other = otherFormat(fmt);
            out.add(neg(code.get(), "DATE_FORMAT", "Date of birth in wrong format", dob + " = " + fmt(LocalDate.of(1990, 5, 20), other) + " (" + other + ")",
                    "Rejected: date must be " + fmt, with(json, dob, txt(fmt(LocalDate.of(1990, 5, 20), other)))));
            return;
        }
        String from = dates.stream().filter(f -> f.toLowerCase().contains("from") || f.toLowerCase().contains("start")).findFirst().orElse(dates.get(0));
        String to = dates.stream().filter(f -> f.toLowerCase().contains("to") && !f.equals(from) || f.toLowerCase().contains("end")).findFirst().orElse(null);
        String fmt = formatOf(json.get(from).asText());
        String future = fmt(today.plusYears(1), fmt);
        out.add(neg(code.get(), "DATE_FUTURE", "Date in the future", from + " = " + future,
                "Rejected, or no data returned for a future date", with(json, from, txt(future))));
        out.add(neg(code.get(), "DATE_INVALID", "Impossible date", from + " = " + impossible(fmt) + " (31 February)",
                "Rejected: not a valid calendar date", with(json, from, txt(impossible(fmt)))));
        String other = otherFormat(fmt);
        out.add(neg(code.get(), "DATE_FORMAT", "Date in wrong format", from + " = " + fmt(today.minusDays(7), other) + " (" + other + ")",
                "Rejected: date must be " + fmt, with(json, from, txt(fmt(today.minusDays(7), other)))));
        if (to != null) {
            ObjectNode c = json.deepCopy();
            c.put(from, fmt(today, fmt));
            c.put(to, fmt(today.minusDays(30), fmt));
            out.add(neg(code.get(), "DATE_RANGE", "From-date after to-date",
                    from + " = " + fmt(today, fmt) + ", " + to + " = " + fmt(today.minusDays(30), fmt),
                    "Rejected: from-date must not be after to-date (or no data)", c.toString()));
        }
    }

    /** Date format of a value: a ${DATE:..:fmt} / ${NOW:fmt} template, or detected from the text. */
    private static String formatOf(String v) {
        Matcher t = Pattern.compile("\\$\\{(?:DATE:-?\\d+|NOW):([^}]+)}").matcher(v == null ? "" : v);
        if (t.find()) return t.group(1);
        if (v == null || v.isBlank()) return "dd/MM/yyyy";
        if (v.matches("\\d{4}-\\d{2}-\\d{2}.*")) return "yyyy-MM-dd";
        if (v.matches("\\d{2}-\\d{2}-\\d{4}.*")) return "dd-MM-yyyy";
        if (v.matches("\\d{8}")) return "ddMMyyyy";
        return "dd/MM/yyyy";
    }

    private static String otherFormat(String fmt) {
        return fmt.startsWith("yyyy") ? "dd/MM/yyyy" : "yyyy-MM-dd";
    }

    private static String fmt(LocalDate d, String fmt) {
        try {
            return d.format(DateTimeFormatter.ofPattern(fmt));
        } catch (Exception e) {
            return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }
    }

    /** 31 February in the given format (built as text - it is not a real date). */
    private static String impossible(String fmt) {
        return fmt.replace("yyyy", "2000").replace("MM", "02").replace("dd", "31");
    }

    private static JsonNode txt(String s) {
        return M.getNodeFactory().textNode(s);
    }

    /** Leaf element of an XML body: <cmId>3</cmId> (no child elements). */
    private static final Pattern XML_LEAF = Pattern.compile("<((?:[\\w.-]+:)?([\\w.-]+))(\\s[^>]*)?>([^<]*)</\\1>");

    /**
     * XML / SOAP bodies: the same field checks as JSON (on the first id-like element, else the first element),
     * plus two XML-only VAPT checks - malformed XML and XXE (external entity injection).
     */
    private static void xmlNegatives(String xml, boolean writeApi, List<ResolvedPayload> out, java.util.function.Supplier<String> code) {
        // XML-only security checks are safe on write APIs too: a correct API rejects the document before using it
        out.add(neg(code.get(), "MALFORMED_XML", "Malformed XML", "last closing tag removed",
                "Rejected: not well-formed XML (400)", xml.substring(0, xml.lastIndexOf("</") > 0 ? xml.lastIndexOf("</") : xml.length())));
        // pick the first id-like element, else the first element
        Matcher m = XML_LEAF.matcher(xml);
        int chosen = -1;
        while (m.find()) {
            if (chosen < 0) chosen = m.start();
            if (ID_FIELDS.contains(m.group(2))) { chosen = m.start(); break; }
        }
        if (chosen < 0) return;
        final Matcher f = XML_LEAF.matcher(xml);
        if (!f.find(chosen)) return;
        String field = f.group(2), value = f.group(4);
        java.util.function.Function<String, String> set = v ->
                xml.substring(0, f.start(4)) + v + xml.substring(f.end(4));

        // XXE: declare an external entity and use it in the chosen element
        String doc = "<!DOCTYPE autoqa [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>";
        String withEntity = set.apply("&xxe;");
        String xxe = withEntity.startsWith("<?xml") && withEntity.contains("?>")
                ? withEntity.substring(0, withEntity.indexOf("?>") + 2) + doc + withEntity.substring(withEntity.indexOf("?>") + 2)
                : doc + withEntity;
        out.add(neg(code.get(), "XXE", "XML external entity (XXE)", field + " = &xxe; (entity reading a server file)",
                "Rejected (400); the file content must never appear in the response", xxe));
        if (writeApi) return;
        boolean numeric = looksNumeric(value) || field.matches("(?i).*Id$");
        out.add(neg(code.get(), "NOT_EXISTING", "Not-existing record", field + " = " + (numeric ? "999999999" : "ZZNOTEXIST999"),
                "Rejected as 'not found', or no data returned", set.apply(numeric ? "999999999" : "ZZNOTEXIST999")));
        if (numeric) out.add(neg(code.get(), "WRONG_TYPE", "Wrong data type", field + " = ABC (text instead of number)",
                "Rejected with a validation message (4xx)", set.apply("ABC")));
        out.add(neg(code.get(), "MISSING_FIELD", "Missing mandatory field", "<" + field + "> removed",
                "Rejected: '" + field + "' is required", xml.substring(0, f.start()) + xml.substring(f.end())));
        out.add(neg(code.get(), "EMPTY_VALUE", "Empty value", field + " = (empty)", "Rejected: '" + field + "' must not be empty", set.apply("")));
        out.add(neg(code.get(), "SPACES_ONLY", "Only spaces", field + " = (5 spaces)", "Rejected: spaces are not a value", set.apply("     ")));
        out.add(neg(code.get(), "BOUNDARY", "Boundary / length", field + " = 300 characters", "Rejected (too long)", set.apply((numeric ? "9" : "A").repeat(300))));
        out.add(neg(code.get(), "INJECTION", "SQL injection characters", field + " = ' OR '1'='1",
                "Rejected or no data; no SQL / internal error leaked", set.apply("' OR '1'='1")));
        out.add(neg(code.get(), "XSS_SCRIPT", "Script / HTML tags", field + " = <script> (XML-escaped)",
                "Rejected, or not sent back unescaped", set.apply("&lt;script&gt;alert('x')&lt;/script&gt;")));
    }

    /** Login: ONE wrong-password attempt on the real user, then prove a valid login still works. */
    private static List<ResolvedPayload> loginNegatives(String body) {
        List<ResolvedPayload> out = new ArrayList<>();
        ObjectNode json = parseObject(body);
        if (json == null) return out;
        // The real test user is NEVER used for the wrong-password attempt (a few of them lock the account and stop the whole suite).
        // negative.login.real.user=true switches the old behaviour back on.
        boolean real = com.api.automation.config.ConfigManager.getBool("negative.login.real.user", false);
        ObjectNode wrong = json.deepCopy();
        wrong.put("password", "Wrong@Pass_" + System.currentTimeMillis() % 1000);
        String throwAway = com.api.automation.config.ConfigManager.get("negative.login.throwaway.user", "api.nouser.check");
        if (!real && wrong.has("userName")) wrong.put("userName", throwAway);
        out.add(neg("01", "WRONG_PASSWORD", real ? "Wrong password (real user, one attempt)" : "Wrong credentials (throw-away user - the real account is not touched)",
                real ? "password = wrong value" : "userName = " + throwAway + ", password = wrong value",
                "Rejected: invalid username or password", wrong.toString()));
        ObjectNode ok = json.deepCopy();
        ok.put("sessionId", 1);   // take over the session the positive login created
        out.add(ResolvedPayload.negative("02", "VERIFY_LOGIN", "Valid login still works after the failed attempt",
                        "correct password again (sessionId 1)", "Login Successful - account is not locked", ok.toString())
                .asVerifyPositive());
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    private static ResolvedPayload neg(String code, String cat, String title, String change, String expected, String body) {
        return ResolvedPayload.negative(code, cat, title, change, expected, body);
    }

    private static ObjectNode parseObject(String body) {
        try {
            if (body == null || body.isBlank()) return null;
            JsonNode n = M.readTree(body);
            return n.isObject() ? (ObjectNode) n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstPresent(ObjectNode json, List<String> names) {
        for (String f : names) if (json.has(f)) return f;
        return null;
    }

    private static String firstOther(ObjectNode json) {
        Iterator<String> it = json.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!f.equalsIgnoreCase("userId") && !f.equalsIgnoreCase("requestID") && !ENUM_FIELDS.contains(f)
                    && !DATE_FIELD.matcher(f).matches()) return f;
        }
        return null;
    }

    private static boolean looksNumeric(String s) {
        return s != null && s.matches("-?\\d+");
    }

    private static ArrayNode arr(String v) {
        return M.createArrayNode().add(v);
    }

    private static String with(ObjectNode json, String field, JsonNode value) {
        ObjectNode c = json.deepCopy();
        c.set(field, value);
        return c.toString();
    }

    private static String without(ObjectNode json, String field) {
        ObjectNode c = json.deepCopy();
        c.remove(field);
        return c.toString();
    }

    private static String replaceQuery(String endpoint, Matcher m, String value) {
        return endpoint.substring(0, m.start()) + m.group(1) + m.group(2) + "=" + value + endpoint.substring(m.end());
    }

    private static boolean isSkipped(String endpoint) {
        for (String p : ConfigManager.get("crypto.skip.paths", "").split(",")) {
            if (!p.isBlank() && endpoint.contains(p.trim())) return true;
        }
        return false;
    }
}
