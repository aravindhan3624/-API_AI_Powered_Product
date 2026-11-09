package com.api.product.core.flow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Turns the written business rules of a requirement document into reviewable test proposals. Only sentences that match a clear,
 * testable pattern become proposals (required field, length, allowed values, numeric limits, response must contain / must not contain,
 * response time, success status). Every proposal keeps its source sentence and rule id; anything unclear (which API? which field? vague
 * wording) is flagged as an ambiguity for the user to settle. Nothing is applied without approval, and nothing is guessed silently.
 */
public final class RuleAssertions {

    public static final class Proposal {
        public String id;
        public String ruleId = "";
        public String sentence;
        /** REQUIRED | MAX_LENGTH | EXACT_LENGTH | ALLOWED_VALUES | MIN_VALUE | MAX_VALUE | NOT_NEGATIVE | RESPONSE_HAS | RESPONSE_HIDES | MAX_LATENCY | SUCCESS_STATUS */
        public String kind;
        public String endpointId = "";
        public String endpointLabel = "";
        public String field = "";
        public String value = "";
        /** ready | needs-confirmation */
        public String status = "ready";
        public final List<String> ambiguities = new ArrayList<>();
        public final List<String> candidates = new ArrayList<>();
        public String preview = "";
    }

    private static final Pattern RULE_ID = Pattern.compile("\\b((?:BR|FR|NFR|REQ|RULE|VR)[- ]?\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_LINE = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+`?(/[A-Za-z0-9_\\-./{}:$]*)`?");
    private static final Pattern VAGUE = Pattern.compile("(?i)\\b(appropriate|as per|as applicable|etc\\.?|tbd|to be decided|as needed|suitable|reasonable|properly|fast|quickly|user[- ]friendly|and so on|wherever)\\b");
    private static final Pattern MODAL = Pattern.compile("(?i)\\b(must|shall|should|cannot|can not|can't|required|mandatory|only|at most|at least|maximum|minimum|between|not allowed|no more than|within|exactly|returns? (?:http )?2\\d\\d)\\b");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private RuleAssertions() {}

    public static final class Result {
        public final List<Proposal> proposals = new ArrayList<>();
        /** Sentences that state a rule but match no testable pattern: the user decides what to do with them. */
        public final List<String> unparsed = new ArrayList<>();
    }

    public static Result analyze(ApiModel m, String text) {
        Result res = new Result();
        List<Proposal> out = res.proposals;
        Endpoint current = null;
        int n = 0;
        for (String raw : text.split("\\R|(?<=[.;])\\s+(?=[A-Z])")) {
            String line = raw.replaceAll("[|`*#]+", " ").replaceAll("\\s+", " ").trim();
            if (line.length() < 8) continue;
            Matcher am = API_LINE.matcher(line);
            if (am.find()) {
                Endpoint hit = byPath(m, am.group(1), am.group(2));
                if (hit != null) current = hit;
            } else {
                Endpoint byName = byName(m, line);
                if (byName != null && line.length() < 80 && !MODAL.matcher(line).find()) current = byName;
            }
            if (!MODAL.matcher(line).find()) continue;
            String ruleId = "";
            Matcher rm = RULE_ID.matcher(line);
            if (rm.find()) ruleId = rm.group(1).toUpperCase(Locale.ROOT).replace(' ', '-');
            List<Proposal> found = proposals(m, line, ruleId, current);
            if (found.isEmpty() && (!ruleId.isEmpty() || line.length() > 20) && res.unparsed.size() < 60) {
                res.unparsed.add((ruleId.isEmpty() ? "" : ruleId + ": ") + (line.length() > 240 ? line.substring(0, 240) + "..." : line));
            }
            boolean inherited = !API_LINE.matcher(line).find();
            for (Proposal p : found) {
                if (inherited && !p.endpointId.isEmpty() && current != null && p.endpointId.equals(current.id)) {
                    p.preview += " [API taken from the nearest earlier mention: " + p.endpointLabel + "]";
                }
                p.id = "P" + (++n);
                out.add(p);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ sentence patterns

    private static List<Proposal> proposals(ApiModel m, String s, String ruleId, Endpoint ctx) {
        List<Proposal> list = new ArrayList<>();
        String l = s.toLowerCase(Locale.ROOT);
        Matcher x;

        // response time: "response time must be under 2 seconds / 500 ms"
        x = Pattern.compile("(?i)(?:response|respond|answer)[^.]{0,40}?(?:within|under|below|less than|not exceed|at most|<)\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds?|seconds?|secs?|s)\\b").matcher(s);
        if (x.find()) {
            double v = Double.parseDouble(x.group(1));
            long ms = x.group(2).toLowerCase(Locale.ROOT).startsWith("m") ? (long) v : (long) (v * 1000);
            list.add(make(m, s, ruleId, "MAX_LATENCY", ctx, "", String.valueOf(ms), "Response time at most " + ms + " ms"));
        }
        // success status: "returns 201 on success / must return HTTP 200"
        x = Pattern.compile("(?i)(?:return|returns|respond with|responds with|response code|status code|http status)[^.]{0,30}?\\b([2]\\d\\d)\\b").matcher(s);
        if (x.find() && ctx != null) list.add(make(m, s, ruleId, "SUCCESS_STATUS", ctx, "", x.group(1), "Valid request must answer HTTP " + x.group(1)));
        // response must contain / include <field>
        x = Pattern.compile("(?i)(?:response|result|reply)[^.]{0,40}?(?:must |shall |should )?(?:contain|include|return|have)s?\\s+(?:the\\s+|a\\s+|an\\s+)?[\"'`]?([A-Za-z][A-Za-z0-9_ ]{1,30}?)[\"'`]?(?:\\s+field|\\s+number|\\s+id|\\.|,|;|$| and )").matcher(s);
        if (x.find() && !l.contains("must not") && !l.contains("shall not")) {
            String f = camel(x.group(1));
            if (!f.isEmpty()) list.add(make(m, s, ruleId, "RESPONSE_HAS", ctx, f, "$." + f, "Response must contain '" + f + "'"));
        }
        // must not expose / return
        x = Pattern.compile("(?i)(?:must not|shall not|should not|never|cannot|can not)\\s+(?:expose|return|reveal|show|include|display|contain)\\s+(?:the\\s+|any\\s+)?[\"'`]?([A-Za-z][A-Za-z0-9_ ]{1,30}?)[\"'`]?(?:\\.|,|;|$| in )").matcher(s);
        if (x.find()) {
            String f = camel(x.group(1));
            if (!f.isEmpty()) list.add(make(m, s, ruleId, "RESPONSE_HIDES", ctx, f, f, "Response must not contain '" + f + "'"));
        }
        // allowed values: "must be one of A, B, C" / "allowed values: A, B"
        x = Pattern.compile("(?i)(?:one of|allowed values?(?: are| is)?:?|valid values?(?: are| is)?:?|only accepts?|must be either)\\s*[\\[(]?([A-Za-z0-9_\\- ,/|]+?)[\\])]?(?:\\.|;|$)").matcher(s);
        if (x.find()) {
            String vals = x.group(1).replaceAll("(?i)\\s+or\\s+", ",").replaceAll("[/|]", ",").replaceAll("\\s*,\\s*", ",").trim();
            Proposal p = make(m, s, ruleId, "ALLOWED_VALUES", ctx, fieldIn(m, s, ctx, x.start()), vals, "A value outside [" + vals + "] must be rejected");
            list.add(p);
        }
        // exact length: "must be exactly 10 digits/characters"
        x = Pattern.compile("(?i)(?:exactly|must be|should be|of)\\s+(\\d{1,4})\\s*(?:-)?(digits?|characters?|chars?|letters?)\\b").matcher(s);
        if (x.find() && !l.contains("at most") && !l.contains("maximum")) {
            list.add(make(m, s, ruleId, "EXACT_LENGTH", ctx, fieldIn(m, s, ctx, x.start()), x.group(1) + (x.group(2).toLowerCase(Locale.ROOT).startsWith("d") ? ":digits" : ":chars"),
                    "Exactly " + x.group(1) + " " + x.group(2) + " required; one more or one fewer must be rejected"));
        } else {
            // maximum length: "at most / maximum / up to / not exceed N characters"
            x = Pattern.compile("(?i)(?:at most|maximum(?: of| length)?|max(?:imum)?|up to|no more than|not exceed|within)\\s*(\\d{1,5})\\s*(?:digits?|characters?|chars?|letters?)").matcher(s);
            if (x.find()) list.add(make(m, s, ruleId, "MAX_LENGTH", ctx, fieldIn(m, s, ctx, x.start()), x.group(1), "More than " + x.group(1) + " characters must be rejected"));
        }
        // numeric limits
        x = Pattern.compile("(?i)(?:between|from)\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:and|to|-)\\s*(-?\\d+(?:\\.\\d+)?)\\b").matcher(s);
        if (x.find() && !l.contains("character") && !l.contains("digit")) {
            String f = fieldIn(m, s, ctx, x.start());
            list.add(make(m, s, ruleId, "MIN_VALUE", ctx, f, x.group(1), "Below " + x.group(1) + " must be rejected"));
            list.add(make(m, s, ruleId, "MAX_VALUE", ctx, f, x.group(2), "Above " + x.group(2) + " must be rejected"));
        } else {
            x = Pattern.compile("(?i)(?:minimum|at least|not less than|no less than|min)\\s*(?:of|value of)?\\s*(-?\\d+(?:\\.\\d+)?)\\b(?!\\s*(?:digits?|characters?|chars?))").matcher(s);
            if (x.find()) list.add(make(m, s, ruleId, "MIN_VALUE", ctx, fieldIn(m, s, ctx, x.start()), x.group(1), "Below " + x.group(1) + " must be rejected"));
            x = Pattern.compile("(?i)(?:maximum|not more than|up to|at most|max)\\s*(?:of|value of)?\\s*(-?\\d+(?:\\.\\d+)?)\\b(?!\\s*(?:digits?|characters?|chars?))").matcher(s);
            if (x.find() && list.stream().noneMatch(p -> p.kind.equals("MAX_LENGTH") || p.kind.equals("MAX_VALUE"))) {
                list.add(make(m, s, ruleId, "MAX_VALUE", ctx, fieldIn(m, s, ctx, x.start()), x.group(1), "Above " + x.group(1) + " must be rejected"));
            }
        }
        if (l.matches(".*(cannot be negative|must not be negative|greater than zero|more than zero|positive (?:number|amount|value)|non-negative).*")) {
            list.add(make(m, s, ruleId, "NOT_NEGATIVE", ctx, fieldIn(m, s, ctx, 0), "-1", "A negative value must be rejected"));
        }
        // required: "<field> is required / mandatory / must be provided"
        x = Pattern.compile("(?i)(?:is|are)?\\s*(?:required|mandatory|compulsory|must be (?:provided|supplied|present|entered|sent))").matcher(s);
        if (x.find() && !l.contains("not required") && !l.contains("not mandatory")) {
            list.add(make(m, s, ruleId, "REQUIRED", ctx, fieldIn(m, s, ctx, x.start()), "", "Request without the field must be rejected"));
        }
        // de-duplicate by kind+field
        Set<String> seen = new LinkedHashSet<>();
        list.removeIf(p -> !seen.add(p.kind + "|" + p.field + "|" + p.value));
        return list;
    }

    private static Proposal make(ApiModel m, String sentence, String ruleId, String kind, Endpoint ctx, String field, String value, String preview) {
        Proposal p = new Proposal();
        p.sentence = sentence.length() > 300 ? sentence.substring(0, 300) + "..." : sentence;
        p.ruleId = ruleId;
        p.kind = kind;
        p.field = field;
        p.value = value;
        p.preview = preview;
        boolean needsField = !kind.equals("MAX_LATENCY") && !kind.equals("SUCCESS_STATUS");
        Endpoint ep = ctx;
        if (needsField && !kind.equals("RESPONSE_HAS") && !kind.equals("RESPONSE_HIDES")) {
            if (field.isEmpty()) {
                p.ambiguities.add("Which field does this rule apply to? The sentence does not name one the API accepts.");
            } else if (ep == null || !hasBodyField(ep, field)) {
                List<Endpoint> owners = new ArrayList<>();
                for (Endpoint e : m.endpoints) if (hasBodyField(e, field)) owners.add(e);
                if (ep == null || !owners.contains(ep)) {
                    if (owners.size() == 1) ep = owners.get(0);
                    else if (owners.size() > 1) {
                        ep = null;
                        owners.stream().limit(8).forEach(e -> p.candidates.add(e.id + "  " + e.method + " " + e.path));
                        p.ambiguities.add("The field '" + field + "' exists in " + owners.size() + " APIs; choose which one the rule is about.");
                    } else {
                        ep = null;
                        p.ambiguities.add("No API has a body field called '" + field + "'.");
                    }
                }
            }
        }
        if (ep == null && p.ambiguities.isEmpty()) p.ambiguities.add("Which API does this rule belong to? Name it as 'POST /path' near the rule.");
        if (ep != null) {
            p.endpointId = ep.id;
            p.endpointLabel = ep.method + " " + ep.path;
        }
        if (VAGUE.matcher(sentence).find()) p.ambiguities.add("Vague wording (\"" + firstVague(sentence) + "\"): confirm the exact expected behaviour.");
        if (kind.equals("MAX_LATENCY") && Long.parseLong(value) < 50) p.ambiguities.add("Under 50 ms is unrealistic over a network; confirm the limit.");
        if (!p.ambiguities.isEmpty()) p.status = "needs-confirmation";
        return p;
    }

    private static String firstVague(String s) {
        Matcher m = VAGUE.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** The body field the sentence is about: a field that appears (normalised) in the sentence, preferring the endpoint in context. */
    private static String fieldIn(ApiModel m, String sentence, Endpoint ctx, int near) {
        String ns = norm(sentence);
        String best = "";
        Map<String, Integer> pool = new LinkedHashMap<>();
        if (ctx != null) fields(ctx).forEach(f -> pool.put(f, 2));
        else for (Endpoint e : m.endpoints) fields(e).forEach(f -> pool.merge(f, 1, Integer::sum));
        for (String f : pool.keySet()) {
            String nf = norm(f);
            if (nf.length() < 3 || nf.equals("id")) continue;
            if (ns.contains(nf) && nf.length() > norm(best).length()) best = f;
        }
        if (best.isEmpty()) {
            Matcher q = Pattern.compile("[\"'`]([A-Za-z][A-Za-z0-9_]{1,40})[\"'`]").matcher(sentence);
            if (q.find()) best = q.group(1);
        }
        return best;
    }

    // ------------------------------------------------------------------ model helpers

    static List<String> fields(Endpoint e) {
        List<String> out = new ArrayList<>();
        if (e.requestBody != null) collect(e.requestBody, out, 0);
        e.query.keySet().forEach(out::add);
        return out;
    }

    private static void collect(JsonNode n, List<String> out, int d) {
        if (n == null || d > 4) return;
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isValueNode()) out.add(e.getKey());
                collect(e.getValue(), out, d + 1);
            }
        } else if (n.isArray() && n.size() > 0) {
            collect(n.get(0), out, d + 1);
        }
    }

    private static boolean hasBodyField(Endpoint e, String field) {
        for (String f : fields(e)) if (norm(f).equals(norm(field))) return true;
        return false;
    }

    private static Endpoint byPath(ApiModel m, String method, String path) {
        String want = normPath(path.replaceAll("[:.,;)]+$", ""));
        for (Endpoint e : m.endpoints) {
            if (!e.method.equalsIgnoreCase(method)) continue;
            String tpl = e.specKey != null ? e.specKey.replaceFirst("^\\S+ ", "") : e.path;
            if (normPath(tpl).equals(want) || normPath(e.path).equals(want)) return e;
        }
        return null;
    }

    private static Endpoint byName(ApiModel m, String line) {
        String nl = norm(line);
        for (Endpoint e : m.endpoints) if (e.name != null && e.name.length() > 3 && nl.equals(norm(e.name))) return e;
        return null;
    }

    private static String normPath(String p) {
        return p.toLowerCase(Locale.ROOT).replaceAll("\\$\\{[^}]*}|\\{[^}]*}", "{}").replaceAll("/+$", "");
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String camel(String words) {
        String[] p = words.trim().split("\\s+");
        if (p.length > 3) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            String w = p[i].replaceAll("[^A-Za-z0-9_]", "");
            if (w.isEmpty()) continue;
            if (w.matches("(?i)the|a|an|any|field|following|data")) continue;
            sb.append(sb.length() == 0 ? w.substring(0, 1).toLowerCase(Locale.ROOT) + w.substring(1) : w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ applying

    /** Creates the DRAFT tests / assertions of an approved proposal. Returns a short description, or null when nothing could be made. */
    public static String apply(ApiModel m, Proposal p) {
        Endpoint ep = m.endpoint(p.endpointId);
        if (ep == null) return null;
        TestCase pos = null;
        for (TestCase t : m.testCases) if (ep.id.equals(t.endpointId) && "POSITIVE".equals(t.category)) { pos = t; break; }
        String tag = p.ruleId.isEmpty() ? "brd" : p.ruleId;
        int made = 0;
        switch (p.kind) {
            case "MAX_LATENCY":
                if (pos == null) return null;
                pos.expected.assertions.add(assertion("maxLatencyMs", null, F.numberNode(Long.parseLong(p.value))));
                return "added a response-time assertion to " + pos.id;
            case "SUCCESS_STATUS":
                if (pos == null) return null;
                pos.expected.status.clear();
                pos.expected.status.add(Integer.parseInt(p.value));
                return "positive test " + pos.id + " now expects HTTP " + p.value;
            case "RESPONSE_HAS":
                if (pos == null) return null;
                pos.expected.assertions.add(assertion("jsonPathExists", p.value, null));
                return "added 'response has " + p.field + "' to " + pos.id;
            case "RESPONSE_HIDES":
                if (pos == null) return null;
                pos.expected.assertions.add(assertion("bodyNotContains", null, F.textNode("\"" + p.value + "\"")));
                return "added 'response must not contain " + p.field + "' to " + pos.id;
            case "REQUIRED":
                made += add(m, ep, pos, p, tag, "MISSING_FIELD", "field '" + p.field + "' missing", mut -> mut.removeBodyFields.add(p.field), null);
                made += add(m, ep, pos, p, tag, "EMPTY_VALUE", "field '" + p.field + "' empty", mut -> mut.setBodyFields.put(p.field, F.textNode("")), null);
                break;
            case "MAX_LENGTH":
                made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' longer than " + p.value, mut -> mut.setBodyFields.put(p.field, F.textNode("A".repeat(Integer.parseInt(p.value) + 1))), null);
                break;
            case "EXACT_LENGTH": {
                int len = Integer.parseInt(p.value.split(":")[0]);
                boolean digits = p.value.endsWith("digits");
                made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' one too long", mut -> mut.setBodyFields.put(p.field, F.textNode((digits ? "7" : "A").repeat(len + 1))), null);
                if (len > 1) made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' one too short", mut -> mut.setBodyFields.put(p.field, F.textNode((digits ? "7" : "A").repeat(len - 1))), null);
                break;
            }
            case "ALLOWED_VALUES":
                made += add(m, ep, pos, p, tag, "INVALID_CODE", "field '" + p.field + "' outside the allowed values", mut -> mut.setBodyFields.put(p.field, F.textNode("ZZ_NOT_ALLOWED")), null);
                break;
            case "MIN_VALUE": {
                double v = Double.parseDouble(p.value) - 1;
                made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' below the minimum " + p.value, mut -> mut.setBodyFields.put(p.field, num(v)), null);
                break;
            }
            case "MAX_VALUE": {
                double v = Double.parseDouble(p.value) + 1;
                made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' above the maximum " + p.value, mut -> mut.setBodyFields.put(p.field, num(v)), null);
                break;
            }
            case "NOT_NEGATIVE":
                made += add(m, ep, pos, p, tag, "BOUNDARY", "field '" + p.field + "' negative", mut -> mut.setBodyFields.put(p.field, F.numberNode(-1)), null);
                break;
            default:
                return null;
        }
        return made == 0 ? null : made + " DRAFT test(s) for " + p.ruleId + " " + p.kind;
    }

    private static JsonNode num(double v) {
        return v == Math.rint(v) ? F.numberNode((long) v) : F.numberNode(v);
    }

    private interface MutFn {
        void accept(Mutation m);
    }

    private static int add(ApiModel m, Endpoint ep, TestCase pos, Proposal p, String tag, String category, String what, MutFn fn, String unused) {
        if (p.field.isEmpty()) return 0;
        TestCase t = new TestCase();
        int n = 1;
        String base = "TC_BRD_" + ep.id + "_" + (p.ruleId.isEmpty() ? p.kind : p.ruleId.replace('-', '_')) + "_";
        final String b = base;
        while (true) {
            final int k = n;
            if (m.testCases.stream().noneMatch(x -> x.id.equals(b + k))) break;
            n++;
        }
        t.id = base + n;
        t.endpointId = ep.id;
        t.category = category;
        t.title = (p.ruleId.isEmpty() ? "Business rule" : p.ruleId) + ": " + what;
        t.mutation = new Mutation();
        fn.accept(t.mutation);
        t.lifecycle = "DRAFT";
        t.origin = "brd";
        t.basedOn = pos == null ? null : pos.id;
        t.tags.add(tag);
        t.tags.add("brd-rule");
        m.testCases.add(t);
        return 1;
    }

    private static Assertion assertion(String type, String path, JsonNode expected) {
        Assertion a = new Assertion();
        a.type = type;
        a.path = path;
        a.expected = expected;
        return a;
    }

    /** JSON for a proposal (UI). */
    public static ObjectNode toJson(Proposal p) {
        ObjectNode o = F.objectNode();
        o.put("id", p.id);
        o.put("ruleId", p.ruleId);
        o.put("sentence", p.sentence);
        o.put("kind", p.kind);
        o.put("endpointId", p.endpointId);
        o.put("endpointLabel", p.endpointLabel);
        o.put("field", p.field);
        o.put("value", p.value);
        o.put("status", p.status);
        o.put("preview", p.preview);
        p.ambiguities.forEach(o.putArray("ambiguities")::add);
        p.candidates.forEach(o.putArray("candidates")::add);
        return o;
    }
}
