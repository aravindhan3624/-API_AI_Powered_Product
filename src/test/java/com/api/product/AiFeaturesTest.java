package com.api.product;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.api.product.ai.AiSettings;
import com.api.product.ai.OllamaClient;
import com.api.product.ai.PlainExplainer;
import com.api.product.ai.TestIdeas;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.flow.AutoChain;
import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.importer.SpecDrift;
import com.api.product.core.importer.SpecHeal;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.report.CodeExport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** AI-assisted features, offline: a stub stands in for the language model, so nothing real is loaded or called. */
public class AiFeaturesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer stub;
    private final AtomicReference<String> lastChat = new AtomicReference<>("");
    private volatile String chatAnswer = "{\"ideas\":[]}";

    @BeforeClass
    public void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/api/tags", ex -> reply(ex, "{\"models\":[{\"name\":\"qwen2.5:3b\"}]}"));
        stub.createContext("/api/chat", ex -> {
            lastChat.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                reply(ex, M.createObjectNode().set("message", M.createObjectNode().put("role", "assistant").put("content", chatAnswer)).toString());
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        stub.start();
    }

    @AfterClass
    public void stopStub() {
        stub.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private OllamaClient client(boolean enabled) {
        AiSettings s = new AiSettings();
        s.enabled = enabled;
        s.url = "http://localhost:" + stub.getAddress().getPort();
        OllamaClient c = new OllamaClient(s.url, "auto");
        c.apply(s);
        return c;
    }

    private ApiModel registration() throws Exception {
        ApiModel m = new ApiModel();
        Endpoint e = new Endpoint();
        e.id = "reg";
        e.method = "POST";
        e.path = "/register";
        e.name = "Register";
        e.requestBody = M.readTree("{\"email\":\"qa@example.test\",\"mobile\":\"9876543210\",\"amount\":1500,\"password\":\"x\",\"city\":\"Pune\"}");
        m.endpoints.add(e);
        ApiModel.TestCase t = new ApiModel.TestCase();
        t.id = "TC_reg_POSITIVE";
        t.endpointId = "reg";
        m.testCases.add(t);
        return m;
    }

    @Test
    public void builtInIdeasUseFieldMeaningAndSkipSecrets() throws Exception {
        ApiModel m = registration();
        List<TestIdeas.Idea> ideas = TestIdeas.builtIn(m, m.endpoint("reg"));
        Assert.assertTrue(ideas.stream().anyMatch(i -> i.field.equals("email") && i.title.contains("without @")));
        Assert.assertTrue(ideas.stream().anyMatch(i -> i.field.equals("mobile")));
        Assert.assertTrue(ideas.stream().anyMatch(i -> i.field.equals("amount") && i.value.asInt() == -1));
        Assert.assertTrue(ideas.stream().noneMatch(i -> i.field.equals("password") || i.field.equals("city")));
        ApiModel.TestCase t = TestIdeas.toTest(m, ideas.get(0));
        Assert.assertEquals(t.lifecycle, "DRAFT");
        Assert.assertTrue(t.tags.contains("smart-idea"));
        m.testCases.add(t);
        List<TestIdeas.Idea> again = TestIdeas.builtIn(m, m.endpoint("reg"));
        TestIdeas.markDuplicates(m, again);
        Assert.assertTrue(again.stream().anyMatch(i -> i.duplicate), "an idea that already became a test is marked");
    }

    @Test
    public void modelIdeasAreValidatedAndGentleSettingsAreSent() throws Exception {
        ApiModel m = registration();
        chatAnswer = "{\"ideas\":[{\"field\":\"email\",\"title\":\"E-mail with unicode\",\"category\":\"INVALID_CODE\",\"value\":\"\\u00e9@x\",\"reason\":\"non-ascii\"},"
                + "{\"field\":\"ghost\",\"title\":\"unknown field\",\"category\":\"BOUNDARY\",\"value\":1},"
                + "{\"field\":\"amount\",\"title\":\"bad category\",\"category\":\"EVIL\",\"value\":1},"
                + "{\"field\":\"amount\",\"title\":\"object value\",\"category\":\"BOUNDARY\",\"value\":{\"a\":1}}]}";
        TestIdeas.AiResult r = TestIdeas.viaAi(m, m.endpoint("reg"), "", client(true));
        Assert.assertTrue(r.used);
        Assert.assertEquals(r.ideas.size(), 1, r.message);
        Assert.assertTrue(r.ideas.get(0).source.startsWith("AI"));
        Assert.assertTrue(r.message.contains("3 dropped"), r.message);
        String sent = lastChat.get();
        Assert.assertTrue(sent.contains("\"num_ctx\":2048"), "gentle mode keeps the context small: " + sent);
        Assert.assertTrue(sent.contains("\"format\":\"json\""));
        Assert.assertFalse(sent.contains("\"x\""), "the password value never goes to the model");

        TestIdeas.AiResult off = TestIdeas.viaAi(m, m.endpoint("reg"), "", client(false));
        Assert.assertFalse(off.used);
        Assert.assertTrue(off.message.contains("off"));
        Assert.assertEquals(TestIdeas.viaAi(m, m.endpoint("reg"), "", new OllamaClient("http://localhost:1", "none")).used, false);
    }

    @Test
    public void explainerGivesPlainEnglishWithAndWithoutModel() {
        TestResult r = new TestResult();
        r.testCaseId = "TC9";
        r.outcome = "FAIL";
        r.category = "POSITIVE";
        r.httpStatus = 422;
        r.responseEvidence = "{\"statusCode\":422,\"message\":\"Token Not Available\"}";
        r.remark = "Valid request returned 422";
        r.method = "POST";
        r.url = "https://x.test/a";
        PlainExplainer.Explanation e = PlainExplainer.explain(r);
        Assert.assertTrue(e.headline.contains("token"), e.headline);
        Assert.assertTrue(e.fix.contains("login") && e.fix.contains("credentials"));
        chatAnswer = "The request had no token. Log in first.";
        PlainExplainer.addAi(e, r, client(true));
        Assert.assertEquals(e.aiText, "The request had no token. Log in first.");
        PlainExplainer.Explanation off = PlainExplainer.explain(r);
        PlainExplainer.addAi(off, r, client(false));
        Assert.assertTrue(off.aiText.isEmpty() && off.aiNote.contains("off"));
    }

    @Test
    public void healProposesAndAppliesOnlyApprovedRepairs() throws Exception {
        Path spec = Paths.get("sample_apis/specs/Petstore_v3_openapi.json");
        ApiModel current = OpenApiModelImporter.importSpec(spec, "ACTIVE");
        ApiModel latest = OpenApiModelImporter.importSpec(spec, "DRAFT");
        Endpoint nw = latest.endpoint("addPet");
        ((com.fasterxml.jackson.databind.node.ObjectNode) nw.requestBody).put("brandNewField", "x");
        ((com.fasterxml.jackson.databind.node.ObjectNode) nw.requestBody).remove("status");
        nw.specHash = "changed";
        Assert.assertTrue(SpecDrift.compare(current, latest).changed.stream().anyMatch(i -> i.id.equals("addPet")));
        List<SpecHeal.Repair> repairs = SpecHeal.propose(current, latest);
        Assert.assertTrue(repairs.stream().anyMatch(r -> r.kind.equals("ADD_FIELD") && r.field.equals("brandNewField")));
        Assert.assertTrue(repairs.stream().anyMatch(r -> r.kind.equals("REMOVE_FIELD") && r.field.equals("status")));
        String keep = current.endpoint("addPet").requestBody.path("name").asText();
        Set<String> approve = new HashSet<>();
        repairs.stream().filter(r -> r.kind.equals("ADD_FIELD")).forEach(r -> approve.add(r.id));
        Assert.assertEquals(SpecHeal.apply(current, repairs, approve), 1);
        Assert.assertTrue(current.endpoint("addPet").requestBody.has("brandNewField"));
        Assert.assertTrue(current.endpoint("addPet").requestBody.has("status"), "a repair that was not approved is not applied");
        Assert.assertEquals(current.endpoint("addPet").requestBody.path("name").asText(), keep, "existing values are kept");
    }

    @Test
    public void exportedRestAssuredProjectCompilesAndKarateLooksRight() throws Exception {
        ApiModel m = OpenApiModelImporter.importSpec(Paths.get("sample_apis/specs/Petstore_v3_openapi.json"), "ACTIVE");
        m.links.addAll(AutoChain.detect(m));
        AutoChain.apply(m);
        RulePack rules = RulePack.classpath("rules/api-test-rules.properties");
        CodeExport.Options o = new CodeExport.Options();

        Path dir = Files.createTempDirectory("export-ra");
        List<String> javaFiles = new ArrayList<>();
        for (ZipEntryData z : unzip(CodeExport.restAssured(m, rules, o))) {
            Path p = dir.resolve(z.name);
            Files.createDirectories(p.getParent());
            Files.write(p, z.data);
            if (z.name.endsWith(".java")) javaFiles.add(p.toString());
        }
        Assert.assertTrue(javaFiles.size() >= 3, "classes generated: " + javaFiles);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        Assert.assertNotNull(jc, "needs a JDK");
        Path out = dir.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", System.getProperty("java.class.path")));
        args.addAll(javaFiles);
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        Assert.assertEquals(jc.run(null, null, err, args.toArray(new String[0])), 0, "the generated tests must compile:\n" + err);

        String karate = "";
        for (ZipEntryData z : unzip(CodeExport.karate(m, rules, o))) if (z.name.endsWith(".feature")) karate += new String(z.data, StandardCharsets.UTF_8);
        Assert.assertTrue(karate.contains("Given url baseUrl + '/pet/' + petId"), karate.substring(0, Math.min(600, karate.length())));
        Assert.assertTrue(karate.contains("Then status 200") || karate.contains("Then assert"));
        Assert.assertFalse(karate.contains("special-key"), "no secrets in exported code");
    }

    private static final class ZipEntryData {
        String name;
        byte[] data;
    }

    private static List<ZipEntryData> unzip(byte[] zip) throws IOException {
        List<ZipEntryData> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                ZipEntryData d = new ZipEntryData();
                d.name = e.getName();
                d.data = z.readAllBytes();
                out.add(d);
            }
        }
        return out;
    }

    static File unusedFile() {
        return new File(".");
    }
}
