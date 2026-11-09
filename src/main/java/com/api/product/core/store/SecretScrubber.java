package com.api.product.core.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Finds secrets that an imported collection or spec carries in clear text (variables, request-body fields, headers) and replaces
 * them by ${ENV:NAME} references. The values are returned so the caller can place them in the {@link SecretVault}; nothing secret
 * stays in the model file. Placeholders (${...}) and empty values are left alone.
 */
public final class SecretScrubber {

        private static final Pattern PLACEHOLDER = Pattern.compile(".*\\$\\{.*}.*");

    /** Matches secret-like field names by whole words (apiName and pinCode are not secrets; passWord, mpin, otp, clientSecret are). */
    private static final class SecretName {
        static final Pattern LONG = Pattern.compile("(?i).*(password|passwd|passphrase|secret|token|apikey|api_key|credential|authorization|authkey|auth_key|privatekey).*");
        static final java.util.Set<String> WORDS = java.util.Set.of("pass", "pwd", "otp", "pin", "mpin", "tpin", "cvv");
    }

    static boolean secretName(String key) {
        if (key == null) return false;
        if (SecretName.LONG.matcher(key).matches()) return true;
        String lower = key.toLowerCase();
        if (lower.contains("pincode") || lower.contains("pin_code") || lower.contains("pinned")) return false;
        for (String w : key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase().split("[^a-z0-9]+")) if (SecretName.WORDS.contains(w)) return true;
        return false;
    }

    public static final class Result {
        public final Map<String, String> secrets = new LinkedHashMap<>();
        public final List<String> report = new ArrayList<>();
        public int count() {
            return secrets.size();
        }
    }

    private SecretScrubber() {}

    /** @param apply false = only count what would be moved (nothing is changed) */
    public static Result scrub(String project, ApiModel m, boolean apply) {
        Result res = new Result();
        // 1) environment variables
        for (ApiModel.Environment env : m.environments) {
            for (Map.Entry<String, String> e : new ArrayList<>(env.variables.entrySet())) {
                String v = e.getValue();
                if (v == null || v.isBlank() || PLACEHOLDER.matcher(v).matches() || !secretName(e.getKey())) continue;
                String name = SecretVault.envName(project, e.getKey());
                res.secrets.put(name, v);
                res.report.add("variable '" + e.getKey() + "' -> ${ENV:" + name + "}");
                if (apply) env.variables.put(e.getKey(), "${ENV:" + name + "}");
            }
        }
        // 2) request bodies: collect values per field name so identical values share one vault entry
        Map<String, Map<String, Integer>> seen = new HashMap<>();
        for (Endpoint ep : m.endpoints) collect(ep.requestBody, seen);
        for (Endpoint ep : m.endpoints) {
            if (ep.requestBody != null) {
                JsonNode nb = rewrite(project, ep, ep.requestBody, seen, res, apply);
                if (apply && nb != null) ep.requestBody = nb;
            }
            // 3) headers and query (also for endpoints without a body)
            for (Map.Entry<String, String> h : new ArrayList<>(ep.headers.entrySet())) scrubKv(project, ep, h, ep.headers, res, apply, true);
            for (Map.Entry<String, String> q : new ArrayList<>(ep.query.entrySet())) scrubKv(project, ep, q, ep.query, res, apply, false);
        }
        return res;
    }

    private static void scrubKv(String project, Endpoint ep, Map.Entry<String, String> kv, Map<String, String> target, Result res, boolean apply, boolean header) {
        String k = kv.getKey(), v = kv.getValue();
        if (v == null || v.isBlank() || PLACEHOLDER.matcher(v).matches() || !secretName(k)) return;
        String raw = v;
        String prefix = "";
        Matcher b = Pattern.compile("(?i)^(Bearer|Basic)\\s+(.+)$").matcher(v);
        if (header && b.matches()) {
            prefix = b.group(1) + " ";
            raw = b.group(2);
        }
        String name = SecretVault.envName(project, ep.id + "_" + k);
        res.secrets.put(name, raw);
        res.report.add((header ? "header '" : "query '") + k + "' of " + ep.id + " -> ${ENV:" + name + "}");
        if (apply) target.put(k, prefix + "${ENV:" + name + "}");
    }

    private static void collect(JsonNode n, Map<String, Map<String, Integer>> seen) {
        if (n == null) return;
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual() && secretName(e.getKey()) && !v.asText().isBlank() && !PLACEHOLDER.matcher(v.asText()).matches()) {
                    seen.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(v.asText(), 1, Integer::sum);
                }
                collect(v, seen);
            }
        } else if (n.isArray()) {
            for (JsonNode x : n) collect(x, seen);
        }
    }

    private static JsonNode rewrite(String project, Endpoint ep, JsonNode n, Map<String, Map<String, Integer>> seen, Result res, boolean apply) {
        if (n == null) return null;
        JsonNode copy = apply ? n.deepCopy() : n;
        rewriteInPlace(project, ep, copy, seen, res, apply);
        return copy;
    }

    private static void rewriteInPlace(String project, Endpoint ep, JsonNode n, Map<String, Map<String, Integer>> seen, Result res, boolean apply) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String k : names) {
                JsonNode v = o.get(k);
                if (v.isTextual() && secretName(k) && !v.asText().isBlank() && !PLACEHOLDER.matcher(v.asText()).matches()) {
                    boolean sameEverywhere = seen.getOrDefault(k, Map.of()).size() <= 1;
                    String name = SecretVault.envName(project, sameEverywhere ? k : ep.id + "_" + k);
                    res.secrets.put(name, v.asText());
                    res.report.add("field '" + k + "' of " + ep.id + " -> ${ENV:" + name + "}");
                    if (apply) o.set(k, new TextNode("${ENV:" + name + "}"));
                } else {
                    rewriteInPlace(project, ep, v, seen, res, apply);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode x : (ArrayNode) n) rewriteInPlace(project, ep, x, seen, res, apply);
        }
    }
}
