package com.api.product.core.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.api.product.core.engine.RulePack;
import com.api.product.core.flow.AutoChain;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hands the tests over as code that developers can run without Studio: Karate feature files or a Rest Assured + TestNG project.
 * Secrets are never written: ${ENV:NAME} becomes an environment variable read at run time. WebSocket and gRPC tests are not exported
 * (the generated README lists them). Setup-before-use order of the Data flow is kept where the target framework allows it.
 */
public final class CodeExport {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");

    private CodeExport() {}

    public static final class Options {
        /** Include DRAFT and REVIEWED tests too (default: only APPROVED and ACTIVE). */
        public boolean includeDraft;
    }

    // ------------------------------------------------------------------ selection

    private static List<TestCase> select(ApiModel m, Options o, List<String> skipped) {
        List<TestCase> list = new ArrayList<>();
        for (TestCase t : m.testCases) {
            Endpoint e = m.endpoint(t.endpointId);
            if (e == null) continue;
            if (e.method.equals("WS") || e.method.equals("GRPC")) {
                skipped.add(t.id + " (" + e.method + " is not exported)");
                continue;
            }
            boolean ok = "ACTIVE".equals(t.lifecycle) || "APPROVED".equals(t.lifecycle) || (o.includeDraft && ("DRAFT".equals(t.lifecycle) || "REVIEWED".equals(t.lifecycle)));
            if (ok && !t.tags.contains("unsupported-body") && !t.tags.contains("run-flag-N")) list.add(t);
        }
        if (!m.links.isEmpty()) list.sort(Comparator.comparingInt(t -> AutoChain.rank(m, t)));
        return list;
    }

    private static List<Integer> expected(TestCase t, RulePack rules) {
        if (!t.expected.status.isEmpty()) return t.expected.status;
        List<Integer> r = rules.expected(t.category);
        return r.isEmpty() ? List.of(200, 201, 202) : r;
    }

    private static String baseUrl(ApiModel m) {
        return m.environments.isEmpty() || m.environments.get(0).baseUrl == null ? "" : m.environments.get(0).baseUrl;
    }

    private static Set<String> envNames(ApiModel m) {
        Set<String> n = new java.util.TreeSet<>();
        try {
            Matcher x = Pattern.compile("\\$\\{ENV:([A-Za-z0-9_]+)}").matcher(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m));
            while (x.find()) n.add(x.group(1));
        } catch (IOException ignored) {
            // no references found
        }
        return n;
    }

    private static Map<String, String> plainVars(ApiModel m) {
        Map<String, String> v = new LinkedHashMap<>();
        if (!m.environments.isEmpty()) {
            m.environments.get(0).variables.forEach((k, val) -> {
                if (val != null && !val.contains("${") && !k.matches("(?i).*(pass|secret|token|otp|key).*")) v.put(k, val);
            });
        }
        return v;
    }

    /** The request as it would be sent: mutation applied. */
    private static final class Req {
        String method;
        String path;
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        JsonNode body;
        String bodyText;
    }

    private static Req request(Endpoint e, TestCase t) {
        Req r = new Req();
        ApiModel.Mutation mu = t.mutation;
        r.method = mu != null && mu.method != null ? mu.method : e.method;
        r.path = mu != null && mu.path != null ? mu.path : e.path;
        e.headers.forEach((k, v) -> r.headers.put(k, v));
        if (e.contentType != null) r.headers.put("Content-Type", e.contentType);
        r.query.putAll(e.query);
        r.body = e.requestBody == null ? null : e.requestBody.deepCopy();
        if (mu != null) {
            mu.removeHeaders.forEach(h -> r.headers.keySet().removeIf(k -> k.equalsIgnoreCase(h)));
            r.headers.putAll(mu.setHeaders);
            mu.removeQuery.forEach(r.query.keySet()::remove);
            r.query.putAll(mu.setQuery);
            if (r.body instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
                mu.removeBodyFields.forEach(o::remove);
                mu.setBodyFields.forEach(o::set);
            }
            if (mu.bodyOverride != null) {
                r.body = null;
                r.bodyText = mu.bodyOverride;
            }
        }
        if (r.body != null && r.body.isTextual()) {
            r.bodyText = r.body.asText();
            r.body = null;
        }
        r.headers.entrySet().removeIf(h -> h.getValue() == null || h.getValue().isBlank() || h.getValue().equalsIgnoreCase("Bearer"));
        return r;
    }

    // ------------------------------------------------------------------ Karate

    public static byte[] karate(ApiModel m, RulePack rules, Options o) throws IOException {
        List<String> skipped = new ArrayList<>();
        List<TestCase> tests = select(m, o, skipped);
        Map<String, List<TestCase>> byModule = new LinkedHashMap<>();
        for (TestCase t : tests) {
            Endpoint e = m.endpoint(t.endpointId);
            byModule.computeIfAbsent(e.module == null ? "General" : e.module, k -> new ArrayList<>()).add(t);
        }
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder cfg = new StringBuilder("function fn() {\n  var config = { baseUrl: '" + js(baseUrl(m)) + "' };\n");
        plainVars(m).forEach((k, v) -> cfg.append("  config.").append(ident(k)).append(" = '").append(js(v)).append("';\n"));
        for (String env : envNames(m)) cfg.append("  config.").append(ident(env)).append(" = java.lang.System.getenv('").append(env).append("');\n");
        cfg.append("  if (!config.token) config.token = '';\n  return config;\n}\n");
        files.put("karate/karate-config.js", cfg.toString());
        int fileNo = 0;
        for (Map.Entry<String, List<TestCase>> mod : byModule.entrySet()) {
            StringBuilder f = new StringBuilder("Feature: " + mod.getKey().replace("\n", " ") + "\n\n  Background:\n    * url baseUrl\n\n");
            for (TestCase t : mod.getValue()) {
                Endpoint e = m.endpoint(t.endpointId);
                Req r = request(e, t);
                f.append("  @").append("POSITIVE".equals(t.category) ? "positive" : "negative").append(" @").append(tag(t.id)).append("\n");
                f.append("  Scenario: ").append((t.title == null ? t.id : t.title).replace("\n", " ")).append(" [").append(t.id).append("]\n");
                f.append("    Given url baseUrl + ").append(expr(r.path)).append("\n");
                for (Map.Entry<String, String> q : r.query.entrySet()) f.append("    And param ").append(q.getKey()).append(" = ").append(expr(q.getValue())).append("\n");
                for (Map.Entry<String, String> h : r.headers.entrySet()) f.append("    And header ").append(h.getKey()).append(" = ").append(expr(h.getValue())).append("\n");
                if (r.body != null) f.append("    And request ").append(karateJson(r.body)).append("\n");
                else if (r.bodyText != null) f.append("    And request ").append(expr(r.bodyText)).append("\n");
                f.append("    When method ").append(r.method.toLowerCase(Locale.ROOT)).append("\n");
                List<Integer> st = expected(t, rules);
                if (st.size() == 1) f.append("    Then status ").append(st.get(0)).append("\n");
                else f.append("    Then assert ").append(st).append(".indexOf(responseStatus) >= 0\n");
                for (ApiModel.Assertion a : t.expected.assertions) {
                    if ("jsonPathExists".equals(a.type) && a.path != null) f.append("    And match response.").append(karatePath(a.path)).append(" != null\n");
                    if ("maxLatencyMs".equals(a.type) && a.expected != null) f.append("    And assert responseTime < ").append(a.expected.asLong()).append("\n");
                }
                if ("POSITIVE".equals(t.category)) {
                    for (ApiModel.Extract x : e.extract) {
                        if (x.jsonPath == null || x.jsonPath.contains("find{") || x.jsonPath.toLowerCase(Locale.ROOT).startsWith("xpath:")) continue;
                        f.append("    * def ").append(ident(x.var)).append(" = ").append("response.").append(karatePath(x.jsonPath.split("\\|")[0])).append("\n");
                        break;
                    }
                }
                f.append("\n");
            }
            files.put("karate/features/" + String.format("%02d", ++fileNo) + "_" + slug(mod.getKey()) + ".feature", f.toString());
        }
        files.put("README.md", "# Karate tests exported from API Quality Studio\n\nProject: " + m.api.name + "\n\n"
                + "Run (Karate standalone jar): `java -jar karate.jar karate/features`\n\nSecrets are read from environment variables: " + (envNames(m).isEmpty() ? "none" : String.join(", ", envNames(m))) + ".\n\n"
                + "Note: Karate scenarios are independent. A value produced by one API (an id, a token) is set to its default in karate-config.js; run the sign-in/setup feature first or pass real values with -Dname=value.\n\n"
                + (skipped.isEmpty() ? "" : "Not exported: " + String.join(", ", skipped.subList(0, Math.min(20, skipped.size()))) + "\n"));
        return zip(files);
    }

    private static String karateJson(JsonNode n) {
        if (n.isObject()) {
            StringBuilder s = new StringBuilder("{ ");
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!first) s.append(", ");
                first = false;
                s.append('"').append(e.getKey().replace("\"", "\\\"")).append("\": ").append(karateJson(e.getValue()));
            }
            return s.append(" }").toString();
        }
        if (n.isArray()) {
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < n.size(); i++) s.append(i == 0 ? "" : ", ").append(karateJson(n.get(i)));
            return s.append("]").toString();
        }
        if (n.isTextual()) {
            String t = n.asText();
            Matcher mm = VAR.matcher(t);
            if (!mm.find()) return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            mm.reset();
            if (mm.matches()) return "\"#(" + varName(mm.group(1)) + ")\"";
            return "\"#(" + expr(t).replace("\"", "\\\"") + ")\"";
        }
        return n.toString();
    }

    private static String karatePath(String jp) {
        String p = jp.replaceFirst("^\\$\\.?", "");
        return p.isEmpty() ? "" : p;
    }

    /** "/pet/${petId}" -> '/pet/' + petId */
    private static String expr(String s) {
        if (s == null) return "''";
        Matcher mm = VAR.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (mm.find()) {
            String lit = s.substring(last, mm.start());
            if (!lit.isEmpty()) out.append(out.length() > 0 ? " + " : "").append("'").append(js(lit)).append("'");
            out.append(out.length() > 0 ? " + " : "").append(varName(mm.group(1)));
            last = mm.end();
            any = true;
        }
        String tail = s.substring(last);
        if (!tail.isEmpty() || !any) out.append(out.length() > 0 ? " + " : "").append("'").append(js(tail)).append("'");
        return out.toString();
    }

    private static String varName(String raw) {
        if (raw.startsWith("ENV:")) return ident(raw.substring(4));
        if (raw.startsWith("RANDOM:") || raw.startsWith("NOW") || raw.startsWith("DATE") || raw.startsWith("PAD") || raw.startsWith("TXN")) return "'" + js("1" + "0".repeat(5)) + "'";
        return ident(raw.replace("?", ""));
    }

    // ------------------------------------------------------------------ Rest Assured

    public static byte[] restAssured(ApiModel m, RulePack rules, Options o) throws IOException {
        List<String> skipped = new ArrayList<>();
        List<TestCase> tests = select(m, o, skipped);
        Map<String, List<TestCase>> byModule = new LinkedHashMap<>();
        for (TestCase t : tests) byModule.computeIfAbsent(m.endpoint(t.endpointId).module == null ? "General" : m.endpoint(t.endpointId).module, k -> new ArrayList<>()).add(t);
        Map<String, String> files = new LinkedHashMap<>();
        String pkg = "api.tests";
        StringBuilder base = new StringBuilder("package " + pkg + ";\n\nimport java.util.List;\nimport java.util.Map;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;\n\n"
                + "import io.restassured.RestAssured;\nimport io.restassured.response.Response;\nimport io.restassured.specification.RequestSpecification;\n\n"
                + "/** Shared settings. Values produced by one test (ids, tokens) are kept in VARS for the next tests. Secrets come from environment variables. */\npublic abstract class BaseApiTest {\n\n"
                + "    protected static final String BASE = \"" + java(baseUrl(m)) + "\";\n    protected static final Map<String, String> VARS = new ConcurrentHashMap<>();\n    private static final Pattern VAR = Pattern.compile(\"\\\\$\\\\{([^}]+)}\");\n\n    static {\n");
        plainVars(m).forEach((k, v) -> base.append("        VARS.put(\"").append(java(k)).append("\", \"").append(java(v)).append("\");\n"));
        for (String env : envNames(m)) base.append("        VARS.put(\"ENV:").append(env).append("\", System.getenv().getOrDefault(\"").append(env).append("\", \"\"));\n");
        base.append("    }\n\n    protected static String v(String s) {\n        Matcher m = VAR.matcher(s);\n        StringBuilder out = new StringBuilder();\n        while (m.find()) {\n"
                + "            String key = m.group(1).replace(\"?\", \"\");\n            m.appendReplacement(out, Matcher.quoteReplacement(VARS.getOrDefault(key, \"\")));\n        }\n        m.appendTail(out);\n        return out.toString();\n    }\n\n"
                + "    protected static RequestSpecification given() {\n        return RestAssured.given().relaxedHTTPSValidation().baseUri(BASE);\n    }\n\n"
                + "    protected static void store(String var, Response r, String path) {\n        Object val = r.jsonPath().get(path);\n        if (val != null) VARS.put(var, String.valueOf(val));\n    }\n\n"
                + "    protected static void expectStatus(Response r, List<Integer> allowed) {\n        if (!allowed.contains(r.statusCode())) throw new AssertionError(\"HTTP \" + r.statusCode() + \" expected \" + allowed + \": \" + r.asString());\n    }\n}\n");
        files.put("src/test/java/api/tests/BaseApiTest.java", base.toString());
        List<String> classes = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int priority = 0;
        for (Map.Entry<String, List<TestCase>> mod : byModule.entrySet()) {
            String cls = className(mod.getKey(), used);
            classes.add(pkg + "." + cls);
            StringBuilder c = new StringBuilder("package " + pkg + ";\n\nimport java.util.List;\n\nimport org.testng.annotations.Test;\n\nimport io.restassured.response.Response;\n\n/** " + mod.getKey().replace("*/", "") + " */\npublic class " + cls + " extends BaseApiTest {\n");
            Set<String> names = new HashSet<>();
            for (TestCase t : mod.getValue()) {
                Endpoint e = m.endpoint(t.endpointId);
                Req r = request(e, t);
                String name = "tc_" + t.id.replaceAll("[^A-Za-z0-9]", "_");
                while (!names.add(name)) name += "_";
                c.append("\n    @Test(priority = ").append(++priority).append(", description = \"").append(java(t.title == null ? t.id : t.title)).append("\")\n    public void ").append(name).append("() {\n");
                c.append("        Response r = given()\n");
                for (Map.Entry<String, String> h : r.headers.entrySet()) c.append("                .header(\"").append(java(h.getKey())).append("\", v(\"").append(java(h.getValue())).append("\"))\n");
                for (Map.Entry<String, String> q : r.query.entrySet()) c.append("                .queryParam(\"").append(java(q.getKey())).append("\", v(\"").append(java(q.getValue())).append("\"))\n");
                if (r.body != null) c.append("                .body(v(\"").append(java(r.body.toString())).append("\"))\n");
                else if (r.bodyText != null) c.append("                .body(v(\"").append(java(r.bodyText)).append("\"))\n");
                c.append("                .when().request(\"").append(r.method).append("\", v(\"").append(java(r.path)).append("\"));\n");
                c.append("        expectStatus(r, List.of(").append(String.join(", ", expected(t, rules).stream().map(String::valueOf).toList())).append("));\n");
                for (ApiModel.Assertion a : t.expected.assertions) {
                    if ("jsonPathExists".equals(a.type) && a.path != null) c.append("        if (r.jsonPath().get(\"").append(java(karatePath(a.path))).append("\") == null) throw new AssertionError(\"missing ").append(java(a.path)).append("\");\n");
                    if ("maxLatencyMs".equals(a.type) && a.expected != null) c.append("        if (r.time() > ").append(a.expected.asLong()).append(") throw new AssertionError(\"too slow: \" + r.time() + \" ms\");\n");
                }
                if ("POSITIVE".equals(t.category)) {
                    for (ApiModel.Extract x : e.extract) {
                        if (x.jsonPath == null || x.jsonPath.contains("find{") || x.jsonPath.toLowerCase(Locale.ROOT).startsWith("xpath:") || x.jsonPath.startsWith("$header")) continue;
                        c.append("        store(\"").append(java(x.var)).append("\", r, \"").append(java(karatePath(x.jsonPath.split("\\|")[0]))).append("\");\n");
                        break;
                    }
                }
                c.append("    }\n");
            }
            c.append("}\n");
            files.put("src/test/java/api/tests/" + cls + ".java", c.toString());
        }
        StringBuilder xml = new StringBuilder("<!DOCTYPE suite SYSTEM \"https://testng.org/testng-1.0.dtd\">\n<suite name=\"" + m.api.name.replace("\"", "") + "\" preserve-order=\"true\">\n  <test name=\"API tests\">\n    <classes>\n");
        classes.forEach(cn -> xml.append("      <class name=\"").append(cn).append("\"/>\n"));
        xml.append("    </classes>\n  </test>\n</suite>\n");
        files.put("testng.xml", xml.toString());
        files.put("pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>api</groupId>\n  <artifactId>exported-api-tests</artifactId>\n  <version>1.0.0</version>\n"
                + "  <properties><maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>\n  <dependencies>\n"
                + "    <dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><version>5.4.0</version><scope>test</scope></dependency>\n"
                + "    <dependency><groupId>org.testng</groupId><artifactId>testng</artifactId><version>7.10.2</version><scope>test</scope></dependency>\n  </dependencies>\n"
                + "  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version><configuration><suiteXmlFiles><suiteXmlFile>testng.xml</suiteXmlFile></suiteXmlFiles></configuration></plugin></plugins></build>\n</project>\n");
        files.put("README.md", "# Rest Assured + TestNG tests exported from API Quality Studio\n\nProject: " + m.api.name + "\n\nRun: `mvn test`\n\nTests run in the order of the Data flow (setup first), so ids and tokens produced by one test are available to the next (BaseApiTest.VARS).\n"
                + "Secrets are read from environment variables: " + (envNames(m).isEmpty() ? "none" : String.join(", ", envNames(m))) + ".\n\n" + (skipped.isEmpty() ? "" : "Not exported: " + String.join(", ", skipped.subList(0, Math.min(20, skipped.size()))) + "\n"));
        return zip(files);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String ident(String s) {
        String i = s.replaceAll("[^A-Za-z0-9_]", "_");
        return i.isEmpty() || Character.isDigit(i.charAt(0)) ? "v_" + i : i;
    }

    private static String slug(String s) {
        String x = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return x.isEmpty() ? "general" : x;
    }

    private static String tag(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String className(String module, Set<String> used) {
        StringBuilder sb = new StringBuilder();
        for (String part : module.replaceAll("[^A-Za-z0-9]+", " ").trim().split(" ")) if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        String n = sb.length() == 0 ? "General" : sb.toString();
        if (Character.isDigit(n.charAt(0))) n = "M" + n;
        n += "Tests";
        String c = n;
        int k = 1;
        while (!used.add(c)) c = n + (++k);
        return c;
    }

    private static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private static String java(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
