package com.api.product.capture;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Compares a recorded flow with a project: which observed calls the project already has, which are new (a possible
 * inventory gap, OWASP API9) and in which order the application calls them. New calls can be turned into DRAFT endpoints
 * with a DRAFT positive test; redacted values become ${...} placeholders so nothing secret is ever written into a model.
 */
public final class CaptureAnalyzer {

    public static final class Step {
        public int no;
        public String page;
        public String method;
        public String path;
        public int status;
        public String endpointId;   // matched project endpoint or null
        public boolean isNew;
    }

    public static final class Analysis {
        public List<Step> flow = new ArrayList<>();
        public int observed;
        public int matched;
        public List<String> newCalls = new ArrayList<>();
        public List<String> neverSeen = new ArrayList<>();
        public List<Endpoint> proposedEndpoints = new ArrayList<>();
        public List<TestCase> proposedTests = new ArrayList<>();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern ID_SEG = Pattern.compile("/(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}|[0-9a-fA-F]{24,})(?=/|$)");
    private static final Pattern STATIC = Pattern.compile("(?i).*\\.(js|css|png|jpg|jpeg|gif|svg|ico|woff2?|ttf|map|html?)(\\?.*)?$");

    private CaptureAnalyzer() {}

    public static Analysis analyze(ApiModel model, List<FlowCapture.Entry> entries) {
        Analysis a = new Analysis();
        String base = model.environments.isEmpty() || model.environments.get(0).baseUrl == null ? "" : model.environments.get(0).baseUrl;
        String basePath = pathOf(base);
        Map<String, Endpoint> seenNew = new LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        int no = 0;
        for (FlowCapture.Entry e : entries) {
            String p = pathOf(e.url);
            if (p == null || STATIC.matcher(p).matches() || "GET".equals(e.method) && e.responseContentType != null
                    && e.responseContentType.toLowerCase(Locale.ROOT).contains("html")) continue;
            a.observed++;
            String rel = p.startsWith(basePath) && !basePath.isEmpty() ? p.substring(basePath.length()) : p;
            String norm = norm(rel);
            Endpoint hit = null;
            for (Endpoint ep : model.endpoints) {
                if (ep.method.equalsIgnoreCase(e.method) && norm(pathOnly(ep.path)).equals(norm)) {
                    hit = ep;
                    break;
                }
            }
            Step s = new Step();
            s.no = ++no;
            s.page = e.page;
            s.method = e.method;
            s.path = rel;
            s.status = e.status;
            if (hit != null) {
                s.endpointId = hit.id;
                used.add(hit.id);
                a.matched++;
            } else {
                s.isNew = true;
                String key = e.method + " " + norm;
                if (!seenNew.containsKey(key)) {
                    Endpoint ne = proposed(model, e, rel, seenNew.size() + 1);
                    seenNew.put(key, ne);
                    a.newCalls.add(e.method + " " + rel + " -> " + e.status);
                }
            }
            a.flow.add(s);
        }
        for (Endpoint ep : model.endpoints) if (!used.contains(ep.id)) a.neverSeen.add(ep.id);
        for (Endpoint ne : seenNew.values()) {
            a.proposedEndpoints.add(ne);
            TestCase t = new TestCase();
            t.id = "CAP_" + ne.id;
            t.endpointId = ne.id;
            t.category = "POSITIVE";
            t.title = "Captured: " + ne.method + " " + ne.path;
            t.lifecycle = "DRAFT";
            t.origin = "captured";
            t.tags.add("captured from a real browser session; review the request data first");
            a.proposedTests.add(t);
        }
        return a;
    }

    private static Endpoint proposed(ApiModel model, FlowCapture.Entry e, String rel, int n) {
        Endpoint ep = new Endpoint();
        ep.id = "cap" + n + "_" + e.method.toLowerCase(Locale.ROOT) + "_" + rel.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        ep.name = "Captured " + e.method + " " + rel;
        ep.module = "Captured";
        ep.method = e.method.toUpperCase(Locale.ROOT);
        ep.path = rel;
        if (e.requestContentType != null && !e.requestContentType.isBlank()) ep.contentType = e.requestContentType.split(";")[0];
        if (e.requestBody != null && !e.requestBody.isBlank()) {
            try {
                JsonNode body = M.readTree(e.requestBody.replace("\"***\"", "\"${REDACTED}\""));
                ep.requestBody = body;
            } catch (Exception notJson) {
                // non-JSON bodies are not copied
            }
        }
        ep.idempotent = "GET".equals(ep.method);
        return ep;
    }

    private static String pathOf(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String pathOnly(String p) {
        if (p == null) return "";
        int q = p.indexOf('?');
        return q >= 0 ? p.substring(0, q) : p;
    }

    static String norm(String p) {
        String s = pathOnly(p).toLowerCase(Locale.ROOT).replaceAll("\\$\\{[^}]*}|\\{[^}]*}", "/{}");
        s = ID_SEG.matcher(s).replaceAll("/{}");
        s = s.replaceAll("/+", "/");
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }
}
