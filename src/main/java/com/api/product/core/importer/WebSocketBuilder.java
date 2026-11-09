package com.api.product.core.importer;

import java.net.URI;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Creates WebSocket requests (method WS) from a ws:// or wss:// address, a message to send and what the answer must contain. */
public final class WebSocketBuilder {

    private WebSocketBuilder() {}

    /** A new project model with one WebSocket request. */
    public static ApiModel newModel(String wsUrl, String message, int expectMessages, String expectContains) {
        URI u = parse(wsUrl);
        ApiModel m = new ApiModel();
        m.api.name = u.getHost() + u.getPath().replaceAll("[^A-Za-z0-9]+", "_");
        m.api.source.type = "websocket";
        m.api.source.ref = wsUrl;
        Environment env = new Environment();
        env.name = "DEFAULT";
        env.baseUrl = (u.getScheme().equals("wss") ? "https://" : "http://") + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        m.environments.add(env);
        add(m, wsUrl, message, expectMessages, expectContains);
        return m;
    }

    /** Adds one WebSocket request (path relative to the project's application URL, or a full ws/wss address). */
    public static Endpoint add(ApiModel m, String pathOrUrl, String message, int expectMessages, String expectContains) {
        String path = pathOrUrl.trim();
        if (path.matches("(?i)^wss?://.*")) {
            URI u = parse(path);
            path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } else if (!path.startsWith("/")) {
            path = "/" + path;
        }
        Endpoint ep = new Endpoint();
        int n = 1;
        String base = "ws_" + path.replaceAll("\\?.*", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        String id = base;
        while (m.endpoint(id) != null) id = base + "_" + (++n);
        ep.id = id;
        ep.name = "WebSocket " + path;
        ep.module = "WebSocket";
        ep.method = "WS";
        ep.path = path;
        ep.contentType = "text/plain";
        if (message != null && !message.isEmpty()) {
            try {
                ep.requestBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(message);
            } catch (Exception notJson) {
                ep.requestBody = JsonNodeFactory.instance.textNode(message);
            }
        }
        ep.expectMessages = Math.max(1, expectMessages);
        ep.idempotent = false;
        m.endpoints.add(ep);
        TestCase t = new TestCase();
        t.id = "TC_" + ep.id + "_POSITIVE";
        t.endpointId = ep.id;
        t.category = "POSITIVE";
        t.title = ep.name + " - connect, send and receive";
        t.lifecycle = "DRAFT";
        t.origin = "generated";
        t.expected.status.add(101);
        if (expectContains != null && !expectContains.isBlank()) {
            Assertion a = new Assertion();
            a.type = "bodyContains";
            a.expected = JsonNodeFactory.instance.textNode(expectContains);
            t.expected.assertions.add(a);
        }
        m.testCases.add(t);
        return ep;
    }

    private static URI parse(String url) {
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter a WebSocket address such as wss://host/path");
        }
        if (u.getScheme() == null || !(u.getScheme().equals("ws") || u.getScheme().equals("wss")) || u.getHost() == null) {
            throw new IllegalArgumentException("A WebSocket address starts with ws:// or wss://");
        }
        return u;
    }
}
