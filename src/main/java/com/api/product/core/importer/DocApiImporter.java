package com.api.product.core.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * API documentation (Markdown, text, Word or PDF text) to a draft project. It finds lines such as "POST /api/orders" (also inside
 * tables), takes the JSON block that follows as the request body, uses headings as modules and a "Base URL: https://..." line as the
 * application URL. It never invents data: endpoints without a visible body are created without one and reported.
 */
public final class DocApiImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern API = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+`?((?:https?://[^\\s/`]+)?/[A-Za-z0-9_\\-./{}:$?=&%]*)`?");
    private static final Pattern BASE = Pattern.compile("(?i)(?:base\\s*url|host|server|endpoint url|api url)\\s*[:=|]\\s*`?(https?://[^\\s`|)\"']+)");
    private static final Pattern ANY_URL = Pattern.compile("(https?://[A-Za-z0-9._\\-]+(?::\\d+)?(?:/[A-Za-z0-9_\\-./]*)?)");
    private static final Pattern HEADING = Pattern.compile("^(?:#{1,2}(?!#)\\s*|\\d+(?:\\.\\d+)*\\.?\\s+)(.{3,70})$");

    private DocApiImporter() {}

    public static ApiModel importText(String text, String fileName) {
        ApiModel m = new ApiModel();
        m.api.name = fileName == null ? "API document" : fileName.replaceAll("\\.[^.]+$", "");
        m.api.source.type = "document";
        m.api.source.ref = fileName;
        ApiModel.Environment env = new ApiModel.Environment();
        env.name = "DEFAULT";
        m.environments.add(env);

        String[] lines = text.split("\\R");
        Matcher bm = BASE.matcher(text);
        if (bm.find()) env.baseUrl = stripTrailing(bm.group(1));
        String module = "General";
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher hm = HEADING.matcher(line);
            Matcher am = API.matcher(line);
            if (!am.find()) {
                if (hm.matches() && !line.contains("{") && !line.endsWith(".") && line.length() < 70) module = hm.group(1).trim();
                continue;
            }
            am.reset();
            while (am.find()) {
                String method = am.group(1);
                String path = stripTrailing(am.group(2));
                if (path.startsWith("http")) {
                    Matcher u = Pattern.compile("^(https?://[^/]+)(/.*)$").matcher(path);
                    if (u.matches()) {
                        if (env.baseUrl == null) env.baseUrl = u.group(1);
                        path = u.group(2);
                    }
                }
                if (!seen.add(method + " " + path)) continue;
                Endpoint ep = new Endpoint();
                ep.id = "doc" + (++n) + "_" + method.toLowerCase(Locale.ROOT) + "_" + path.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
                ep.method = method;
                ep.path = path.replaceAll("\\{([^}]+)}", "\\${$1}");
                ep.module = module;
                ep.name = describe(lines, i, method, path);
                ep.idempotent = "GET".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
                JsonNode body = bodyAfter(lines, i);
                if (body != null && !"GET".equals(method)) {
                    ep.requestBody = body;
                    ep.contentType = "application/json";
                } else if (!"GET".equals(method) && !"DELETE".equals(method)) {
                    m.api.source.warnings.add(ep.id + ": no JSON request body found after this line; add one in Test data.");
                }
                m.endpoints.add(ep);
                TestCase t = new TestCase();
                t.id = "TC_" + ep.id;
                t.endpointId = ep.id;
                t.category = "POSITIVE";
                t.title = ep.name;
                t.lifecycle = "DRAFT";
                t.origin = "imported";
                m.testCases.add(t);
            }
        }
        if (env.baseUrl == null) {
            Matcher um = ANY_URL.matcher(text);
            if (um.find()) env.baseUrl = stripTrailing(um.group(1));
        }
        if (env.baseUrl == null) {
            env.baseUrl = "";
            m.api.source.warnings.add("No base URL found in the document; enter the application URL.");
        }
        if (m.endpoints.isEmpty()) m.api.source.warnings.add("No lines like 'POST /path' were found. Write each API as METHOD /path (for example POST /auth/login).");
        return m;
    }

    private static String describe(String[] lines, int i, String method, String path) {
        String line = lines[i].trim().replaceAll("[|`*#]+", " ").replaceAll("\\s+", " ").trim();
        String cleaned = line.replaceAll("\\b(GET|POST|PUT|PATCH|DELETE)\\s+\\S+", "").replaceAll("^[\\d.\\s:-]+", "").replaceAll("[\\s:-]+$", "").trim();
        if (cleaned.length() >= 4 && cleaned.length() <= 80) return cleaned;
        return method + " " + path;
    }

    /** The JSON object that follows an API line: a fenced block or a raw { ... } that closes within a few dozen lines. */
    private static JsonNode bodyAfter(String[] lines, int from) {
        List<String> buf = new ArrayList<>();
        int depth = 0;
        boolean started = false;
        for (int j = from; j < Math.min(lines.length, from + 60); j++) {
            String l = lines[j];
            if (j > from && API.matcher(l).find() && !started) return null;
            String t = l.trim();
            if (!started) {
                int k = t.indexOf('{');
                if (k < 0 || j == from && !t.substring(k).trim().startsWith("{")) continue;
                if (j == from) continue;
                started = true;
                t = t.substring(k);
            }
            buf.add(t);
            for (char c : t.toCharArray()) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            if (depth <= 0) break;
        }
        if (buf.isEmpty() || depth > 0) return null;
        try {
            return M.readTree(String.join("\n", buf).replace('“', '"').replace('”', '"'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailing(String s) {
        return s.replaceAll("[.,;:)>`|\"']+$", "");
    }
}
