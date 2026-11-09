package com.api.product.core.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Extract;
import com.api.product.core.model.ApiModel.Link;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Finds data dependencies between APIs (an id created by one call and used by another), turns them into reviewable {@link Link}s and
 * applies them: the producer extracts the value, the consumer uses ${variable}. It also gives the run order: setup calls first, normal
 * tests, then cleanup (DELETE of what was created). Detection never changes anything; the user approves links first.
 */
public final class AutoChain {

    private static final Pattern PH = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final Pattern ID_FIELD = Pattern.compile("(?i)^(.*[a-z])?_?id$");
    private static final Set<String> RESERVED = Set.of("OTP", "token", "apiKey", "TXN_ID", "DATE", "PAD");
    private static final String[] GUESS = { "$.%s", "$.id", "$.data.%s", "$.data.id", "$.result.%s", "$.result.id", "$.%sId", "$.data.%sId" };

    private AutoChain() {}

    // ------------------------------------------------------------------ detection

    /** Proposals that are not yet links. Nothing is modified. */
    public static List<Link> detect(ApiModel m) {
        List<Link> out = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (Link l : m.links) have.add(key(l.toEndpointId, l.where, l.name));
        Set<String> produced = new HashSet<>();
        for (Endpoint e : m.endpoints) for (Extract x : e.extract) produced.add(x.var);
        for (Endpoint e : m.endpoints) e.set.keySet().forEach(produced::add);
        Map<String, String> envVars = m.environments.isEmpty() ? new HashMap<>() : m.environments.get(0).variables;
        int n = 0;
        for (Endpoint c : m.endpoints) {
            if (looksLikeLogin(c)) continue;
            for (Need need : needs(c, envVars, produced)) {
                if (have.contains(key(c.id, need.where, need.name))) continue;
                Found f = findProducer(m, c, need.var);
                if (f == null) continue;
                Link l = new Link();
                l.id = "L" + (m.links.size() + (++n));
                l.var = need.var;
                l.fromEndpointId = f.producer.id;
                l.jsonPath = f.jsonPath;
                l.toEndpointId = c.id;
                l.where = need.where;
                l.name = need.name;
                l.origin = "auto";
                l.confidence = f.confirmed ? "high" : "medium";
                l.enabled = true;
                out.add(l);
            }
        }
        return out;
    }

    private static final class Need {
        String where, name, var;
    }

    private static final class Found {
        Endpoint producer;
        String jsonPath;
        boolean confirmed;
    }

    private static List<Need> needs(Endpoint c, Map<String, String> envVars, Set<String> produced) {
        List<Need> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // (a) path parameters declared in the specification
        if (c.specKey != null) {
            Matcher pm = PATH_PARAM.matcher(c.specKey.replaceFirst("^\\S+ ", ""));
            while (pm.find()) add(out, seen, "path", pm.group(1), pm.group(1));
        }
        // (b) variables used but never defined or produced
        List<String[]> places = new ArrayList<>();
        places.add(new String[] { "path", c.path });
        c.query.forEach((k, v) -> places.add(new String[] { "query:" + k, v }));
        c.headers.forEach((k, v) -> places.add(new String[] { "header:" + k, v }));
        for (String[] pl : places) {
            Matcher m = PH.matcher(pl[1] == null ? "" : pl[1]);
            while (m.find()) {
                String var = m.group(1);
                if (RESERVED.contains(var) || produced.contains(var) || (envVars.get(var) != null && !envVars.get(var).isBlank())) continue;
                add(out, seen, pl[0].startsWith("path") ? "path" : pl[0].startsWith("query") ? "query" : "header",
                        pl[0].contains(":") ? pl[0].substring(pl[0].indexOf(':') + 1) : var, var);
            }
        }
        if (c.requestBody != null) bodyNeeds(c.requestBody, out, seen, envVars, produced, 0);
        return out;
    }

    private static void bodyNeeds(JsonNode n, List<Need> out, Set<String> seen, Map<String, String> envVars, Set<String> produced, int depth) {
        if (n == null || depth > 4) return;
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual() && PH.matcher(v.asText()).matches()) {
                    String var = v.asText().substring(2, v.asText().length() - 1);
                    if (!RESERVED.contains(var) && !produced.contains(var) && (envVars.get(var) == null || envVars.get(var).isBlank()))
                        add(out, seen, "body", e.getKey(), var);
                } else if (v.isValueNode() && !v.isNull() && !e.getKey().equalsIgnoreCase("id") && ID_FIELD.matcher(e.getKey()).matches()
                        && !(v.isTextual() && v.asText().contains("${"))) {
                    add(out, seen, "body", e.getKey(), e.getKey());
                } else if (v.isContainerNode()) {
                    bodyNeeds(v, out, seen, envVars, produced, depth + 1);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode x : n) bodyNeeds(x, out, seen, envVars, produced, depth + 1);
        }
    }

    private static void add(List<Need> out, Set<String> seen, String where, String name, String var) {
        if (!seen.add(where + ":" + name)) return;
        Need n = new Need();
        n.where = where;
        n.name = name;
        n.var = var;
        out.add(n);
    }

    private static Found findProducer(ApiModel m, Endpoint consumer, String var) {
        String noun = var.replaceAll("(?i)(_?id|ref|number|no)$", "").toLowerCase(Locale.ROOT);
        if (noun.isEmpty()) noun = lastStaticSegment(consumer);
        if (noun.isEmpty()) return null;
        String singular = noun.endsWith("s") && noun.length() > 3 ? noun.substring(0, noun.length() - 1) : noun;
        Found best = null;
        int bestScore = 0;
        for (Endpoint p : m.endpoints) {
            if (p == consumer || looksLikeLogin(p)) continue;
            if (!(p.method.equals("POST") || p.method.equals("PUT") || p.method.equals("PATCH"))) continue;
            String tpl = (p.specKey != null ? p.specKey.replaceFirst("^\\S+ ", "") : String.valueOf(p.path)).toLowerCase(Locale.ROOT);
            if (tpl.contains("{") || tpl.contains("${")) continue;
            String[] seg = tpl.split("/");
            boolean related = false;
            for (String s : seg) if (s.equals(noun) || s.equals(singular) || s.equals(singular + "s") || s.startsWith(singular)) related = true;
            String nm = (p.name == null ? "" : p.name).toLowerCase(Locale.ROOT);
            if (!related && !nm.matches("^(create|add|insert|new|save|register|place)\b.*\b" + Pattern.quote(singular) + ".*")) continue;
            int score = 1;
            if (p.method.equals("POST")) score += 3;
            if (seg.length > 0 && (seg[seg.length - 1].equals(singular) || seg[seg.length - 1].equals(singular + "s"))) score += 3;
            if (nm.matches(".*(create|add|insert|new|save|register|place).*")) score += 2;
            if (tpl.matches(".*(search|find|list|login|logout|upload|update).*")) score -= 2;
            String jp = null;
            boolean confirmed = false;
            if (p.responseSchema != null) {
                jp = idPath(p.responseSchema, var, singular);
                if (jp == null) continue;
                confirmed = true;
                score += 4;
            } else {
                Set<String> g = new java.util.LinkedHashSet<>();
                for (String pat : GUESS) g.add(pat.contains("Id") ? pat.replace("%s", singular) : pat.replace("%s", var));
                jp = String.join("|", g);
            }
            if (score > bestScore) {
                bestScore = score;
                best = new Found();
                best.producer = p;
                best.jsonPath = jp;
                best.confirmed = confirmed;
            }
        }
        return best;
    }

    private static String lastStaticSegment(Endpoint c) {
        String tpl = c.specKey != null ? c.specKey.replaceFirst("^\\S+ ", "") : String.valueOf(c.path);
        String last = "";
        for (String s : tpl.split("/")) if (!s.isEmpty() && !s.startsWith("{") && !s.contains("${")) last = s;
        return last.toLowerCase(Locale.ROOT);
    }

    /** JSON path of an id-like property in a response schema (var name, then <noun>Id, then id), or null. */
    static String idPath(JsonNode schema, String var, String noun) {
        List<String[]> found = new ArrayList<>();
        walk(schema, "$", 0, found);
        String[] wanted = { var.toLowerCase(Locale.ROOT), (noun + "id"), (noun + "_id"), "id" };
        for (String w : wanted) {
            for (String[] f : found) if (f[1].toLowerCase(Locale.ROOT).equals(w)) return f[0];
        }
        return null;
    }

    private static void walk(JsonNode s, String path, int depth, List<String[]> out) {
        if (s == null || !s.isObject() || depth > 3) return;
        if (s.has("allOf")) for (JsonNode p : s.get("allOf")) walk(p, path, depth, out);
        JsonNode props = s.path("properties");
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String t = e.getValue().path("type").asText("");
            if (t.equals("integer") || t.equals("string") || t.isEmpty() && !e.getValue().has("properties")) out.add(new String[] { path + "." + e.getKey(), e.getKey() });
            if (e.getValue().has("properties") || "object".equals(t)) walk(e.getValue(), path + "." + e.getKey(), depth + 1, out);
        }
    }

    static boolean looksLikeLogin(Endpoint e) {
        String s = ((e.path == null ? "" : e.path) + " " + (e.name == null ? "" : e.name)).toLowerCase(Locale.ROOT);
        return s.matches(".*(login|signin|sign-in|authenticate|oauth/token|logout|otp).*");
    }

    private static String key(String endpoint, String where, String name) {
        return endpoint + "|" + where + "|" + name;
    }

    // ------------------------------------------------------------------ applying

    /** Writes every enabled link into the endpoints (extract on the producer, ${var} on the consumer). Returns the number of edits. */
    public static int apply(ApiModel m) {
        int edits = 0;
        Map<String, String> env = m.environments.isEmpty() ? new LinkedHashMap<>() : m.environments.get(0).variables;
        for (Link l : m.links) {
            if (!l.enabled) continue;
            Endpoint p = m.endpoint(l.fromEndpointId), c = m.endpoint(l.toEndpointId);
            if (p == null || c == null) continue;
            for (String jp : l.jsonPath.split("\\|")) {
                boolean has = false;
                for (Extract x : p.extract) if (x.var.equals(l.var) && x.jsonPath.equals(jp)) has = true;
                if (!has) {
                    Extract x = new Extract();
                    x.var = l.var;
                    x.jsonPath = jp;
                    p.extract.add(x);
                    edits++;
                }
            }
            edits += substitute(c, l, env);
        }
        return edits;
    }

    private static int substitute(Endpoint c, Link l, Map<String, String> env) {
        String ph = "${" + l.var + "}";
        switch (l.where) {
            case "path": {
                if (c.path != null && c.path.contains(ph)) return 0;
                if (c.specKey == null) return 0;
                String tpl = c.specKey.replaceFirst("^\\S+ ", "");
                String[] t = tpl.split("/", -1), a = c.path.split("/", -1);
                if (t.length != a.length) return 0;
                for (int i = 0; i < t.length; i++) {
                    if (t[i].equals("{" + l.name + "}")) {
                        env.putIfAbsent(l.var, a[i]);
                        a[i] = ph;
                        c.path = String.join("/", a);
                        return 1;
                    }
                }
                return 0;
            }
            case "query": {
                String v = c.query.get(l.name);
                if (v == null || v.contains(ph)) return 0;
                env.putIfAbsent(l.var, v);
                c.query.put(l.name, ph);
                return 1;
            }
            case "header": {
                String v = c.headers.get(l.name);
                if (v == null || v.contains(ph)) return 0;
                env.putIfAbsent(l.var, v);
                c.headers.put(l.name, ph);
                return 1;
            }
            case "body":
                return c.requestBody == null ? 0 : replaceField(c.requestBody, l.name, ph, l.var, env, 0);
            default:
                return 0;
        }
    }

    private static int replaceField(JsonNode n, String name, String ph, String var, Map<String, String> env, int depth) {
        if (n == null || depth > 5) return 0;
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            if (o.has(name) && o.get(name).isValueNode() && !(o.get(name).isTextual() && o.get(name).asText().contains("${"))) {
                env.putIfAbsent(var, o.get(name).asText());
                o.set(name, new TextNode(ph));
                return 1;
            }
            int c = 0;
            Iterator<JsonNode> it = o.elements();
            while (it.hasNext() && c == 0) c += replaceField(it.next(), name, ph, var, env, depth + 1);
            return c;
        }
        if (n.isArray()) {
            for (JsonNode x : (ArrayNode) n) {
                int c = replaceField(x, name, ph, var, env, depth + 1);
                if (c > 0) return c;
            }
        }
        return 0;
    }

    /** Removes a link and puts the consumer's original value back (the default kept in the environment variables). */
    public static void remove(ApiModel m, String linkId) {
        Link l = null;
        for (Link x : m.links) if (x.id.equals(linkId)) l = x;
        if (l == null) return;
        m.links.remove(l);
        final Link removed = l;
        Map<String, String> env = m.environments.isEmpty() ? new LinkedHashMap<>() : m.environments.get(0).variables;
        Endpoint c = m.endpoint(l.toEndpointId), p = m.endpoint(l.fromEndpointId);
        String orig = env.get(l.var);
        String ph = "${" + l.var + "}";
        if (c != null && orig != null) {
            switch (l.where) {
                case "path": c.path = c.path.replace(ph, orig); break;
                case "query": c.query.replace(l.name, orig); break;
                case "header": c.headers.replace(l.name, orig); break;
                case "body": if (c.requestBody != null) restoreField(c.requestBody, l.name, orig, 0); break;
                default: break;
            }
        }
        boolean stillUsed = false;
        for (Link x : m.links) if (x.var.equals(l.var) && x.fromEndpointId.equals(l.fromEndpointId)) stillUsed = true;
        if (p != null && !stillUsed) p.extract.removeIf(x -> x.var.equals(removed.var));
    }

    private static void restoreField(JsonNode n, String name, String orig, int depth) {
        if (n == null || depth > 5) return;
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            if (o.has(name) && o.get(name).isTextual() && o.get(name).asText().startsWith("${")) {
                o.set(name, new TextNode(orig));
                return;
            }
            o.elements().forEachRemaining(x -> restoreField(x, name, orig, depth + 1));
        } else if (n.isArray()) {
            n.forEach(x -> restoreField(x, name, orig, depth + 1));
        }
    }

    // ------------------------------------------------------------------ run order

    /** 0..n = setup (producers, in dependency order), 100 = normal tests, 200 = cleanup (DELETE of created things). */
    public static int rank(ApiModel m, TestCase t) {
        if (m.links.isEmpty() || t.endpointId == null) return 100;
        Endpoint e = m.endpoint(t.endpointId);
        if (e == null) return 100;
        if ("POSITIVE".equals(t.category) && isProducer(m, e.id)) return depth(m, e.id, new HashSet<>());
        if ("DELETE".equals(e.method) && isConsumer(m, e.id)) return 200;
        return 100;
    }

    private static boolean isProducer(ApiModel m, String id) {
        for (Link l : m.links) if (l.enabled && id.equals(l.fromEndpointId)) return true;
        return false;
    }

    private static boolean isConsumer(ApiModel m, String id) {
        for (Link l : m.links) if (l.enabled && id.equals(l.toEndpointId)) return true;
        return false;
    }

    private static int depth(ApiModel m, String id, Set<String> path) {
        if (!path.add(id)) return 0;
        int d = 0;
        for (Link l : m.links) if (l.enabled && id.equals(l.toEndpointId)) d = Math.max(d, 1 + depth(m, l.fromEndpointId, path));
        path.remove(id);
        return Math.min(d, 90);
    }

    /** Endpoints in the order a run will call their valid tests: setup, normal, cleanup. For the flow view. */
    public static List<Endpoint> order(ApiModel m) {
        List<Endpoint> list = new ArrayList<>(m.endpoints);
        Map<String, Integer> r = new HashMap<>();
        for (Endpoint e : list) {
            TestCase t = new TestCase();
            t.endpointId = e.id;
            t.category = "POSITIVE";
            r.put(e.id, rank(m, t));
        }
        list.sort((a, b) -> Integer.compare(r.get(a.id), r.get(b.id)));
        return list;
    }
}
