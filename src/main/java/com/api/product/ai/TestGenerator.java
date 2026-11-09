package com.api.product.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Model-level negative / security test generator. It is a port of the legacy NegativeGenerator rules, so it works for every
 * project whatever its source (Excel, Postman, OpenAPI): every negative changes ONE thing of the positive request and is
 * derived from the data of the positive test that is already in the project.
 *
 * Rules kept from the legacy engine: one wrong-password attempt then a verify-login (never hammer a login);
 * write APIs (save, update, delete, add...) get only security checks unless the user opts in (an accepted bad request would
 * create junk records); no negatives for rows that are not run (Run=N) or helper endpoints (logout, OTP verify...);
 * a final STATE_CHECK proves the valid request still works. Categories use the rule-pack names, so expected results come
 * from the rule pack. Everything is DRAFT for review.
 */
public final class TestGenerator {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final List<String> ID_FIELDS = Arrays.asList("cmId", "customerId", "custId", "customerIds", "customerNo",
            "downloadRefId", "ckycNo", "personId", "roleId", "reportId", "jobId", "groupId", "scenarioCode", "templateCode",
            "fiName", "category", "searchValue", "otpSessionRef", "documentId", "dcmId", "id", "petId", "orderId", "accountId",
            "accountNo", "cif", "applicationId", "referenceId", "txnId", "transactionId");
    private static final List<String> ENUM_FIELDS = Arrays.asList("custType", "flag", "roleFlag", "recordFlag", "viewMode",
            "searchType", "applType", "constitutionType", "skipStatus", "type", "status", "gender", "currency");
    private static final List<String> TEXT_FIELDS = Arrays.asList("searchValue", "name", "customerName", "fullName", "remarks",
            "fiName", "category", "custId", "customerId", "customerRef", "pan", "mobile", "email", "fileName", "scenarioCode",
            "templateCode", "title", "description", "address", "username", "userName");
    private static final String SPECIAL = "!@#$%^&*()_+=[]{}|;:'\",.<>/?`~\\";
    private static final String XSS = "<script>alert('x')</script>";
    private static final String UNICODE = "தமிழ் 测试 😀";

    private static final Pattern DATE_FIELD = Pattern.compile("(?i).*(date|dob|birth|dt)$|(?i)(dob|dateOfBirth|birthDate)");
    private static final Pattern DOB_FIELD = Pattern.compile("(?i).*(dob|birth).*");
    private static final Pattern WRITE_API = Pattern.compile("(?i)/[^/]*(save|update|delete|insert|add|create|approve|reject|submit|upload|push|confirm|assign|remove|reset|change|generate|send|archive|crud|register)[^/]*$");
    private static final Pattern READ_API = Pattern.compile("(?i)/[^/]*(search|list|get|find|fetch|view|query|report|check|verify|validate|read|download|filter|count|login|me|info|detail|status)[^/]*$");
    private static final Pattern EXCLUDED = Pattern.compile("(?i).*(logout|signout|sign-out|take ?over|verify.?otp|resend.?otp|validate.?otp|captcha|getloginpageconfig|getssologinmeta|initiatesso|ckyc_image).*");
    private static final Pattern LEGACY_WRITE = Pattern.compile("(?i)/[^/]*(save|update|delete|insert|add|create|approve|reject|submit|upload|push|confirm|assign|remove|reset|change|generate|send|archive)[^/]*$");
    private static final Pattern LOGIN = Pattern.compile("(?i).*(login|signin|sign-in|authenticate|/token|oauth).*");
    private static final Pattern QUERY_VAR = Pattern.compile("([?&])(scenarioCode|templateCode|groupId|jobId|customerId|cmId|id)=([^&]*)");
    private static final Pattern XML_LEAF = Pattern.compile("<((?:[\\w.-]+:)?([\\w.-]+))(\\s[^>]*)?>([^<]*)</\\1>");

    public static final class Options {
        /** Invalid-data negatives (wrong type, missing, boundary, injection, dates ...). */
        public boolean data = true;
        /** Security negatives (no session, tampered token/CSRF/encryption, other user, large payload, wrong method). */
        public boolean security = true;
        /** Also generate data negatives for create/update/delete endpoints (an accepted bad request may create junk records). */
        public boolean includeWriteData = false;
        public boolean largePayload = true;
        public boolean allMethods = false;
    }

    public static final class Result {
        public final List<TestCase> tests = new ArrayList<>();
        /** reason -> number of endpoints, shown to the user so nothing is skipped silently */
        public final Map<String, Integer> skipped = new LinkedHashMap<>();
        public int endpoints;

        void skip(String why) {
            skipped.merge(why, 1, Integer::sum);
        }
    }

    private TestGenerator() {}

    /** All eligible endpoints of the model (each must have a positive test). */
    public static Result generateAll(ApiModel model, Options o, AiMemory memory, AiMemory.Data mem, Set<String> onlyEndpoints) {
        Result res = new Result();
        List<TestCase> pending = new ArrayList<>();
        for (Endpoint ep : model.endpoints) {
            if (onlyEndpoints != null && !onlyEndpoints.contains(ep.id)) continue;
            List<TestCase> g = generate(model, ep, o, memory, mem, res);
            if (!g.isEmpty()) {
                res.endpoints++;
                pending.addAll(g);
                model.testCases.addAll(g); // keeps ids unique while generating; removed again below (preview only)
            }
        }
        model.testCases.removeAll(pending);
        res.tests.addAll(pending);
        return res;
    }

    public static List<TestCase> generate(ApiModel model, Endpoint ep, Options o, AiMemory memory, AiMemory.Data mem) {
        return generate(model, ep, o, memory, mem, new Result());
    }

    static List<TestCase> generate(ApiModel model, Endpoint ep, Options o, AiMemory memory, AiMemory.Data mem, Result res) {
        List<TestCase> out = new ArrayList<>();
        TestCase positive = null;
        for (TestCase t : model.testCases) {
            if (ep.id.equals(t.endpointId) && "POSITIVE".equals(t.category)) {
                positive = t;
                break;
            }
        }
        if (positive == null) {
            res.skip("endpoint has no positive test (negatives are derived from a positive test)");
            return out;
        }
        if (positive.tags.contains("run-flag-N")) {
            res.skip("positive test is switched off (Run=N)");
            return out;
        }
        String path = ep.path == null ? "" : ep.path;
        String nameAndPath = (ep.name == null ? "" : ep.name) + " " + path;
        String full = (ep.module == null ? "" : ep.module) + " | " + nameAndPath;
        for (String rx : model.negativeExclude) {
            if (Pattern.compile(rx, Pattern.CASE_INSENSITIVE).matcher(full).find()) {
                res.skip("excluded by the project (negativeExclude: " + rx + ")");
                return out;
            }
        }
        if (EXCLUDED.matcher(nameAndPath).matches()) {
            res.skip("helper endpoint (logout / OTP verify / SSO / binary download)");
            return out;
        }
        Ctx c = new Ctx(model, ep, positive, o, memory, mem, out);

        ObjectNode json = ep.requestBody instanceof ObjectNode ? (ObjectNode) ep.requestBody : null;
        String text = ep.requestBody != null && ep.requestBody.isTextual() ? ep.requestBody.asText().trim() : null;

        if (isLogin(ep)) {
            if (o.data && json != null) loginNegatives(c, json);
            return out;
        }
        boolean write = isWriteApi(ep, "excel".equals(model.api.source.type));
        boolean dataAllowed = o.data && (!write || o.includeWriteData);
        if (o.data && write && !o.includeWriteData) {
            res.skip("write API: data negatives skipped (an accepted bad request could create junk records; ask to include write APIs)");
        }

        if (dataAllowed && json != null) {
            jsonNegatives(c, json, o);
        } else if (text != null && text.startsWith("<")) {
            if (o.security || dataAllowed) xmlNegatives(c, text, !dataAllowed);
        } else if (dataAllowed) {
            queryNegatives(c);
        }
        if (o.security) securityNegatives(c, json, write, dataAllowed);
        if (!write && !out.isEmpty()) {
            c.add("STATE_CHECK", "Valid request still works after the negatives", "original valid request sent again",
                    "Same result as the positive test (session, permissions and data unchanged)", m -> { });
        }
        return out;
    }

    // ------------------------------------------------------------------ rule groups

    private static void loginNegatives(Ctx c, ObjectNode json) {
        String pw = firstMatching(json, "(?i).*pass.*");
        if (pw == null) return;
        c.add("WRONG_PASSWORD", "Wrong password (real user, one attempt)", "password = wrong value", "Rejected: invalid username or password",
                m -> m.setBodyFields.put(pw, F.textNode("Wrong@Pass_${RANDOM:3}")));
        c.add("VERIFY_LOGIN", "Valid login still works after the failed attempt", "correct password again", "Login successful - account is not locked",
                m -> {
                    if (json.has("sessionId") && json.get("sessionId").isNumber()) m.setBodyFields.put("sessionId", F.numberNode(1));
                });
    }

    private static void jsonNegatives(Ctx c, ObjectNode json, Options o) {
        String idField = firstPresent(json, ID_FIELDS);
        if (idField == null) idField = firstOther(json);
        JsonNode orig = idField == null ? null : json.get(idField);
        boolean numeric = orig != null && (orig.isNumber() || looksNumeric(orig.asText())
                || idField.matches("(?i).*(cmId|Id)$") && !idField.matches("(?i)(customerId|custId|downloadRefId|scenarioCode|templateCode|searchValue)"));
        if (idField != null) {
            final String f = idField;
            final boolean num = numeric;
            final boolean isArr = orig != null && orig.isArray();
            c.add("NOT_EXISTING", "Not-existing record", f + " = " + (num ? "999999999" : "\"ZZNOTEXIST999\""), "Rejected as 'not found', or no data returned",
                    m -> m.setBodyFields.put(f, isArr ? F.arrayNode().add("ZZNOTEXIST999") : F.textNode(num ? "999999999" : "ZZNOTEXIST999")));
            c.add("WRONG_TYPE", "Wrong data type", f + (num ? " = \"ABC\" (text instead of number)" : " = 12345 (number instead of text)"),
                    "Rejected with a validation message (4xx), no server error",
                    m -> m.setBodyFields.put(f, num ? F.textNode("ABC") : F.numberNode(12345)));
            c.add("MISSING_FIELD", "Missing mandatory field", f + " removed", "Rejected: '" + f + "' is required", m -> m.removeBodyFields.add(f));
            c.add("EMPTY_VALUE", "Empty value", f + " = \"\"", "Rejected: '" + f + "' must not be empty",
                    m -> m.setBodyFields.put(f, isArr ? F.arrayNode() : F.textNode("")));
        }
        String enumField = firstPresent(json, ENUM_FIELDS);
        if (enumField != null && !enumField.equals(idField)) {
            final String f = enumField;
            boolean num = json.get(f).isNumber() || looksNumeric(json.get(f).asText());
            c.add("INVALID_CODE", "Invalid code / flag", f + " = " + (num ? "99" : "\"XX\""), "Rejected: invalid " + f,
                    m -> m.setBodyFields.put(f, num ? F.numberNode(99) : F.textNode("XX")));
        }
        if (idField != null) {
            final String f = idField;
            final String big = (numeric ? "9" : "A").repeat(300);
            c.add("BOUNDARY", "Boundary / length", f + " = 300 characters", "Rejected (too long) or no data returned, no server error",
                    m -> m.setBodyFields.put(f, F.textNode(big)));
            c.add("INJECTION", "SQL injection characters", f + " = \"' OR '1'='1\"", "Rejected or no data; no SQL / internal error leaked",
                    m -> m.setBodyFields.put(f, F.textNode("' OR '1'='1")));
        }
        String textField = firstPresent(json, TEXT_FIELDS);
        if (textField == null) textField = idField;
        if (textField != null) {
            final String f = textField;
            c.add("SPECIAL_CHARS", "Special characters", f + " = " + SPECIAL, "Rejected (special characters not allowed) or no data; no server error",
                    m -> m.setBodyFields.put(f, F.textNode(SPECIAL)));
            c.add("XSS_SCRIPT", "Script / HTML tags", f + " = " + XSS, "Rejected, or not sent back unescaped (XSS)",
                    m -> m.setBodyFields.put(f, F.textNode(XSS)));
            c.add("UNICODE", "Unicode / emoji characters", f + " = " + UNICODE, "Rejected or no data; no server error",
                    m -> m.setBodyFields.put(f, F.textNode(UNICODE)));
            c.add("SPACES_ONLY", "Only spaces", f + " = 5 spaces", "Rejected: '" + f + "' must not be blank (spaces are not a value)",
                    m -> m.setBodyFields.put(f, F.textNode("     ")));
        }
        dateNegatives(c, json);
    }

    private static void dateNegatives(Ctx c, ObjectNode json) {
        List<String> dates = new ArrayList<>();
        json.fieldNames().forEachRemaining(f -> {
            if (DATE_FIELD.matcher(f).matches() && json.get(f).isValueNode()) dates.add(f);
        });
        if (dates.isEmpty()) return;
        String dob = dates.stream().filter(f -> DOB_FIELD.matcher(f).matches()).findFirst().orElse(null);
        if (dob != null) {
            String fmt = formatOf(json.get(dob).asText());
            String other = otherFormat(fmt);
            c.add("DOB_TODAY", "Date of birth = today", dob + " = today", "Rejected: date of birth cannot be today", m -> m.setBodyFields.put(dob, F.textNode("${DATE:0:" + fmt + "}")));
            c.add("DOB_FUTURE", "Date of birth in the future", dob + " = tomorrow", "Rejected: date of birth cannot be in the future", m -> m.setBodyFields.put(dob, F.textNode("${DATE:1:" + fmt + "}")));
            c.add("DOB_INVALID", "Impossible date of birth", dob + " = " + impossible(fmt) + " (31 February)", "Rejected: not a valid calendar date", m -> m.setBodyFields.put(dob, F.textNode(impossible(fmt))));
            c.add("DOB_TOO_OLD", "Date of birth too old", dob + " = year 1800", "Rejected: unrealistic date of birth", m -> m.setBodyFields.put(dob, F.textNode(fmt.replace("yyyy", "1800").replace("MM", "01").replace("dd", "01"))));
            c.add("DATE_FORMAT", "Date of birth in wrong format", dob + " in " + other, "Rejected: date must be " + fmt, m -> m.setBodyFields.put(dob, F.textNode("${DATE:-9000:" + other + "}")));
            return;
        }
        String from = dates.stream().filter(f -> f.toLowerCase().contains("from") || f.toLowerCase().contains("start")).findFirst().orElse(dates.get(0));
        String to = dates.stream().filter(f -> f.toLowerCase().contains("to") && !f.equals(from) || f.toLowerCase().contains("end")).findFirst().orElse(null);
        String fmt = formatOf(json.get(from).asText());
        String other = otherFormat(fmt);
        c.add("DATE_FUTURE", "Date in the future", from + " = one year ahead", "Rejected, or no data returned for a future date", m -> m.setBodyFields.put(from, F.textNode("${DATE:365:" + fmt + "}")));
        c.add("DATE_INVALID", "Impossible date", from + " = " + impossible(fmt), "Rejected: not a valid calendar date", m -> m.setBodyFields.put(from, F.textNode(impossible(fmt))));
        c.add("DATE_FORMAT", "Date in wrong format", from + " in " + other, "Rejected: date must be " + fmt, m -> m.setBodyFields.put(from, F.textNode("${DATE:-7:" + other + "}")));
        if (to != null) {
            c.add("DATE_RANGE", "From-date after to-date", from + " = today, " + to + " = 30 days ago", "Rejected: from-date must not be after to-date (or no data)",
                    m -> {
                        m.setBodyFields.put(from, F.textNode("${DATE:0:" + fmt + "}"));
                        m.setBodyFields.put(to, F.textNode("${DATE:-30:" + fmt + "}"));
                    });
        }
    }

    private static void xmlNegatives(Ctx c, String xml, boolean securityOnly) {
        c.add("MALFORMED_XML", "Malformed XML", "last closing tag removed", "Rejected: not well-formed XML (400)",
                m -> m.bodyOverride = xml.substring(0, xml.lastIndexOf("</") > 0 ? xml.lastIndexOf("</") : xml.length()));
        Matcher m0 = XML_LEAF.matcher(xml);
        int chosen = -1;
        while (m0.find()) {
            if (chosen < 0) chosen = m0.start();
            if (ID_FIELDS.contains(m0.group(2))) {
                chosen = m0.start();
                break;
            }
        }
        if (chosen < 0) return;
        final Matcher f = XML_LEAF.matcher(xml);
        if (!f.find(chosen)) return;
        String field = f.group(2), value = f.group(4);
        java.util.function.Function<String, String> set = v -> xml.substring(0, f.start(4)) + v + xml.substring(f.end(4));
        String doc = "<!DOCTYPE autoqa [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>";
        String withEntity = set.apply("&xxe;");
        String xxe = withEntity.startsWith("<?xml") && withEntity.contains("?>")
                ? withEntity.substring(0, withEntity.indexOf("?>") + 2) + doc + withEntity.substring(withEntity.indexOf("?>") + 2) : doc + withEntity;
        c.add("XXE", "XML external entity (XXE)", field + " = &xxe;", "Rejected (400); the file content must never appear in the response", m -> m.bodyOverride = xxe);
        if (securityOnly) return;
        boolean numeric = looksNumeric(value) || field.matches("(?i).*Id$");
        c.add("NOT_EXISTING", "Not-existing record", field + " = " + (numeric ? "999999999" : "ZZNOTEXIST999"), "Rejected as 'not found', or no data returned",
                m -> m.bodyOverride = set.apply(numeric ? "999999999" : "ZZNOTEXIST999"));
        if (numeric) c.add("WRONG_TYPE", "Wrong data type", field + " = ABC", "Rejected with a validation message (4xx)", m -> m.bodyOverride = set.apply("ABC"));
        c.add("MISSING_FIELD", "Missing mandatory field", "<" + field + "> removed", "Rejected: '" + field + "' is required",
                m -> m.bodyOverride = xml.substring(0, f.start()) + xml.substring(f.end()));
        c.add("EMPTY_VALUE", "Empty value", field + " = (empty)", "Rejected: '" + field + "' must not be empty", m -> m.bodyOverride = set.apply(""));
        c.add("SPACES_ONLY", "Only spaces", field + " = 5 spaces", "Rejected: spaces are not a value", m -> m.bodyOverride = set.apply("     "));
        c.add("BOUNDARY", "Boundary / length", field + " = 300 characters", "Rejected (too long)", m -> m.bodyOverride = set.apply((numeric ? "9" : "A").repeat(300)));
        c.add("INJECTION", "SQL injection characters", field + " = ' OR '1'='1", "Rejected or no data; no SQL / internal error leaked", m -> m.bodyOverride = set.apply("' OR '1'='1"));
        c.add("XSS_SCRIPT", "Script / HTML tags", field + " = <script> (XML-escaped)", "Rejected, or not sent back unescaped",
                m -> m.bodyOverride = set.apply("&lt;script&gt;alert('x')&lt;/script&gt;"));
    }

    /** GET endpoints: parameters in the path (?a=b) or in the endpoint's query map. */
    private static void queryNegatives(Ctx c) {
        Endpoint ep = c.ep;
        String path = ep.path == null ? "" : ep.path;
        Matcher m = QUERY_VAR.matcher(path);
        if (m.find()) {
            final String p = m.group(2);
            final String pre = path.substring(0, m.start()) + m.group(1) + p + "=";
            final String post = path.substring(m.end());
            c.add("NOT_EXISTING", "Not-existing record", p + " = ZZNOTEXIST999", "Rejected as 'not found', or no data returned", mu -> mu.path = pre + "ZZNOTEXIST999" + post);
            c.add("EMPTY_VALUE", "Empty value", p + " = (empty)", "Rejected: '" + p + "' is required, or no data returned", mu -> mu.path = pre + post);
            c.add("INJECTION", "Special / injection characters", p + " = ' OR '1'='1", "Rejected or no data; no SQL / internal error leaked",
                    mu -> mu.path = pre + "%27%20OR%20%271%27%3D%271" + post);
            return;
        }
        int n = 0;
        for (Map.Entry<String, String> q : ep.query.entrySet()) {
            if (n++ >= 3) break;
            final String p = q.getKey();
            c.add("MISSING_FIELD", "Query parameter removed", p + " removed", "Rejected: '" + p + "' is required, or no data returned", mu -> mu.removeQuery.add(p));
            c.add("WRONG_TYPE", "Query parameter with the wrong type", p + " = abc", "Rejected with a validation message (4xx)", mu -> mu.setQuery.put(p, "abc"));
            c.add("INJECTION", "Query parameter with injection characters", p + " = ' OR '1'='1", "Rejected or no data; no SQL / internal error leaked",
                    mu -> mu.setQuery.put(p, "' OR '1'='1"));
        }
        Matcher seg = Pattern.compile("/(\\d{1,18})(?=/|$)").matcher(path);
        String last = null;
        while (seg.find()) last = seg.group(0);
        if (last != null && "GET".equals(ep.method)) {
            final String s = last;
            c.add("NOT_EXISTING", "Record id that does not exist", "id in the path = 987654321", "Rejected as 'not found', or no data returned",
                    mu -> mu.path = path.replace(s, "/987654321"));
        }
    }

    private static void securityNegatives(Ctx c, ObjectNode json, boolean write, boolean dataAllowed) {
        Endpoint ep = c.ep;
        boolean protectedEp = ep.authProfileRef != null || ep.headers.keySet().stream()
                .anyMatch(k -> k.toLowerCase().matches(".*(authorization|token|csrf|xsrf|api-?key).*"));
        if (protectedEp) {
            c.add("NO_SESSION", "No login / session", "cookies, CSRF and token removed", "Rejected: not signed in (401 / 403 / 422)", m -> {
                m.removeHeaders.add("Authorization");
                ep.headers.keySet().stream().filter(k -> k.toLowerCase().matches(".*(token|csrf|xsrf|api-?key).*")).forEach(m.removeHeaders::add);
            });
            boolean bearer = ep.headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Authorization")) || ep.authProfileRef != null;
            if (bearer) {
                c.add("TAMPERED_TOKEN", "Tampered bearer token", "Authorization: Bearer <real token with the last 6 characters changed>",
                        "Rejected: token is invalid (401)", m -> m.setHeaders.put("Authorization", "Bearer ${token?}Zz9Zz9"));
            }
            String csrf = ep.headers.keySet().stream().filter(k -> k.toLowerCase().contains("csrf") || k.toLowerCase().contains("xsrf")).findFirst().orElse(null);
            if (csrf != null && !"GET".equals(ep.method) && !"HEAD".equals(ep.method)) {
                c.add("TAMPERED_CSRF", "Wrong CSRF token", csrf + " value changed", "Rejected: CSRF check failed (403)",
                        m -> m.setHeaders.put(csrf, "${csrf_token?}Zz9Zz9"));
            }
        }
        if (json != null && json.has("userId")) {
            c.add("OTHER_USER", "Another user's id", "userId = \"1\" (not the logged-in user)", "Rejected: user does not match the session",
                    m -> m.setBodyFields.put("userId", F.textNode("1")));
        }
        boolean body = ep.requestBody != null && !ep.requestBody.isNull();
        if (c.model.envelope != null && "hybrid-rsa-aes-gcm".equals(c.model.envelope.type) && body
                && c.model.envelope.skipPaths.stream().noneMatch(sp -> !sp.isBlank() && ep.path != null && ep.path.contains(sp))) {
            c.add("TAMPERED_ENCRYPTION", "Tampered encryption", "clientsecret header corrupted", "Rejected: request cannot be decrypted (4xx)", m -> { });
        }
        if (c.o.largePayload && json != null && !write) {
            // like the legacy engine: never on write APIs, an accepted 1 MB body would become a junk record
            c.add("LARGE_PAYLOAD", "Very large payload", "extra field with 1 MB of text", "Rejected (413 / 4xx), no slowdown or crash",
                    m -> m.setBodyFields.put("zzLargeField", F.textNode("${PAD:1048576}")));
        }
        List<String> methods = new ArrayList<>();
        if (c.o.allMethods) {
            // legacy rule: PUT / PATCH / DELETE only on read-only APIs, so nothing can be changed by mistake
            for (String w : write ? new String[] { "GET", "POST" } : new String[] { "GET", "POST", "PUT", "PATCH", "DELETE" }) {
                if (!w.equals(ep.method)) methods.add(w);
            }
        } else {
            methods.add("GET".equals(ep.method) ? "PATCH" : "GET");
        }
        if ("WS".equals(ep.method) || "GRPC".equals(ep.method)) methods.clear();   // a WebSocket has no HTTP method to get wrong
        for (String wrong : methods) {
            c.add("WRONG_METHOD", "Wrong HTTP method", ep.method + " -> " + wrong, "Rejected: 405 Method Not Allowed (or 4xx)", m -> m.method = wrong);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Collects tests for one endpoint, with unique ids and dedupe. */
    private static final class Ctx {
        final ApiModel model;
        final Endpoint ep;
        final TestCase positive;
        final Options o;
        final AiMemory memory;
        final AiMemory.Data mem;
        final List<TestCase> out;
        final Set<String> seen = new HashSet<>();
        int seq;

        Ctx(ApiModel model, Endpoint ep, TestCase positive, Options o, AiMemory memory, AiMemory.Data mem, List<TestCase> out) {
            this.model = model;
            this.ep = ep;
            this.positive = positive;
            this.o = o;
            this.memory = memory;
            this.mem = mem;
            this.out = out;
            Pattern p = Pattern.compile(Pattern.quote(ep.id) + "_N(\\d+)$");
            for (TestCase t : model.testCases) {
                if (t.id == null) continue;
                Matcher m = p.matcher(t.id);
                if (m.find()) seq = Math.max(seq, Integer.parseInt(m.group(1)));
                if (ep.id.equals(t.endpointId)) seen.add(sig(t));
            }
        }

        void add(String category, String title, String change, String expected, java.util.function.Consumer<Mutation> fill) {
            if (memory != null && memory.isDismissed(mem, ep.id, category)) return;
            // protobuf is typed and binary: the server never sees a wrong type or an XML payload, so these checks do not apply to gRPC
            if ("GRPC".equals(ep.method) && java.util.Set.of("WRONG_TYPE", "MALFORMED_XML", "XXE", "DATE_FORMAT", "SPACES_ONLY").contains(category)) return;
            TestCase t = new TestCase();
            t.endpointId = ep.id;
            t.category = category;
            t.title = (ep.name == null ? ep.id : ep.name) + " - " + title;
            t.mutation = new Mutation();
            fill.accept(t.mutation);
            t.lifecycle = "DRAFT";
            t.origin = "generated";
            t.basedOn = positive.id;
            t.tags.add("auto");
            t.tags.add("change: " + change);
            t.tags.add("expect: " + expected);
            if (memory != null) {
                List<Integer> learned = memory.learnedStatus(mem, category);
                if (!learned.isEmpty()) {
                    t.expected.status.addAll(learned);
                    t.tags.add("expected status learned from this project");
                }
            }
            if (!seen.add(sig(t))) return;
            t.id = ep.id + "_N" + String.format("%02d", ++seq);
            out.add(t);
        }
    }

    private static String sig(TestCase t) {
        try {
            return t.category + "|" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(t.mutation);
        } catch (Exception e) {
            return t.category + "|" + t.title;
        }
    }

    static boolean isLogin(Endpoint ep) {
        return ep.path != null && LOGIN.matcher(ep.path).matches()
                && ep.extract.stream().anyMatch(x -> x.var != null && x.var.toLowerCase().contains("token"));
    }

    /** legacyVerbsOnly: Excel-imported projects keep the legacy rule (verb in the last path segment only). */
    static boolean isWriteApi(Endpoint ep, boolean legacyVerbsOnly) {
        String p = (ep.path == null ? "" : ep.path.replaceAll("\\?.*", ""));
        if (legacyVerbsOnly) return LEGACY_WRITE.matcher(p).find();
        if (WRITE_API.matcher(p).find()) return true;
        if (READ_API.matcher(p).find()) return false;
        String m = ep.method == null ? "GET" : ep.method;
        return m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE") || m.equals("POST");
    }

    private static String firstPresent(ObjectNode json, List<String> names) {
        for (String f : names) if (json.has(f)) return f;
        return null;
    }

    private static String firstMatching(ObjectNode json, String regex) {
        Iterator<String> it = json.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.matches(regex)) return f;
        }
        return null;
    }

    private static String firstOther(ObjectNode json) {
        Iterator<String> it = json.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!f.equalsIgnoreCase("userId") && !f.equalsIgnoreCase("requestID") && !ENUM_FIELDS.contains(f) && !DATE_FIELD.matcher(f).matches()) return f;
        }
        return null;
    }

    private static boolean looksNumeric(String s) {
        return s != null && s.matches("-?\\d+");
    }

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

    private static String impossible(String fmt) {
        return fmt.replace("yyyy", "2000").replace("MM", "02").replace("dd", "31");
    }
}
