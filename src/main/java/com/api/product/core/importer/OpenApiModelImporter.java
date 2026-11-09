package com.api.product.core.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAPI 3.x / Swagger 2.0 (JSON) -> normalized model + deterministic test candidates.
 * Per operation: 1 POSITIVE, up to 3 MISSING_FIELD, 1 INVALID_TYPE, 1 NO_SESSION (when a header-style key/bearer is declared).
 * Expected behaviour comes from the contract (declared 2xx responses) and the rule pack - never an assumed 400.
 */
public final class OpenApiModelImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String[] METHODS = { "get", "post", "put", "patch", "delete", "head", "options" };

    private final ApiModel model = new ApiModel();
    private final Map<String, Integer> ids = new LinkedHashMap<>();
    private JsonNode root;
    private String lifecycle;
    private String authHeader;      // header name carrying an API key, or "Authorization"
    private String authValue;       // value template
    private boolean unsupportedBody; // current operation needs a body type the runner cannot send yet

    private OpenApiModelImporter() {}

    public static ApiModel importSpec(Path file, String lifecycle) throws Exception {
        OpenApiModelImporter imp = new OpenApiModelImporter();
        imp.lifecycle = lifecycle == null ? "DRAFT" : lifecycle;
        imp.root = M.readTree(file.toFile());
        return imp.build(file.getFileName().toString());
    }

    public static boolean looksLikeOpenApi(JsonNode n) {
        return n.has("openapi") || n.has("swagger");
    }

    private ApiModel build(String fileName) {
        JsonNode info = root.path("info");
        model.api.name = info.path("title").asText(fileName);
        model.api.version = info.path("version").asText("");
        model.api.source.type = "openapi";
        model.api.source.ref = fileName + " (" + (root.has("openapi") ? "OpenAPI " + root.get("openapi").asText()
                : "Swagger " + root.path("swagger").asText()) + ")";

        Environment env = new Environment();
        env.name = "DEFAULT";
        env.baseUrl = baseUrl();
        String override = System.getProperty("baseurl");
        if (override != null && !override.isBlank()) env.baseUrl = override;
        model.environments.add(env);
        detectAuth(env);

        JsonNode paths = root.path("paths");
        Iterator<String> it = paths.fieldNames();
        while (it.hasNext()) {
            String path = it.next();
            JsonNode item = paths.get(path);
            for (String method : METHODS) {
                if (item.has(method)) operation(path, method, item, item.get(method));
            }
        }
        if (model.endpoints.isEmpty()) model.api.source.warnings.add("No operations found under 'paths'.");
        return model;
    }

    private String baseUrl() {
        if (root.has("servers") && root.get("servers").size() > 0) {
            String u = root.get("servers").get(0).path("url").asText("");
            if (!u.startsWith("http")) {
                model.api.source.warnings.add("Server URL '" + u + "' is relative; set environments[0].baseUrl.");
            }
            return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        }
        if (root.has("host")) {
            String scheme = "https";
            JsonNode schemes = root.path("schemes");
            if (schemes.isArray() && schemes.size() > 0) {
                scheme = schemes.toString().contains("https") ? "https" : schemes.get(0).asText("https");
            }
            String bp = root.path("basePath").asText("");
            return scheme + "://" + root.get("host").asText() + (bp.equals("/") ? "" : bp);
        }
        model.api.source.warnings.add("No servers/host in the specification; set environments[0].baseUrl.");
        return "";
    }

    private void detectAuth(Environment env) {
        JsonNode defs = root.has("components") ? root.path("components").path("securitySchemes") : root.path("securityDefinitions");
        Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
        while (it.hasNext()) {
            JsonNode s = it.next().getValue();
            String type = s.path("type").asText();
            if ("apiKey".equals(type) && "header".equals(s.path("in").asText()) && authHeader == null) {
                authHeader = s.path("name").asText();
                authValue = "${apiKey}";
                env.variables.put("apiKey", "");
                ApiModel.AuthProfile ap = new ApiModel.AuthProfile();
                ap.id = "apiKeyAuth";
                ap.type = "apikey";
                ap.name = authHeader;
                ap.value = "${apiKey}";
                model.authProfiles.add(ap);
            } else if (("http".equals(type) && "bearer".equalsIgnoreCase(s.path("scheme").asText())) && authHeader == null) {
                authHeader = "Authorization";
                authValue = "Bearer ${token}";
                env.variables.put("token", "");
                ApiModel.AuthProfile bp = new ApiModel.AuthProfile();
                bp.id = "bearerAuth";
                bp.type = "bearer";
                bp.token = "${token}";
                model.authProfiles.add(bp);
            } else if ("oauth2".equals(type) || "basic".equals(type) || "http".equals(type)) {
                model.api.source.warnings.add("Security scheme type '" + type
                        + "' is not auto-configured; add headers or an auth step manually.");
            }
        }
    }

    private void operation(String path, String method, JsonNode pathItem, JsonNode op) {
        Endpoint ep = new Endpoint();
        String opId = op.path("operationId").asText("");
        ep.id = unique(opId.isEmpty() ? method + "_" + path.replaceAll("[^A-Za-z0-9]+", "_") : opId.replaceAll("[^A-Za-z0-9_]+", "_"));
        ep.name = op.path("summary").asText(op.path("description").asText(method.toUpperCase() + " " + path));
        if (ep.name.length() > 90) ep.name = ep.name.substring(0, 90);
        ep.method = method.toUpperCase();
        ep.module = op.path("tags").isArray() && op.get("tags").size() > 0 ? op.get("tags").get(0).asText() : firstSegment(path);

        String resolvedPath = path;
        List<JsonNode> params = new ArrayList<>();
        pathItem.path("parameters").forEach(params::add);
        op.path("parameters").forEach(params::add);
        JsonNode bodySchema = null;
        boolean unsupported = false;
        for (JsonNode p0 : params) {
            JsonNode p = deref(p0, 0);
            String in = p.path("in").asText();
            String name = p.path("name").asText();
            JsonNode schema = p.has("schema") ? deref(p.get("schema"), 0) : p;
            JsonNode example = p.has("example") ? p.get("example") : p.has("x-example") ? p.get("x-example") : null;
            switch (in) {
                case "path" -> resolvedPath = resolvedPath.replace("{" + name + "}", (example != null ? example : sample(schema, 0)).asText());
                case "query" -> {
                    if (p.path("required").asBoolean(false)) ep.query.put(name, sample(schema, 0).asText());
                }
                case "header" -> {
                    if (p.path("required").asBoolean(false)) ep.headers.put(name, sample(schema, 0).asText());
                }
                case "body" -> bodySchema = deref(p.path("schema"), 0);
                case "formData" -> {
                    unsupported = true;
                    model.api.source.warnings.add(ep.id + ": formData parameter '" + name + "' not supported yet.");
                }
                default -> { }
            }
        }
        if (op.has("requestBody")) {
            JsonNode content = deref(op.get("requestBody"), 0).path("content");
            if (content.has("application/json")) {
                bodySchema = deref(content.get("application/json").path("schema"), 0);
            } else if (content.size() > 0) {
                unsupported = true;
                model.api.source.warnings.add(ep.id + ": request body type '" + content.fieldNames().next() + "' not supported yet.");
            }
        }
        if (resolvedPath.contains("{")) {
            model.api.source.warnings.add(ep.id + ": path parameter(s) not declared in the spec; sample value used.");
            resolvedPath = resolvedPath.replaceAll("\\{[^}]+}", "sample");
        }
        ep.path = resolvedPath;
        ep.specKey = method.toUpperCase() + " " + path;
        ep.responseSchema = responseSchema(op);
        ep.specHash = hash(params, bodySchema, ep.responseSchema);
        this.unsupportedBody = unsupported;

        JsonNode body = null;
        if (bodySchema != null && !bodySchema.isMissingNode()) {
            body = sample(bodySchema, 0);
            ep.requestBody = body;
            ep.contentType = "application/json";
        }

        boolean needsAuth = authHeader != null && (op.has("security") ? op.get("security").size() > 0
                : root.path("security").size() > 0);
        if (needsAuth) ep.authProfileRef = model.authProfiles.get(0).id;

        ep.idempotent = !"POST".equals(ep.method) && !"PATCH".equals(ep.method);
        model.endpoints.add(ep);

        List<Integer> ok = new ArrayList<>();
        op.path("responses").fieldNames().forEachRemaining(code -> {
            if (code.matches("[23]\\d\\d")) ok.add(Integer.parseInt(code));
        });
        TestCase pos = tc(ep, "POSITIVE", "Valid request");
        if (!ok.isEmpty()) pos.expected.status.addAll(ok);
        model.testCases.add(pos);

        if (body instanceof ObjectNode obj && bodySchema != null) {
            int n = 0;
            for (JsonNode r : bodySchema.path("required")) {
                if (n++ >= 3) break;
                TestCase t = tc(ep, "MISSING_FIELD", "Required field '" + r.asText() + "' missing");
                t.mutation = new Mutation();
                t.mutation.removeBodyFields.add(r.asText());
                model.testCases.add(t);
            }
            Iterator<String> fn = obj.fieldNames();
            if (fn.hasNext()) {
                String f = fn.next();
                TestCase t = tc(ep, "INVALID_TYPE", "Field '" + f + "' with wrong type");
                t.mutation = new Mutation();
                ArrayNode wrong = F.arrayNode();
                wrong.add("invalid");
                t.mutation.setBodyFields.put(f, wrong);
                model.testCases.add(t);
            }
        }
        if (needsAuth) {
            TestCase t = tc(ep, "NO_SESSION", "No credentials supplied");
            t.mutation = new Mutation();
            t.mutation.removeHeaders.add(authHeader);
            model.testCases.add(t);
        }
    }

    /** Schema of the first 2xx JSON response, fully dereferenced (cycles cut), or null when the spec declares none. */
    private JsonNode responseSchema(JsonNode op) {
        JsonNode responses = op.path("responses");
        for (String code : iter(responses.fieldNames())) {
            if (!code.matches("2\\d\\d")) continue;
            JsonNode r = deref(responses.get(code), 0);
            JsonNode schema = null;
            if (r.has("content")) {
                JsonNode c = r.get("content");
                if (c.has("application/json")) schema = c.get("application/json").get("schema");
                else for (String ct : iter(c.fieldNames())) if (ct.contains("json")) { schema = c.get(ct).get("schema"); break; }
            } else if (r.has("schema")) {
                schema = r.get("schema");
            }
            if (schema != null) return full(schema, 0, new java.util.HashSet<>());
        }
        return null;
    }

    private static List<String> iter(Iterator<String> it) {
        List<String> l = new ArrayList<>();
        it.forEachRemaining(l::add);
        return l;
    }

    /** Fully dereferenced copy. {@code depth} counts $ref hops only (JSON nesting is not limited), so leaf values are never cut. */
    private JsonNode full(JsonNode n, int depth, java.util.Set<String> seen) {
        if (n == null || depth > 7) return F.objectNode();
        if (n.has("$ref")) {
            String ref = n.get("$ref").asText();
            if (!ref.startsWith("#/") || seen.contains(ref)) return F.objectNode();   // cycle or external: unconstrained
            java.util.Set<String> next = new java.util.HashSet<>(seen);
            next.add(ref);
            JsonNode cur = root;
            for (String part : ref.substring(2).split("/")) cur = cur.path(part.replace("~1", "/").replace("~0", "~"));
            return full(cur, depth + 1, next);
        }
        if (n.isObject()) {
            ObjectNode out = F.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if (k.equals("example") || k.equals("examples") || k.equals("description") || k.equals("xml") || k.equals("externalDocs")) continue;
                out.set(k, full(e.getValue(), depth, seen));
            }
            return out;
        }
        if (n.isArray()) {
            ArrayNode a = F.arrayNode();
            n.forEach(x -> a.add(full(x, depth, seen)));
            return a;
        }
        return n;
    }

    private String hash(List<JsonNode> params, JsonNode bodySchema, JsonNode respSchema) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode p0 : params) {
            JsonNode p = deref(p0, 0);
            sb.append(p.path("in").asText()).append(':').append(p.path("name").asText()).append(':').append(p.path("required").asBoolean(false))
                    .append(':').append(p.has("schema") ? full(p.get("schema"), 0, new java.util.HashSet<>()).toString() : p.path("type").asText()).append(';');
        }
        if (bodySchema != null) sb.append("body=").append(full(bodySchema, 0, new java.util.HashSet<>())).append(';');
        if (respSchema != null) sb.append("resp=").append(respSchema);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private TestCase tc(Endpoint ep, String category, String title) {
        TestCase t = new TestCase();
        t.id = "TC_" + ep.id + "_" + category + (category.equals("MISSING_FIELD") ? "_" + countFor(ep.id + category) : "");
        t.endpointId = ep.id;
        t.category = category;
        t.title = ep.name + " - " + title;
        t.lifecycle = unsupportedBody ? "DRAFT" : lifecycle;
        if (unsupportedBody) t.tags.add("unsupported-body");
        t.origin = "generated";
        return t;
    }

    private int countFor(String key) {
        return ids.merge("tc:" + key, 1, Integer::sum);
    }

    private String unique(String base) {
        int n = ids.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "_" + n;
    }

    private static String firstSegment(String path) {
        for (String s : path.split("/")) if (!s.isEmpty() && !s.startsWith("{")) return s;
        return "General";
    }

    // ---- schema helpers -------------------------------------------------

    private JsonNode deref(JsonNode n, int depth) {
        if (n == null || depth > 8) return n;
        if (n.has("$ref")) {
            String ref = n.get("$ref").asText();
            if (ref.startsWith("#/")) {
                JsonNode cur = root;
                for (String part : ref.substring(2).split("/")) cur = cur.path(part);
                return deref(cur, depth + 1);
            }
        }
        return n;
    }

    private JsonNode sample(JsonNode schema0, int depth) {
        JsonNode schema = deref(schema0, 0);
        if (schema == null || schema.isMissingNode() || depth > 5) return F.nullNode();
        if (schema.has("example")) return schema.get("example");
        if (schema.has("default")) return schema.get("default");
        if (schema.has("enum") && schema.get("enum").size() > 0) return schema.get("enum").get(0);
        if (schema.has("allOf")) {
            ObjectNode merged = F.objectNode();
            for (JsonNode part : schema.get("allOf")) {
                JsonNode s = sample(part, depth + 1);
                if (s instanceof ObjectNode o) merged.setAll(o);
            }
            return merged;
        }
        if (schema.has("oneOf") && schema.get("oneOf").size() > 0) return sample(schema.get("oneOf").get(0), depth + 1);
        if (schema.has("anyOf") && schema.get("anyOf").size() > 0) return sample(schema.get("anyOf").get(0), depth + 1);
        String type = schema.path("type").asText(schema.has("properties") ? "object" : "string");
        type = switch (type) {
            case "int", "int32", "int64", "long" -> "integer";
            case "float", "double", "decimal" -> "number";
            case "bool" -> "boolean";
            default -> type;
        };
        switch (type) {
            case "object": {
                ObjectNode o = F.objectNode();
                Iterator<Map.Entry<String, JsonNode>> it = schema.path("properties").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    o.set(e.getKey(), sample(e.getValue(), depth + 1));
                }
                return o;
            }
            case "array": {
                ArrayNode a = F.arrayNode();
                a.add(sample(schema.path("items"), depth + 1));
                return a;
            }
            case "integer":
                return F.numberNode(schema.has("minimum") ? schema.get("minimum").asLong() : 1L);
            case "number":
                return F.numberNode(schema.has("minimum") ? schema.get("minimum").asDouble() : 1.5);
            case "boolean":
                return F.booleanNode(true);
            default: {
                String fmt = schema.path("format").asText("");
                return F.textNode(switch (fmt) {
                    case "date" -> "2024-01-15";
                    case "date-time" -> "2024-01-15T10:00:00Z";
                    case "email" -> "test@example.com";
                    case "uuid" -> "123e4567-e89b-12d3-a456-426614174000";
                    case "uri", "url" -> "https://example.com";
                    default -> "string";
                });
            }
        }
    }
}
