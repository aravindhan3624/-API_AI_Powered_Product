package com.api.product.ai;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.api.product.core.engine.Redactor;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Extra test ideas beyond the standard negative set, from two sources: built-in ideas that use the MEANING of a field (an e-mail
 * without @, a PAN with the wrong pattern, a negative amount ...) and, when a language model is switched on, ideas the model proposes.
 * Model output is never trusted: every idea must name a real top-level field of the request, use a known test category and a simple
 * value, otherwise it is dropped. All ideas are only proposals; accepted ones become DRAFT tests that a person approves.
 */
public final class TestIdeas {

    public static final class Idea {
        public String id;
        public String endpointId;
        public String endpointLabel;
        public String title;
        public String category;
        public String field;
        public JsonNode value;
        public String reason;
        /** built-in | AI */
        public String source = "built-in";
        public boolean duplicate;
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> CATEGORIES = Set.of("WRONG_TYPE", "EMPTY_VALUE", "BOUNDARY", "INVALID_CODE", "SPECIAL_CHARS", "INJECTION", "SPACES_ONLY", "UNICODE");
    private static final int MAX_AI = 8;

    private TestIdeas() {}

    // ------------------------------------------------------------------ built-in (field meaning)

    public static List<Idea> builtIn(ApiModel m, Endpoint ep) {
        List<Idea> out = new ArrayList<>();
        if (ep.requestBody == null || !ep.requestBody.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> it = ep.requestBody.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!f.getValue().isValueNode()) continue;
            String k = f.getKey();
            if (k.matches("(?i).*(pass|pwd|secret|token|otp|pin\\b|cvv|session).*")) continue;
            String sem = meaning(k);
            if (sem == null) continue;
            switch (sem) {
                case "email":
                    add(out, ep, k, "WRONG_TYPE", "E-mail without @", F.textNode("not-an-email"), "An e-mail address must contain @ and a domain");
                    add(out, ep, k, "INVALID_CODE", "E-mail with spaces", F.textNode("a b@example.test"), "Spaces are not allowed in an e-mail address");
                    break;
                case "mobile":
                    add(out, ep, k, "BOUNDARY", "Mobile number too short", F.textNode("98765"), "A mobile number has 10 digits");
                    add(out, ep, k, "BOUNDARY", "Mobile number too long", F.textNode("98765432109876"), "A mobile number has 10 digits");
                    add(out, ep, k, "WRONG_TYPE", "Mobile number with letters", F.textNode("98765abc10"), "Digits only");
                    break;
                case "pan":
                    add(out, ep, k, "INVALID_CODE", "PAN with the wrong pattern", F.textNode("ABCDE12345"), "PAN is 5 letters, 4 digits, 1 letter");
                    add(out, ep, k, "INVALID_CODE", "PAN in lower case", F.textNode("abcde1234f"), "PAN is upper case");
                    break;
                case "pincode":
                    add(out, ep, k, "BOUNDARY", "Pincode with 5 digits", F.textNode("56000"), "A pincode has 6 digits");
                    add(out, ep, k, "WRONG_TYPE", "Pincode with letters", F.textNode("56A001"), "Digits only");
                    break;
                case "ifsc":
                    add(out, ep, k, "INVALID_CODE", "IFSC with the wrong pattern", F.textNode("1234ABCDEFG"), "IFSC is 4 letters, 0, 6 characters");
                    break;
                case "aadhaar":
                    add(out, ep, k, "BOUNDARY", "Aadhaar with 11 digits", F.textNode("23456789012"), "12 digits required");
                    add(out, ep, k, "WRONG_TYPE", "Aadhaar with letters", F.textNode("2345678901AB"), "Digits only");
                    break;
                case "amount":
                    add(out, ep, k, "BOUNDARY", "Negative amount", F.numberNode(-1), "An amount cannot be negative");
                    add(out, ep, k, "BOUNDARY", "Zero amount", F.numberNode(0), "A payment of zero makes no sense");
                    add(out, ep, k, "BOUNDARY", "Huge amount", F.numberNode(99999999999L), "Very large amounts must be limited");
                    add(out, ep, k, "WRONG_TYPE", "Amount as text", F.textNode("ten rupees"), "A number is required");
                    break;
                case "count":
                    add(out, ep, k, "BOUNDARY", "Quantity zero", F.numberNode(0), "At least one is required");
                    add(out, ep, k, "BOUNDARY", "Negative quantity", F.numberNode(-1), "Cannot be negative");
                    add(out, ep, k, "BOUNDARY", "Quantity above int range", F.numberNode(2147483648L), "Overflow of a 32-bit number");
                    break;
                case "age":
                    add(out, ep, k, "BOUNDARY", "Negative age", F.numberNode(-1), "Age cannot be negative");
                    add(out, ep, k, "BOUNDARY", "Age 200", F.numberNode(200), "Unrealistic age");
                    break;
                case "name":
                    add(out, ep, k, "WRONG_TYPE", "Name made of digits", F.textNode("12345"), "Names contain letters");
                    add(out, ep, k, "SPECIAL_CHARS", "Name with symbols only", F.textNode("@@@###"), "Symbols are not a name");
                    break;
                case "url":
                    add(out, ep, k, "INVALID_CODE", "Not a URL", F.textNode("not a url"), "A URL is required");
                    add(out, ep, k, "INJECTION", "javascript: URL", F.textNode("javascript:alert(1)"), "Dangerous URL schemes must be refused");
                    break;
                case "uuid":
                    add(out, ep, k, "INVALID_CODE", "Not a UUID", F.textNode("not-a-uuid"), "A UUID is required");
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static String meaning(String field) {
        String f = field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (f.matches(".*e_?mail.*")) return "email";
        if (f.matches(".*(mobile|phone|msisdn|contact_?no).*")) return "mobile";
        if (f.matches(".*(^|_)pan(_|$).*")) return "pan";
        if (f.matches(".*(pin_?code|zip|postal).*")) return "pincode";
        if (f.matches(".*ifsc.*")) return "ifsc";
        if (f.matches(".*(aadhaar|aadhar).*")) return "aadhaar";
        if (f.matches(".*(amount|price|balance|total|salary|income|fee).*")) return "amount";
        if (f.matches(".*(quantity|qty|count).*")) return "count";
        if (f.matches(".*(^|_)age(_|$).*")) return "age";
        if (f.matches(".*(first_?name|last_?name|surname|full_?name|customer_?name|holder_?name|^name$).*")) return "name";
        if (f.matches(".*(url|link|website).*")) return "url";
        if (f.matches(".*(uuid|guid).*")) return "uuid";
        return null;
    }

    private static void add(List<Idea> out, Endpoint ep, String field, String category, String title, JsonNode value, String reason) {
        Idea i = new Idea();
        i.endpointId = ep.id;
        i.endpointLabel = ep.method + " " + ep.path;
        i.field = field;
        i.category = category;
        i.title = title + " (" + field + ")";
        i.value = value;
        i.reason = reason;
        out.add(i);
    }

    // ------------------------------------------------------------------ language model

    public static final class AiResult {
        public final List<Idea> ideas = new ArrayList<>();
        public boolean used;
        public String message = "";
    }

    /** Asks the model for ideas for one endpoint. Never throws: problems end up in {@code message}. */
    public static AiResult viaAi(ApiModel m, Endpoint ep, String businessText, OllamaClient llm) {
        AiResult r = new AiResult();
        if (llm == null || !llm.switchedOn()) {
            r.message = "The AI engine is off (left menu, AI engine). Built-in ideas are shown.";
            return r;
        }
        if (!llm.available()) {
            r.message = "The AI engine is switched on but no model answers at " + (llm.settings() == null ? "" : llm.settings().url) + ". Built-in ideas are shown.";
            return r;
        }
        if (ep.requestBody == null || !ep.requestBody.isObject()) {
            r.message = "This API has no JSON request body to vary.";
            return r;
        }
        Set<String> fields = new LinkedHashSet<>();
        ep.requestBody.fieldNames().forEachRemaining(fields::add);
        String body = Redactor.mask(ep.requestBody.toString());
        String system = "You design API tests. Answer with JSON only: {\"ideas\":[{\"field\":\"<one of the listed fields>\",\"title\":\"<short>\",\"category\":\"<one of the listed categories>\",\"value\":<string|number|boolean>,\"reason\":\"<one sentence>\"}]}. "
                + "Each idea changes exactly one field of a valid request to one value that a correct API must reject. No other text.";
        String user = "API: " + ep.method + " " + ep.path + " (" + (ep.name == null ? "" : ep.name) + ")\nValid request body: " + trim(body, 1200) + "\nFields: " + String.join(", ", fields)
                + "\nCategories: " + String.join(", ", CATEGORIES) + "\nAt most " + MAX_AI + " ideas, realistic for this business domain."
                + (businessText == null || businessText.isBlank() ? "" : "\nBusiness notes: " + trim(businessText, 800));
        String out = llm.chatJson(system, user, llm.settings() == null ? 180 : llm.settings().timeoutSeconds, 700);
        if (out == null) {
            r.message = "The model did not answer in time. Built-in ideas are shown.";
            return r;
        }
        r.used = true;
        try {
            JsonNode arr = M.readTree(out).path("ideas");
            int dropped = 0;
            for (JsonNode n : arr) {
                if (r.ideas.size() >= MAX_AI) break;
                String f = n.path("field").asText("");
                String cat = n.path("category").asText("").toUpperCase(Locale.ROOT);
                JsonNode v = n.get("value");
                if (!fields.contains(f) || !CATEGORIES.contains(cat) || v == null || !v.isValueNode() || n.path("title").asText("").isBlank()) {
                    dropped++;
                    continue;
                }
                if (v.isTextual() && v.asText().length() > 300) {
                    dropped++;
                    continue;
                }
                Idea i = new Idea();
                i.endpointId = ep.id;
                i.endpointLabel = ep.method + " " + ep.path;
                i.field = f;
                i.category = cat;
                i.title = n.path("title").asText().trim() + " (" + f + ")";
                i.value = v;
                i.reason = n.path("reason").asText("").trim();
                i.source = "AI (" + llm.model() + ")";
                r.ideas.add(i);
            }
            r.message = r.ideas.size() + " idea(s) from " + llm.model() + (dropped > 0 ? "; " + dropped + " dropped because they named an unknown field or an unsupported change" : "") + ".";
        } catch (Exception e) {
            r.message = "The model answered, but not in the expected format, so nothing was taken from it.";
        }
        return r;
    }

    // ------------------------------------------------------------------ to tests

    /** Marks ideas that an existing test already covers (same API, field and value). */
    public static void markDuplicates(ApiModel m, List<Idea> ideas) {
        Set<String> seen = new LinkedHashSet<>();
        for (TestCase t : m.testCases) {
            if (t.mutation == null) continue;
            t.mutation.setBodyFields.forEach((k, v) -> seen.add(t.endpointId + "|" + k + "|" + v));
        }
        for (Idea i : ideas) {
            String key = i.endpointId + "|" + i.field + "|" + i.value;
            i.duplicate = !seen.add(key);
        }
    }

    public static TestCase toTest(ApiModel m, Idea i) {
        TestCase base = null;
        for (TestCase t : m.testCases) if (i.endpointId.equals(t.endpointId) && "POSITIVE".equals(t.category)) base = t;
        TestCase t = new TestCase();
        int n = 1;
        String prefix = "TC_IDEA_" + i.endpointId + "_" + i.field.replaceAll("[^A-Za-z0-9]", "") + "_";
        final String pf = prefix;
        while (true) {
            final int k = n;
            if (m.testCases.stream().noneMatch(x -> x.id.equals(pf + k))) break;
            n++;
        }
        t.id = prefix + n;
        t.endpointId = i.endpointId;
        t.category = i.category;
        t.title = i.title;
        t.mutation = new Mutation();
        t.mutation.setBodyFields.put(i.field, i.value);
        t.lifecycle = "DRAFT";
        t.origin = i.source.startsWith("AI") ? "ai" : "idea";
        t.basedOn = base == null ? null : base.id;
        t.tags.add(i.source.startsWith("AI") ? "ai-proposed" : "smart-idea");
        if (i.reason != null && !i.reason.isBlank()) t.tags.add("why: " + i.reason);
        return t;
    }

    private static String trim(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    /** For tests: a compact view. */
    static ObjectNode toJson(Idea i) {
        ObjectNode o = M.createObjectNode();
        o.put("title", i.title);
        o.put("category", i.category);
        o.put("field", i.field);
        o.set("value", i.value);
        return o;
    }

    static final Pattern UNUSED = Pattern.compile("x");
}
