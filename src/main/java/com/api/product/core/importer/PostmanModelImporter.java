package com.api.product.core.importer;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Postman Collection v2.1 -> normalized model. Data only: no Java is generated and no Postman script is executed
 * (scripts are reported as warnings). Every imported request becomes one DRAFT positive test that needs review.
 */
public final class PostmanModelImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern PM_VAR = Pattern.compile("\\{\\{\\s*([^}\\s]+)\\s*}}");
    private static final Pattern ORIGIN = Pattern.compile("^(https?://[^/?#]+)(.*)$");

    private final ApiModel model = new ApiModel();
    private final Set<String> usedIds = new LinkedHashSet<>();
    private int scripts;

    private PostmanModelImporter() {}

    public static ApiModel importCollection(Path file) throws Exception {
        JsonNode root = M.readTree(file.toFile());
        PostmanModelImporter imp = new PostmanModelImporter();
        return imp.build(root, file.getFileName().toString());
    }

    private ApiModel build(JsonNode root, String fileName) {
        JsonNode info = root.path("info");
        model.api.name = info.path("name").asText(fileName);
        model.api.source.type = "postman";
        model.api.source.ref = fileName;

        Environment env = new Environment();
        env.name = "DEFAULT";
        for (JsonNode v : root.path("variable")) {
            if (v.hasNonNull("key")) env.variables.put(v.get("key").asText(), v.path("value").asText(""));
        }
        model.environments.add(env);

        String collectionAuth = bearerOf(root.path("auth"));
        walk(root.path("item"), "", collectionAuth, env);
        if (env.baseUrl == null) {
            env.baseUrl = "";
            model.api.source.warnings.add("No common base URL detected; set environments[0].baseUrl.");
        }
        if (scripts > 0) {
            model.api.source.warnings.add(scripts + " pre-request/test script(s) were NOT executed; "
                    + "re-create their checks as assertions.");
        }
        return model;
    }

    private void walk(JsonNode items, String folder, String inheritedAuth, Environment env) {
        for (JsonNode it : items) {
            String name = it.path("name").asText("request");
            String auth = it.has("auth") ? bearerOf(it.get("auth")) : inheritedAuth;
            if (it.has("item")) {
                walk(it.get("item"), folder.isEmpty() ? name : folder + "/" + name, auth, env);
            } else if (it.has("request")) {
                addRequest(it, name, folder, auth, env);
            }
        }
    }

    private void addRequest(JsonNode item, String name, String folder, String auth, Environment env) {
        JsonNode req = item.get("request");
        if (item.has("event")) scripts += item.get("event").size();

        Endpoint ep = new Endpoint();
        ep.id = uniqueId(name);
        ep.name = name;
        ep.method = req.path("method").asText("GET").toUpperCase();

        String raw = req.path("url").isTextual() ? req.path("url").asText() : req.path("url").path("raw").asText("");
        raw = convertVars(raw);
        int q = raw.indexOf('?');
        String noQuery = q >= 0 ? raw.substring(0, q) : raw;
        ep.path = split(noQuery, env);
        if (q >= 0) {
            for (String pair : raw.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                String[] kv = pair.split("=", 2);
                ep.query.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }

        for (JsonNode h : req.path("header")) {
            if (h.path("disabled").asBoolean(false) || !h.hasNonNull("key")) continue;
            ep.headers.put(h.get("key").asText(), convertVars(h.path("value").asText("")));
        }
        if (auth != null && ep.headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Authorization"))) {
            ep.headers.put("Authorization", "Bearer " + auth);
        }
        ep.headers.keySet().stream().filter(k -> k.equalsIgnoreCase("Content-Type")).findFirst()
                .ifPresent(k -> ep.contentType = ep.headers.remove(k));

        JsonNode body = req.path("body");
        if ("raw".equals(body.path("mode").asText())) {
            String text = convertVars(body.path("raw").asText(""));
            try {
                ep.requestBody = M.readTree(text);
                if (ep.contentType == null) ep.contentType = "application/json";
            } catch (Exception notJson) {
                ep.requestBody = new TextNode(text);
                model.api.source.warnings.add(name + ": body is not valid JSON after variable conversion; kept as text.");
            }
        } else if (body.hasNonNull("mode")) {
            model.api.source.warnings.add(name + ": body mode '" + body.get("mode").asText() + "' not supported yet.");
        }
        if (!"GET".equals(ep.method) && !"HEAD".equals(ep.method)) ep.idempotent = "PUT".equals(ep.method) || "DELETE".equals(ep.method);
        model.endpoints.add(ep);

        TestCase tc = new TestCase();
        tc.id = "TC_" + ep.id;
        tc.endpointId = ep.id;
        tc.category = "POSITIVE";
        tc.title = (folder.isEmpty() ? "" : folder + " / ") + name;
        tc.lifecycle = "DRAFT";
        tc.origin = "imported";
        model.testCases.add(tc);
    }

    /** Splits origin from path; the first origin (or {{var}} base) becomes the environment baseUrl. */
    private String split(String url, Environment env) {
        Matcher m = ORIGIN.matcher(url);
        String base = null;
        String path = url;
        if (m.matches()) {
            base = m.group(1);
            path = m.group(2);
        } else if (url.startsWith("${")) {
            int end = url.indexOf('}');
            base = url.substring(0, end + 1);
            path = url.substring(end + 1);
        }
        if (base != null) {
            if (env.baseUrl == null) env.baseUrl = base;
            else if (!env.baseUrl.equals(base)) return url; // different server: keep absolute URL
        }
        return path.isEmpty() ? "/" : path;
    }

    private static String bearerOf(JsonNode auth) {
        if (auth == null || !"bearer".equals(auth.path("type").asText())) return null;
        for (JsonNode n : auth.path("bearer")) {
            if ("token".equals(n.path("key").asText())) return convertVars(n.path("value").asText(""));
        }
        return null;
    }

    private static String convertVars(String s) {
        return PM_VAR.matcher(s == null ? "" : s).replaceAll("\\${$1}");
    }

    private String uniqueId(String name) {
        String base = name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty()) base = "request";
        String id = base;
        int i = 2;
        while (!usedIds.add(id)) id = base + "_" + i++;
        return id;
    }
}
