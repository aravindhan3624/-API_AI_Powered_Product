package com.api.product.core.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small, dependency-free JSON Schema checker for response contracts (OpenAPI 3 / Swagger 2 subset): type, nullable, enum, required,
 * properties, additionalProperties=false, items, min/max, length, pattern, item counts, allOf / anyOf / oneOf. Unknown keywords are
 * ignored, so a sloppy spec never produces a false deviation from a keyword it does not understand.
 */
public final class ContractValidator {

    private static final int MAX = 20;

    private ContractValidator() {}

    /** @return human-readable deviations (empty = the response matches the contract) */
    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> out = new ArrayList<>();
        check(schema, value, "$", out, 0);
        return out;
    }

    private static void check(JsonNode s, JsonNode v, String path, List<String> out, int depth) {
        if (s == null || !s.isObject() || out.size() >= MAX || depth > 12) return;
        if (v == null || v.isMissingNode()) return;
        boolean nullable = s.path("nullable").asBoolean(false) || s.path("x-nullable").asBoolean(false);
        if (v.isNull()) {
            if (!nullable && s.has("type") && !typeAllows(s.get("type"), "null")) out.add(path + ": null is not allowed");
            return;
        }
        if (s.has("allOf")) for (JsonNode part : s.get("allOf")) check(part, v, path, out, depth + 1);
        if (s.has("anyOf") && !anyMatches(s.get("anyOf"), v, depth)) out.add(path + ": matches none of the allowed alternatives (anyOf)");
        if (s.has("oneOf") && !anyMatches(s.get("oneOf"), v, depth)) out.add(path + ": matches none of the allowed alternatives (oneOf)");

        String type = s.has("type") && s.get("type").isTextual() ? s.get("type").asText() : s.has("properties") ? "object" : s.has("items") ? "array" : null;
        if (s.has("type") && s.get("type").isArray()) {
            boolean ok = false;
            for (JsonNode t : s.get("type")) ok |= matchesType(t.asText(), v);
            if (!ok) out.add(path + ": expected " + s.get("type") + " but got " + kind(v));
            return;
        }
        if (type != null && !matchesType(type, v)) {
            out.add(path + ": expected " + type + " but got " + kind(v));
            return;
        }
        if (s.has("enum") && s.get("enum").isArray()) {
            boolean in = false;
            for (JsonNode e : s.get("enum")) {
                if (e.equals(v) || (e.isNumber() && v.isNumber() && e.asDouble() == v.asDouble()) || e.asText().equals(v.asText())) in = true;
            }
            if (!in) out.add(path + ": value '" + shorten(v.asText()) + "' is not one of " + s.get("enum"));
        }
        if (v.isObject()) {
            for (JsonNode r : s.path("required")) {
                if (!v.has(r.asText())) out.add(path + "." + r.asText() + ": required field is missing");
            }
            JsonNode props = s.path("properties");
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext() && out.size() < MAX) {
                Map.Entry<String, JsonNode> e = it.next();
                if (props.has(e.getKey())) {
                    check(props.get(e.getKey()), e.getValue(), path + "." + e.getKey(), out, depth + 1);
                } else if (s.has("additionalProperties") && s.get("additionalProperties").isBoolean() && !s.get("additionalProperties").asBoolean()) {
                    out.add(path + "." + e.getKey() + ": field is not declared in the contract");
                } else if (s.has("additionalProperties") && s.get("additionalProperties").isObject()) {
                    check(s.get("additionalProperties"), e.getValue(), path + "." + e.getKey(), out, depth + 1);
                }
            }
        } else if (v.isArray()) {
            if (s.has("minItems") && v.size() < s.get("minItems").asInt()) out.add(path + ": has " + v.size() + " items, minimum is " + s.get("minItems").asInt());
            if (s.has("maxItems") && v.size() > s.get("maxItems").asInt()) out.add(path + ": has " + v.size() + " items, maximum is " + s.get("maxItems").asInt());
            if (s.has("items")) {
                for (int i = 0; i < Math.min(v.size(), 25) && out.size() < MAX; i++) check(s.get("items"), v.get(i), path + "[" + i + "]", out, depth + 1);
            }
        } else if (v.isTextual()) {
            String t = v.asText();
            if (s.has("minLength") && t.length() < s.get("minLength").asInt()) out.add(path + ": shorter than " + s.get("minLength").asInt() + " characters");
            if (s.has("maxLength") && t.length() > s.get("maxLength").asInt()) out.add(path + ": longer than " + s.get("maxLength").asInt() + " characters");
            if (s.has("pattern")) {
                try {
                    if (!Pattern.compile(s.get("pattern").asText()).matcher(t).find()) out.add(path + ": does not match pattern " + s.get("pattern").asText());
                } catch (Exception ignored) {
                    // an invalid pattern in the spec is not a deviation of the response
                }
            }
        } else if (v.isNumber()) {
            if (s.has("minimum") && v.asDouble() < s.get("minimum").asDouble()) out.add(path + ": " + v.asText() + " is below the minimum " + s.get("minimum").asText());
            if (s.has("maximum") && v.asDouble() > s.get("maximum").asDouble()) out.add(path + ": " + v.asText() + " is above the maximum " + s.get("maximum").asText());
        }
    }

    private static boolean anyMatches(JsonNode alts, JsonNode v, int depth) {
        for (JsonNode a : alts) {
            List<String> tmp = new ArrayList<>();
            check(a, v, "$", tmp, depth + 1);
            if (tmp.isEmpty()) return true;
        }
        return alts.size() == 0;
    }

    private static boolean typeAllows(JsonNode t, String name) {
        if (t.isTextual()) return t.asText().equals(name);
        for (JsonNode x : t) if (x.asText().equals(name)) return true;
        return false;
    }

    private static boolean matchesType(String type, JsonNode v) {
        switch (type) {
            case "object": return v.isObject();
            case "array": return v.isArray();
            case "string": return v.isTextual();
            case "boolean": return v.isBoolean();
            case "integer": return v.isIntegralNumber() || (v.isFloatingPointNumber() && v.asDouble() == Math.rint(v.asDouble()));
            case "number": return v.isNumber();
            case "null": return v.isNull();
            default: return true;
        }
    }

    private static String kind(JsonNode v) {
        return v.isObject() ? "object" : v.isArray() ? "array" : v.isTextual() ? "string" : v.isBoolean() ? "boolean" : v.isNumber() ? "number" : "null";
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
