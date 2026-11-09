package com.api.product.studio;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.AuthProfile;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.store.SecretScrubber;
import com.api.product.core.store.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Turns what the user types in the Credentials form into a model that only holds ${ENV:NAME} references, and puts the values in the
 * {@link SecretVault}. Values are never returned, logged or written to the project folder.
 */
final class CredentialSetup {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern REF = Pattern.compile("\\$\\{ENV:([A-Za-z0-9_]+)}");
    private static final String[] TOKEN_PATHS = { "$.token", "$.access_token", "$.accessToken", "$.data.token", "$.data.access_token",
            "$.data.accessToken", "$.result.token", "$.jwt", "$.id_token", "$.authToken" };

    private CredentialSetup() {}

    /** The login-like endpoint, best guess first. */
    static Endpoint guessLogin(ApiModel m) {
        Endpoint best = null;
        int score = 0;
        for (Endpoint e : m.endpoints) {
            String s = ((e.path == null ? "" : e.path) + " " + (e.name == null ? "" : e.name)).toLowerCase(Locale.ROOT);
            int sc = 0;
            if (!s.matches(".*(login|signin|sign-in|authenticate|/auth/token|oauth/token|/token).*")) continue;
            sc += 5;
            if ("POST".equals(e.method)) sc += 2;
            if (s.matches(".*(otp|forgot|reset|change|logout|register).*")) sc -= 6;
            if (e.requestBody != null && e.requestBody.toString().toLowerCase(Locale.ROOT).matches(".*(pass|pwd).*")) sc += 3;
            if (sc > score) {
                score = sc;
                best = e;
            }
        }
        return score >= 5 ? best : null;
    }

    /** Current state without any value: which references the model contains and which of them have a value. */
    static ObjectNode status(String project, ApiModel m) throws Exception {
        ObjectNode n = M.createObjectNode();
        AuthProfile p = m.authProfiles.isEmpty() ? null : m.authProfiles.get(0);
        n.put("type", p == null ? "none" : p.type);
        Endpoint guess = guessLogin(m);
        n.put("suggestedLoginEndpointId", guess == null ? "" : guess.id);
        n.put("loginEndpointId", p == null || p.loginEndpointId == null ? "" : p.loginEndpointId);
        ArrayNode eps = n.putArray("endpoints");
        for (Endpoint e : m.endpoints) {
            if (!"POST".equals(e.method)) continue;
            ObjectNode o = eps.addObject();
            o.put("id", e.id);
            o.put("label", e.method + " " + e.path);
            o.put("looksLikeLogin", guess != null && guess.id.equals(e.id));
            fieldsOf(e, o.putArray("fields"));
        }
        Set<String> refs = new LinkedHashSet<>();
        Matcher mt = REF.matcher(M.writeValueAsString(m));
        while (mt.find()) refs.add(mt.group(1));
        ArrayNode rs = n.putArray("references");
        int missing = 0;
        for (String r : refs) {
            ObjectNode o = rs.addObject();
            o.put("name", r);
            boolean has = SecretVault.has(r);
            o.put("hasValue", has);
            o.put("remembered", SecretVault.isPersisted(r));
            if (!has) missing++;
        }
        n.put("missing", missing);
        // plain (non-secret) sign-in values such as the user name: shown so they can be saved with the password
        ArrayNode pv = n.putArray("plainVars");
        if (!m.environments.isEmpty()) {
            m.environments.get(0).variables.forEach((k, v) -> {
                if (k.matches("(?i)^(login_?id|user_?name|username|user_?id|user)$") && v != null && !v.contains("${ENV:")) {
                    ObjectNode o = pv.addObject();
                    o.put("name", k);
                    o.put("value", v);
                }
            });
        }
        // the project already signs in by itself (its endpoints send ${token} and a login call captures it): do not replace that
        n.put("selfSigned", m.endpoints.stream().anyMatch(e -> e.headers.entrySet().stream().anyMatch(h -> h.getKey().equalsIgnoreCase("Authorization") && h.getValue() != null && h.getValue().contains("${token")))
                && m.endpoints.stream().anyMatch(e -> e.extract.stream().anyMatch(x -> "token".equals(x.var))));
        SecretScrubber.Result plainScan = SecretScrubber.scrub(project, m, false);
        n.put("plain", plainScan.count());
        ArrayNode plainItems = n.putArray("plainItems");
        plainScan.report.stream().limit(5).forEach(plainItems::add);
        return n;
    }

    private static void fieldsOf(Endpoint e, ArrayNode out) {
        if (e.requestBody == null || !e.requestBody.isObject()) return;
        e.requestBody.fields().forEachRemaining(f -> {
            if (!f.getValue().isValueNode()) return;
            ObjectNode o = out.addObject();
            o.put("name", f.getKey());
            String v = f.getValue().asText();
            Matcher r = REF.matcher(v);
            o.put("secret", f.getKey().toLowerCase(Locale.ROOT).matches(".*(pass|pwd|secret|pin|otp).*"));
            o.put("hasValue", r.matches() ? SecretVault.has(r.group(1)) : !v.isBlank() && !v.contains("${"));
        });
    }

    /** Applies the form. Returns a one-line summary (never a value). */
    static String apply(String project, ApiModel m, JsonNode b) {
        String type = b.path("type").asText("none");
        boolean remember = b.path("remember").asBoolean(false);
        // values the project already refers to (for example ${ENV:CKYC_PASSWORD}) are stored first, whatever the sign-in type
        int stored = 0;
        Set<String> known = new LinkedHashSet<>();
        try {
            Matcher km = REF.matcher(M.writeValueAsString(m));
            while (km.find()) known.add(km.group(1));
        } catch (Exception ignored) {
            // no references known: nothing to store
        }
        for (JsonNode pvv : b.path("plain")) {
            String name = pvv.path("name").asText("");
            if (!m.environments.isEmpty() && m.environments.get(0).variables.containsKey(name) && name.matches("(?i)^(login_?id|user_?name|username|user_?id|user)$")
                    && !m.environments.get(0).variables.get(name).contains("${ENV:")) {
                m.environments.get(0).variables.put(name, pvv.path("value").asText(""));
            }
        }
        for (JsonNode sv : b.path("secrets")) {
            String name = sv.path("name").asText("");
            String val = sv.path("value").asText("");
            if (known.contains(name) && !val.isEmpty()) {
                SecretVault.put(name, val, remember);
                stored++;
            }
        }
        if ("none".equals(type) && stored > 0 && m.authProfiles.isEmpty()) return stored + " value(s) stored" + (remember ? " (remembered on this PC)" : " (this session only)");
        m.authProfiles.clear();
        for (Endpoint e : m.endpoints) e.authProfileRef = null;
        if ("none".equals(type)) return "no sign-in needed";
        AuthProfile p = new AuthProfile();
        p.id = "default";
        p.type = type;
        switch (type) {
            case "bearer": {
                p.token = "${ENV:" + store(project, "TOKEN", b.path("token").asText(""), remember) + "}";
                break;
            }
            case "basic": {
                p.username = "${ENV:" + store(project, "USERNAME", b.path("username").asText(""), remember) + "}";
                p.password = "${ENV:" + store(project, "PASSWORD", b.path("password").asText(""), remember) + "}";
                break;
            }
            case "apikey": {
                p.name = b.path("name").asText("X-API-Key");
                p.in = "query".equalsIgnoreCase(b.path("in").asText("header")) ? "query" : "header";
                p.value = "${ENV:" + store(project, "API_KEY", b.path("value").asText(""), remember) + "}";
                break;
            }
            case "login": {
                String id = b.path("loginEndpointId").asText("");
                Endpoint login = m.endpoint(id);
                if (login == null) throw new IllegalArgumentException("Choose which API is the sign-in (login) call");
                p.loginEndpointId = id;
                p.tokenVar = "token";
                ObjectNode body = login.requestBody != null && login.requestBody.isObject() ? ((ObjectNode) login.requestBody).deepCopy() : M.createObjectNode();
                for (JsonNode f : b.path("fields")) {
                    String fname = f.path("name").asText("");
                    String val = f.path("value").asText("");
                    if (fname.isBlank()) continue;
                    if (val.isEmpty()) {
                        JsonNode cur = body.get(fname);
                        if (cur != null && cur.asText("").startsWith("${ENV:")) continue; // keep the existing reference and value
                        throw new IllegalArgumentException("Enter a value for '" + fname + "'");
                    }
                    String vn = store(project, "LOGIN_" + fname, val, remember);
                    body.set(fname, new TextNode("${ENV:" + vn + "}"));
                }
                login.requestBody = body;
                if (login.extract.stream().noneMatch(x -> "token".equals(x.var))) {
                    for (String jp : TOKEN_PATHS) {
                        ApiModel.Extract x = new ApiModel.Extract();
                        x.var = "token";
                        x.jsonPath = jp;
                        login.extract.add(x);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown sign-in type " + type);
        }
        m.authProfiles.add(p);
        for (Endpoint e : m.endpoints) {
            if (p.loginEndpointId != null && p.loginEndpointId.equals(e.id)) continue;
            e.authProfileRef = p.id;
        }
        return "sign-in set to " + type + (remember ? " (remembered on this PC)" : " (this session only)");
    }

    private static String store(String project, String part, String value, boolean remember) {
        String name = SecretVault.envName(project, part);
        if (value == null || value.isEmpty()) {
            if (SecretVault.has(name)) return name;
            throw new IllegalArgumentException("Enter a value for " + part.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        SecretVault.put(name, value, remember);
        return name;
    }

    /** Moves clear-text secrets of the model into the vault. Returns the number moved. */
    static int secure(String project, ApiModel m, boolean remember) {
        SecretScrubber.Result r = SecretScrubber.scrub(project, m, true);
        r.secrets.forEach((k, v) -> SecretVault.put(k, v, remember));
        return r.count();
    }

    /** Names the values of which are still missing (references with no value in memory, vault or environment). */
    static List<String> missingRefs(ApiModel m) throws Exception {
        List<String> out = new ArrayList<>();
        Matcher mt = REF.matcher(M.writeValueAsString(m));
        Set<String> seen = new LinkedHashSet<>();
        while (mt.find()) if (seen.add(mt.group(1)) && !SecretVault.has(mt.group(1))) out.add(mt.group(1));
        return out;
    }
}
