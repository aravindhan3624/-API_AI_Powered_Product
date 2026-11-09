package com.api.product.studio;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for an API that is not built yet: answers the project's REST endpoints from their response contract (or a generic
 * answer), with optional latency and random failures. Listens on 127.0.0.1 only. It never forwards anything anywhere.
 */
public final class MockServer {

    public static final class Instance {
        public final String project;
        public final int port;
        public final int latencyMs;
        public final int errorPct;
        public final AtomicLong served = new AtomicLong();
        HttpServer server;

        Instance(String project, int port, int latencyMs, int errorPct) {
            this.project = project;
            this.port = port;
            this.latencyMs = latencyMs;
            this.errorPct = errorPct;
        }

        public String url() {
            return "http://localhost:" + port;
        }
    }

    private static final class Route {
        final String method;
        final Pattern pattern;
        final int wildcards;
        final int status;
        final JsonNode body;

        Route(String method, Pattern pattern, int wildcards, int status, JsonNode body) {
            this.method = method;
            this.pattern = pattern;
            this.wildcards = wildcards;
            this.status = status;
            this.body = body;
        }
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private final Map<String, Instance> running = new ConcurrentHashMap<>();

    public Instance get(String project) {
        return running.get(project);
    }

    public synchronized Instance start(String project, ApiModel model, int port, int latencyMs, int errorPct) throws IOException {
        if (running.containsKey(project)) throw new IllegalStateException("The mock server for " + project + " is already running on " + running.get(project).url());
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Port must be between 1024 and 65535");
        if (latencyMs < 0 || latencyMs > 30000) throw new IllegalArgumentException("Latency must be between 0 and 30000 ms");
        if (errorPct < 0 || errorPct > 100) throw new IllegalArgumentException("Error percentage must be between 0 and 100");
        List<Route> routes = routes(model);
        if (routes.isEmpty()) throw new IllegalArgumentException("This project has no REST endpoints to mock");
        Instance inst = new Instance(project, port, latencyMs, errorPct);
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        s.createContext("/", ex -> handle(ex, inst, routes));
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.start();
        inst.server = s;
        running.put(project, inst);
        return inst;
    }

    public synchronized void stop(String project) {
        Instance i = running.remove(project);
        if (i != null && i.server != null) i.server.stop(0);
    }

    public void stopAll() {
        new ArrayList<>(running.keySet()).forEach(this::stop);
    }

    // ------------------------------------------------------------------ routes

    private static List<Route> routes(ApiModel m) {
        List<Route> out = new ArrayList<>();
        for (Endpoint e : m.endpoints) {
            if (e.contentType != null && !e.contentType.toLowerCase().contains("json") && e.requestBody != null && e.requestBody.isTextual()) continue;   // SOAP etc.
            String tpl = e.specKey != null ? e.specKey.replaceFirst("^\\S+ ", "") : e.path == null ? "/" : e.path;
            tpl = tpl.replaceAll("\\?.*", "");
            int wild = 0;
            StringBuilder re = new StringBuilder("^");
            for (String part : tpl.split("/", -1)) {
                if (part.isEmpty()) continue;
                re.append("/");
                if (part.matches("\\{[^}]+}|\\$\\{[^}]+}")) {
                    re.append("[^/]+");
                    wild++;
                } else {
                    re.append(Pattern.quote(part));
                }
            }
            if (re.length() == 1) re.append("/");
            re.append("/?$");
            int status = 200;
            if (e.method.equals("POST")) status = 201;
            JsonNode body = e.responseSchema != null ? sample(e.responseSchema, 0) : F.objectNode().put("message", "mock response for " + e.method + " " + tpl);
            out.add(new Route(e.method, Pattern.compile(re.toString()), wild, status, body));
        }
        out.sort(Comparator.comparingInt(r -> r.wildcards));
        return out;
    }

    private static void handle(HttpExchange ex, Instance inst, List<Route> routes) throws IOException {
        try {
            inst.served.incrementAndGet();
            if (inst.latencyMs > 0) {
                try {
                    Thread.sleep(inst.latencyMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (inst.errorPct > 0 && Math.random() * 100 < inst.errorPct) {
                send(ex, 503, F.objectNode().put("error", "mock failure injected (" + inst.errorPct + "% error rate)"));
                return;
            }
            boolean pathKnown = false;
            for (Route r : routes) {
                if (!r.pattern.matcher(path).matches()) continue;
                pathKnown = true;
                if (r.method.equalsIgnoreCase(method)) {
                    send(ex, r.status, r.body);
                    return;
                }
            }
            if (pathKnown) send(ex, 405, F.objectNode().put("error", "method not allowed"));
            else send(ex, 404, F.objectNode().put("error", "no mock for " + method + " " + path));
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = M.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("X-Mock-Server", "API Quality Studio");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** A value that satisfies the schema: example/default/enum first, otherwise a small typed sample. */
    static JsonNode sample(JsonNode s, int depth) {
        if (s == null || !s.isObject() || depth > 5) return F.nullNode();
        if (s.has("example")) return s.get("example");
        if (s.has("default")) return s.get("default");
        if (s.has("enum") && s.get("enum").size() > 0) return s.get("enum").get(0);
        if (s.has("allOf")) {
            ObjectNode merged = F.objectNode();
            for (JsonNode p : s.get("allOf")) {
                JsonNode v = sample(p, depth + 1);
                if (v.isObject()) merged.setAll((ObjectNode) v);
            }
            return merged;
        }
        for (String k : new String[] { "oneOf", "anyOf" }) if (s.has(k) && s.get(k).size() > 0) return sample(s.get(k).get(0), depth + 1);
        String type = s.path("type").asText(s.has("properties") ? "object" : s.has("items") ? "array" : "string");
        switch (type) {
            case "object": {
                ObjectNode o = F.objectNode();
                Iterator<Map.Entry<String, JsonNode>> it = s.path("properties").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    o.set(e.getKey(), sample(e.getValue(), depth + 1));
                }
                return o;
            }
            case "array": {
                ArrayNode a = F.arrayNode();
                a.add(sample(s.path("items"), depth + 1));
                return a;
            }
            case "integer": return F.numberNode(s.has("minimum") ? s.get("minimum").asLong() : 1L);
            case "number": return F.numberNode(s.has("minimum") ? s.get("minimum").asDouble() : 1.5);
            case "boolean": return F.booleanNode(true);
            default: {
                String fmt = s.path("format").asText("");
                if (fmt.equals("date-time")) return F.textNode("2025-01-15T10:00:00Z");
                if (fmt.equals("date")) return F.textNode("2025-01-15");
                if (fmt.equals("email")) return F.textNode("user@example.test");
                if (fmt.equals("uuid")) return F.textNode("123e4567-e89b-12d3-a456-426614174000");
                return F.textNode("string");
            }
        }
    }
}
