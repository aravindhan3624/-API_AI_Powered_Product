package com.api.product.core.importer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
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
 * GraphQL adapter. Reads a schema from an introspection result (file or live endpoint) and creates one POST /graphql request per
 * query and mutation, with a generated operation text, sample variables, a valid-request test (no "errors" in the response) and
 * two negatives per operation that has required arguments (missing and wrongly typed variables). Mutations are treated as writes.
 */
public final class GraphQlImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");

    static final String INTROSPECTION = "query IntrospectionQuery { __schema { queryType { name } mutationType { name } types { kind name fields(includeDeprecated: true) { name args { name defaultValue type { ...TypeRef } } type { ...TypeRef } } inputFields { name type { ...TypeRef } } enumValues(includeDeprecated: true) { name } possibleTypes { name } } } } "
            + "fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }";

    private final Map<String, JsonNode> types = new HashMap<>();
    private final ApiModel model = new ApiModel();

    private GraphQlImporter() {}

    public static boolean looksLikeIntrospection(JsonNode n) {
        return n != null && (n.has("__schema") || n.path("data").has("__schema"));
    }

    /** Runs the standard introspection query against a live endpoint. Headers (for example Authorization) are used once and never stored. */
    public static JsonNode introspect(String url, Map<String, String> headers) throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        } };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        HttpClient c = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(8)).build();
        ObjectNode body = M.createObjectNode().put("query", INTROSPECTION);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (headers != null) headers.forEach(rb::header);
        HttpResponse<String> r = c.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j;
        try {
            j = M.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalArgumentException("The address answered HTTP " + r.statusCode() + " but not with JSON. Is this the GraphQL endpoint (often /graphql)?");
        }
        if (!looksLikeIntrospection(j)) {
            String msg = j.path("errors").isArray() && j.get("errors").size() > 0 ? j.get("errors").get(0).path("message").asText("") : "HTTP " + r.statusCode();
            throw new IllegalArgumentException("The endpoint did not return its schema (" + msg + "). Introspection may be disabled or need authentication.");
        }
        return j;
    }

    public static ApiModel fromIntrospection(JsonNode json, String url) {
        GraphQlImporter g = new GraphQlImporter();
        return g.build(json.has("data") ? json.get("data").get("__schema") : json.get("__schema"), url);
    }

    private ApiModel build(JsonNode schema, String url) {
        model.api.source.type = "graphql";
        model.api.source.ref = url == null ? "introspection file" : url;
        model.api.contractMode = "off";
        Environment env = new Environment();
        env.name = "DEFAULT";
        String path = "/graphql";
        if (url != null) {
            URI u = URI.create(url);
            env.baseUrl = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/graphql" : u.getRawPath();
            model.api.name = u.getHost() + path.replaceAll("[^A-Za-z0-9]+", "_");
            model.api.source.warnings.add("Introspection is enabled on this endpoint. That is useful for testing, but production systems should disable it (OWASP API8: security misconfiguration).");
        } else {
            env.baseUrl = "";
            model.api.name = "GraphQL";
            model.api.source.warnings.add("No endpoint URL known (schema came from a file); enter the application URL, the path is " + path + ".");
        }
        model.environments.add(env);
        for (JsonNode t : schema.path("types")) types.put(t.path("name").asText(), t);
        String q = schema.path("queryType").path("name").asText(null), m = schema.path("mutationType").path("name").asText(null);
        if (q != null) for (JsonNode f : types.getOrDefault(q, F.objectNode()).path("fields")) operation("query", f, path);
        if (m != null) for (JsonNode f : types.getOrDefault(m, F.objectNode()).path("fields")) operation("mutation", f, path);
        if (model.endpoints.isEmpty()) model.api.source.warnings.add("The schema has no queries or mutations.");
        return model;
    }

    private void operation(String kind, JsonNode field, String path) {
        String name = field.path("name").asText();
        if (name.startsWith("__")) return;
        List<String> decl = new ArrayList<>(), use = new ArrayList<>();
        ObjectNode vars = F.objectNode();
        List<String> required = new ArrayList<>();
        for (JsonNode a : field.path("args")) {
            if (!"NON_NULL".equals(a.path("type").path("kind").asText())) continue;
            String an = a.path("name").asText();
            decl.add("$" + an + ": " + typeRef(a.path("type")));
            use.add(an + ": $" + an);
            vars.set(an, sample(a.path("type"), 0, new HashSet<>()));
            required.add(an);
        }
        String sel = selection(field.path("type"), 0, new HashSet<>());
        String opName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String text = kind + " " + opName + (decl.isEmpty() ? "" : "(" + String.join(", ", decl) + ")") + " { " + name + (use.isEmpty() ? "" : "(" + String.join(", ", use) + ")") + (sel.isEmpty() ? "" : " { " + sel + " }") + " }";

        Endpoint ep = new Endpoint();
        ep.id = (kind.equals("query") ? "q_" : "m_") + name;
        ep.name = kind + " " + name;
        ep.module = kind.equals("query") ? "Query" : "Mutation";
        ep.method = "POST";
        ep.path = path;
        ep.contentType = "application/json";
        ObjectNode body = F.objectNode();
        body.put("query", text);
        body.set("variables", vars);
        ep.requestBody = body;
        ep.idempotent = kind.equals("query");
        model.endpoints.add(ep);

        TestCase pos = new TestCase();
        pos.id = "TC_" + ep.id + "_POSITIVE";
        pos.endpointId = ep.id;
        pos.category = "POSITIVE";
        pos.title = ep.name + " - valid request";
        pos.lifecycle = "DRAFT";
        pos.origin = "generated";
        pos.expected.status.add(200);
        pos.expected.assertions.add(assertion("jsonPathAbsent", "$.errors"));
        pos.expected.assertions.add(assertion("jsonPathExists", "$.data"));
        model.testCases.add(pos);

        if (!required.isEmpty()) {
            TestCase miss = neg(ep, "MISSING_FIELD", "required variable(s) missing");
            miss.mutation.setBodyFields.put("variables", F.objectNode());
            TestCase wrong = neg(ep, "INVALID_TYPE", "variable '" + required.get(0) + "' with the wrong type");
            ObjectNode wv = vars.deepCopy();
            ArrayNode arr = F.arrayNode();
            arr.add("invalid");
            wv.set(required.get(0), arr);
            wrong.mutation.setBodyFields.put("variables", wv);
        }
    }

    private TestCase neg(Endpoint ep, String category, String what) {
        TestCase t = new TestCase();
        t.id = "TC_" + ep.id + "_" + category;
        t.endpointId = ep.id;
        t.category = category;
        t.title = ep.name + " - " + what;
        t.mutation = new Mutation();
        t.lifecycle = "DRAFT";
        t.origin = "generated";
        t.basedOn = "TC_" + ep.id + "_POSITIVE";
        model.testCases.add(t);
        return t;
    }

    private static Assertion assertion(String type, String path) {
        Assertion a = new Assertion();
        a.type = type;
        a.path = path;
        return a;
    }

    // ------------------------------------------------------------------ types

    private static String typeRef(JsonNode t) {
        String kind = t.path("kind").asText();
        if ("NON_NULL".equals(kind)) return typeRef(t.path("ofType")) + "!";
        if ("LIST".equals(kind)) return "[" + typeRef(t.path("ofType")) + "]";
        return t.path("name").asText("String");
    }

    private static JsonNode unwrap(JsonNode t) {
        while (t.has("ofType") && !t.path("ofType").isNull() && ("NON_NULL".equals(t.path("kind").asText()) || "LIST".equals(t.path("kind").asText()))) t = t.get("ofType");
        return t;
    }

    private JsonNode sample(JsonNode t, int depth, Set<String> guard) {
        String kind = t.path("kind").asText();
        if ("NON_NULL".equals(kind)) return sample(t.path("ofType"), depth, guard);
        if ("LIST".equals(kind)) {
            ArrayNode a = F.arrayNode();
            a.add(sample(t.path("ofType"), depth + 1, guard));
            return a;
        }
        String name = t.path("name").asText();
        switch (name) {
            case "Int": return F.numberNode(1);
            case "Float": return F.numberNode(1.5);
            case "Boolean": return F.booleanNode(true);
            case "ID": return F.textNode("1");
            case "String": return F.textNode("string");
            default: break;
        }
        JsonNode def = types.get(name);
        if (def == null) return F.textNode("string");
        if ("ENUM".equals(def.path("kind").asText())) return F.textNode(def.path("enumValues").size() > 0 ? def.get("enumValues").get(0).path("name").asText() : "VALUE");
        if ("INPUT_OBJECT".equals(def.path("kind").asText())) {
            ObjectNode o = F.objectNode();
            if (depth > 3 || !guard.add(name)) return o;
            for (JsonNode f : def.path("inputFields")) {
                if ("NON_NULL".equals(f.path("type").path("kind").asText())) o.set(f.path("name").asText(), sample(f.path("type"), depth + 1, guard));
            }
            guard.remove(name);
            return o;
        }
        return F.textNode("custom");   // custom scalar: the user supplies a real value
    }

    private String selection(JsonNode typeRef, int depth, Set<String> guard) {
        JsonNode t = unwrap(typeRef);
        String name = t.path("name").asText();
        JsonNode def = types.get(name);
        if (def == null || SCALARS.contains(name)) return "";
        String kind = def.path("kind").asText();
        if ("ENUM".equals(kind) || "SCALAR".equals(kind)) return "";
        if ("UNION".equals(kind) || "INTERFACE".equals(kind) && !def.has("fields")) return "__typename";
        if (depth > 2 || !guard.add(name)) return "__typename";
        List<String> scalars = new ArrayList<>(), objects = new ArrayList<>();
        for (JsonNode f : def.path("fields")) {
            boolean needsArgs = false;
            for (JsonNode a : f.path("args")) if ("NON_NULL".equals(a.path("type").path("kind").asText())) needsArgs = true;
            if (needsArgs || f.path("name").asText().startsWith("__")) continue;
            JsonNode ft = unwrap(f.path("type"));
            JsonNode fd = types.get(ft.path("name").asText());
            boolean leaf = SCALARS.contains(ft.path("name").asText()) || fd == null || "SCALAR".equals(fd.path("kind").asText()) || "ENUM".equals(fd.path("kind").asText());
            if (leaf) {
                if (scalars.size() < (depth == 0 ? 5 : 3)) scalars.add(f.path("name").asText());
            } else if (objects.size() < 1 && depth == 0) {
                String inner = selection(f.path("type"), depth + 1, guard);
                if (!inner.isEmpty()) objects.add(f.path("name").asText() + " { " + inner + " }");
            }
        }
        guard.remove(name);
        List<String> all = new ArrayList<>(scalars);
        all.addAll(objects);
        return all.isEmpty() ? "__typename" : String.join(" ", all);
    }
}
