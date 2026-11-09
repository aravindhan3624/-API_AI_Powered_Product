package com.api.product.core.importer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Proposes repairs when a newer specification changed an API: fields that were added or removed in the request body, fields whose type
 * changed, new required query parameters or headers. Nothing is applied automatically; each repair is shown with before and after and
 * applied only when approved. Values you entered for fields that still exist are never overwritten.
 */
public final class SpecHeal {

    public static final class Repair {
        public String id;
        public String endpointId;
        public String api;
        /** ADD_FIELD | REMOVE_FIELD | CHANGE_TYPE | ADD_QUERY | ADD_HEADER */
        public String kind;
        public String field;
        public String before = "";
        public String after = "";
        public JsonNode value;
    }

    private SpecHeal() {}

    public static List<Repair> propose(ApiModel current, ApiModel latest) {
        List<Repair> out = new ArrayList<>();
        int n = 0;
        for (Endpoint cur : current.endpoints) {
            Endpoint neu = latest.endpoint(cur.id);
            if (neu == null || cur.specHash == null || cur.specHash.equals(neu.specHash)) continue;
            String api = cur.method + " " + cur.path;
            diffBody(cur.requestBody, neu.requestBody, "", cur.id, api, out);
            for (Map.Entry<String, String> q : neu.query.entrySet()) {
                if (!cur.query.containsKey(q.getKey())) out.add(repair(cur, api, "ADD_QUERY", q.getKey(), "", q.getValue(), null));
            }
            for (Map.Entry<String, String> h : neu.headers.entrySet()) {
                if (cur.headers.keySet().stream().noneMatch(x -> x.equalsIgnoreCase(h.getKey()))) out.add(repair(cur, api, "ADD_HEADER", h.getKey(), "", h.getValue(), null));
            }
        }
        for (Repair r : out) r.id = "H" + (++n);
        return out;
    }

    private static void diffBody(JsonNode cur, JsonNode neu, String prefix, String id, String api, List<Repair> out) {
        if (cur == null || neu == null || !cur.isObject() || !neu.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = neu.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            String path = prefix.isEmpty() ? f.getKey() : prefix + "." + f.getKey();
            JsonNode c = cur.get(f.getKey());
            if (c == null) {
                out.add(repair(id, api, "ADD_FIELD", path, "(missing)", f.getValue().toString(), f.getValue()));
            } else if (c.isObject() && f.getValue().isObject()) {
                diffBody(c, f.getValue(), path, id, api, out);
            } else if (c.getNodeType() != f.getValue().getNodeType() && !(c.isNumber() && f.getValue().isNumber()) && !c.isNull() && !f.getValue().isNull()) {
                out.add(repair(id, api, "CHANGE_TYPE", path, c.toString(), f.getValue().toString(), f.getValue()));
            }
        }
        Iterator<String> names = cur.fieldNames();
        while (names.hasNext()) {
            String k = names.next();
            if (!neu.has(k)) out.add(repair(id, api, "REMOVE_FIELD", prefix.isEmpty() ? k : prefix + "." + k, cur.get(k).toString(), "(removed)", null));
        }
    }

    private static Repair repair(Endpoint e, String api, String kind, String field, String before, String after, JsonNode v) {
        return repair(e.id, api, kind, field, before, after, v);
    }

    private static Repair repair(String endpointId, String api, String kind, String field, String before, String after, JsonNode v) {
        Repair r = new Repair();
        r.endpointId = endpointId;
        r.api = api;
        r.kind = kind;
        r.field = field;
        r.before = before;
        r.after = after;
        r.value = v;
        return r;
    }

    /** Applies the approved repairs (by id) to {@code current}. Returns how many were applied. */
    public static int apply(ApiModel current, List<Repair> proposed, Set<String> approved) {
        int done = 0;
        for (Repair r : proposed) {
            if (!approved.contains(r.id)) continue;
            Endpoint e = current.endpoint(r.endpointId);
            if (e == null) continue;
            switch (r.kind) {
                case "ADD_FIELD":
                case "CHANGE_TYPE":
                    if (e.requestBody != null && e.requestBody.isObject() && set((ObjectNode) e.requestBody, r.field.split("\\."), 0, r.value)) done++;
                    break;
                case "REMOVE_FIELD":
                    if (e.requestBody != null && e.requestBody.isObject() && remove((ObjectNode) e.requestBody, r.field.split("\\."), 0)) done++;
                    break;
                case "ADD_QUERY":
                    e.query.put(r.field, r.after);
                    done++;
                    break;
                case "ADD_HEADER":
                    e.headers.put(r.field, r.after);
                    done++;
                    break;
                default:
                    break;
            }
        }
        return done;
    }

    private static boolean set(ObjectNode o, String[] path, int i, JsonNode v) {
        if (i == path.length - 1) {
            o.set(path[i], v.deepCopy());
            return true;
        }
        JsonNode next = o.get(path[i]);
        return next != null && next.isObject() && set((ObjectNode) next, path, i + 1, v);
    }

    private static boolean remove(ObjectNode o, String[] path, int i) {
        if (i == path.length - 1) return o.remove(path[i]) != null;
        JsonNode next = o.get(path[i]);
        return next != null && next.isObject() && remove((ObjectNode) next, path, i + 1);
    }

    /** JSON for the UI. */
    public static ArrayNode toJson(List<Repair> list, com.fasterxml.jackson.databind.ObjectMapper m) {
        ArrayNode a = m.createArrayNode();
        for (Repair r : list) {
            ObjectNode o = a.addObject();
            o.put("id", r.id);
            o.put("endpointId", r.endpointId);
            o.put("api", r.api);
            o.put("kind", r.kind);
            o.put("field", r.field);
            o.put("before", r.before);
            o.put("after", r.after);
        }
        return a;
    }
}
