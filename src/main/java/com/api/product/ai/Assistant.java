package com.api.product.ai;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The built-in assistant. Deterministic intent routing + tools (run analysis, failure explanation, test generation,
 * BRD/flow mapping, knowledge retrieval). It answers from facts and cites sources; unknown things are said to be unknown.
 * A local model (Ollama) may rephrase the answer but can never add facts (numbers are checked).
 */
public final class Assistant {

    public static final class Source {
        public String title;
        public String source;
        public String snippet;
    }

    public static final class Action {
        public String type;
        public String label;
        public JsonNode data;
    }

    public static final class Reply {
        public String answer = "";
        public String intent = "";
        public String mode = "rules";
        public List<Source> sources = new ArrayList<>();
        public List<Action> actions = new ArrayList<>();
        public List<String> followUps = new ArrayList<>();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SYSTEM = "You are the assistant of an API test automation product. Rewrite the FACTS as a clear, "
            + "short answer for a tester. Use ONLY the facts given: never invent numbers, test ids, endpoints or causes. "
            + "Keep every id and number exactly. Use short markdown lists.";

    private final ProjectStore store;
    private final KnowledgeBase kb;
    private final AiMemory memory;
    private final OllamaClient llm;

    public Assistant(ProjectStore store, KnowledgeBase kb, AiMemory memory, OllamaClient llm) {
        this.store = store;
        this.kb = kb;
        this.memory = memory;
        this.llm = llm;
    }

    public OllamaClient llm() {
        return llm;
    }

    public Reply reply(String project, String message) {
        String q = message == null ? "" : message.trim();
        String lq = q.toLowerCase(Locale.ROOT);
        Reply r;
        try {
            ApiModel model = project != null && store.exists(project) ? store.load(project) : null;
            List<TestResult> results = model == null ? new ArrayList<>() : store.latestResults(project);
            if (q.isEmpty()) r = help();
            else if (isSecretFishing(lq)) r = refuse();
            else if (has(lq, "ollama", "local model", "which model", "hardware", "gpu", "my pc", "language model")) r = modelAdvice();
            else if (model == null && !isKnowledgeQuestion(lq)) r = noProject();
            else r = route(project, q, lq, model, results);
        } catch (Exception e) {
            r = new Reply();
            r.intent = "error";
            r.answer = "I could not complete that: " + e.getMessage();
        }
        return polish(q, r);
    }

    // ------------------------------------------------------------------ routing

    private Reply route(String project, String q, String lq, ApiModel model, List<TestResult> results) throws IOException {
        boolean wantsGen = has(lq, "suggest", "generate", "negative test", "security test", "more test", "add test", "create test",
                "negative and", "boundary test");
        TestResult byId = findResult(lq, results);
        if (byId != null && !wantsGen) return explainTest(byId, model);
        TestCase tcById = model == null ? null : findTest(lq, model);
        if (tcById != null && !has(lq, "generate", "suggest")) return describeTest(tcById, model);

        if (model != null) {
            // questions about product features are answered from the built-in help, before they can be mistaken for a request on the project
            if (has(lq, "ai engine", "plain english", "plain-english", "auto heal", "auto-heal", "karate", "rest assured", "test ideas") || lq.matches(".*\b(get|find|more)\b.*\bideas\b.*"))
                return knowledge(project, q);
            if (has(lq, "suggest", "generate", "negative test", "boundary", "security test", "more test", "missing test", "coverage", "add test", "create test"))
                return generate(project, lq, model);
            if (has(lq, "mandatory", "optional", "template", "sample brd", "how to write", "what is a brd", "need a brd", "do i need"))
                return knowledge(project, q);
            // product questions about features (not about this project's results): answered from the built-in help
            if (has(lq, "jira", "azure devops", "grpc", "websocket", "soap", "graphql", "load test", "load testing", "studio-cli", "jenkins", "pipeline", "spec drift",
                    "contract check", "data flow", "pass an id", "mock server", "licen", "team mode", "audit trail", "sign in work")
                    || lq.matches(".*\\bhow\\b.*business rules?\\b.*"))
                return knowledge(project, q);
            if (has(lq, "brd", "document", "requirement", "flow", "analy", "business rule", "map the", "read the"))
                return analyzeDocs(project, model);
            if (has(lq, "blocked")) return blockedAnalysis(model, results);
            if (has(lq, "fail", "defect", "bug", "finding", "problem"))
                return failureAnalysis(model, results);
            if (has(lq, "summar", "status", "result", "last run", "how did", "overall", "report", "pass rate", "how many"))
                return summary(project, model, results);
            if (has(lq, "endpoint", "api list", "what apis", "list api", "this project", "about the project", "project overview", "overview of")) return projectOverview(project, model, results);
        }
        return knowledge(project, q);
    }

    /** Instruction-override or credential-fishing requests. The assistant has no access to secrets and says so. */
    private static boolean isSecretFishing(String lq) {
        boolean override = lq.matches(".*\\b(ignore|forget|disregard)\\b.*\\b(instruction|previous|above|rules|prompt)\\b.*");
        boolean reveal = lq.matches(".*\\b(show|print|reveal|give|tell|dump|list|send|leak)\\b.*"
                + "\\b(password|passwords|secret|secrets|token|tokens|api key|apikey|credential|credentials|private key)\\b.*");
        boolean howTo = lq.matches(".*\\b(where|how)\\b.*") && !lq.matches(".*\\b(print|reveal|dump|leak)\\b.*");
        return override || (reveal && !howTo);
    }

    private Reply modelAdvice() {
        Reply r = new Reply();
        r.intent = "model-advice";
        HardwareProfile hw = llm.hardware();
        StringBuilder sb = new StringBuilder();
        sb.append("**This PC:** ").append(hw.summary()).append(".\n");
        sb.append("**Recommended local model:** `").append(hw.recommendedModel()).append("` (").append(hw.reason).append(").\n");
        List<String> inst = llm.installedModels();
        if (llm.available()) {
            sb.append("**Active now:** `").append(llm.model()).append("`. Installed: ").append(String.join(", ", inst)).append(".\n");
        } else if (!llm.enabled()) {
            sb.append("The local model is switched off (-Dai.llm=off); the built-in engine answers everything.\n");
        } else {
            sb.append("No local model is active. The built-in engine answers everything. To add one (free, offline afterwards): `winget install Ollama.Ollama` then `ollama pull ")
                    .append(hw.recommendedModel()).append("`.\n");
        }
        sb.append("The model only rephrases my answers; it cannot add facts. If you add RAM or a GPU later, I re-check the hardware and recommend (and use) a larger model automatically.");
        r.answer = sb.toString();
        return r;
    }

    private Reply refuse() {
        Reply r = new Reply();
        r.intent = "refused";
        r.answer = "I do not show, store or send passwords, tokens, keys or other credentials, and I do not change my rules because a message asks me to. "
                + "Secrets live in environment variables or the secret store and are masked in all evidence. "
                + "I can help with run results, failures, test suggestions and documents instead.";
        r.followUps.add("Summarise the latest run");
        return r;
    }

    private static boolean isKnowledgeQuestion(String lq) {
        return has(lq, "owasp", "how do", "how to", "what is", "explain", "help", "boundary", "idor", "csrf", "otp", "kyc");
    }

    private static boolean has(String lq, String... words) {
        for (String w : words) if (lq.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ tools

    private Reply summary(String project, ApiModel model, List<TestResult> results) {
        Reply r = new Reply();
        r.intent = "run-summary";
        if (results.isEmpty()) {
            r.answer = "Project **" + project + "** has " + model.endpoints.size() + " endpoints and " + model.testCases.size()
                    + " tests (" + lifecycleCounts(model) + "), but no model run yet. Start a run in Test Studio and ask me again.";
            r.followUps.add("Suggest negative tests for this project");
            return r;
        }
        Map<String, Long> c = results.stream().collect(Collectors.groupingBy(x -> x.outcome, TreeMap::new, Collectors.counting()));
        long pass = c.getOrDefault("PASS", 0L), fail = c.getOrDefault("FAIL", 0L), den = pass + fail;
        long avg = Math.round(results.stream().filter(x -> x.httpStatus != null).mapToLong(x -> x.latencyMs).average().orElse(0));
        StringBuilder sb = new StringBuilder();
        sb.append("**Latest run of ").append(project).append("**: ").append(results.size()).append(" tests, ").append(c).append(".\n");
        sb.append("- Pass rate: **").append(den == 0 ? "n/a" : String.format(Locale.ENGLISH, "%.1f%%", 100.0 * pass / den))
                .append("** (passed / (passed + failed) = ").append(pass).append(" / ").append(den).append("). Average response ").append(avg).append(" ms.\n");
        Map<String, Long> byModule = results.stream().filter(x -> "FAIL".equals(x.outcome))
                .collect(Collectors.groupingBy(x -> x.module == null ? "General" : x.module, Collectors.counting()));
        if (!byModule.isEmpty()) sb.append("- Modules with most failures: ").append(top(byModule, 3)).append(".\n");
        Map<String, Long> cats = results.stream().filter(x -> "FAIL".equals(x.outcome))
                .collect(Collectors.groupingBy(x -> String.valueOf(x.failureCategory), Collectors.counting()));
        if (!cats.isEmpty()) sb.append("- Failure types: ").append(top(cats, 4)).append(".\n");
        long blocked = c.getOrDefault("BLOCKED", 0L);
        if (blocked > 0) sb.append("- **").append(blocked).append(" blocked** tests were not sent; they hide real coverage. Ask me \"what is blocked\".\n");
        long high = results.stream().filter(x -> "FAIL".equals(x.outcome) && ("critical".equals(x.severity) || "high".equals(x.severity))).count();
        if (high > 0) sb.append("- ").append(high).append(" failures are high/critical severity; look at those first.\n");
        r.answer = sb.toString();
        r.followUps.add("Why did the failures happen?");
        r.followUps.add("What is blocked?");
        r.followUps.add("Suggest more negative and security tests");
        return r;
    }

    private Reply failureAnalysis(ApiModel model, List<TestResult> results) {
        Reply r = new Reply();
        r.intent = "failures";
        List<TestResult> fails = results.stream().filter(x -> "FAIL".equals(x.outcome)).collect(Collectors.toList());
        if (fails.isEmpty()) {
            r.answer = results.isEmpty() ? "There is no run yet." : "The latest run has **no failed tests**.";
            return r;
        }
        Map<String, List<TestResult>> g = new LinkedHashMap<>();
        fails.stream().sorted(Comparator.comparingInt((TestResult x) -> sev(x.severity))).forEach(x ->
                g.computeIfAbsent(String.valueOf(x.failureCategory), k -> new ArrayList<>()).add(x));
        StringBuilder sb = new StringBuilder("**" + fails.size() + " failed tests** in " + g.size() + " groups:\n");
        for (Map.Entry<String, List<TestResult>> e : g.entrySet()) {
            FailureExplainer.Explanation ex = FailureExplainer.explain(e.getValue().get(0), model);
            sb.append("- **").append(e.getKey()).append("** x").append(e.getValue().size()).append(" (e.g. ")
                    .append(e.getValue().stream().limit(4).map(x -> x.testCaseId).collect(Collectors.joining(", "))).append("): ")
                    .append(ex.cause).append(" Owner: ").append(ex.owner).append(".\n");
        }
        sb.append("\nAsk \"why did <test id> fail\" for the steps on one test.");
        r.answer = sb.toString();
        r.followUps.add("Why did " + fails.get(0).testCaseId + " fail?");
        return r;
    }

    private Reply blockedAnalysis(ApiModel model, List<TestResult> results) {
        Reply r = new Reply();
        r.intent = "blocked";
        List<TestResult> bl = results.stream().filter(x -> "BLOCKED".equals(x.outcome)).collect(Collectors.toList());
        if (bl.isEmpty()) {
            r.answer = results.isEmpty() ? "There is no run yet." : "Nothing is blocked in the latest run.";
            return r;
        }
        Map<String, List<TestResult>> g = new LinkedHashMap<>();
        for (TestResult x : bl) g.computeIfAbsent(blockKey(x.remark), k -> new ArrayList<>()).add(x);
        StringBuilder sb = new StringBuilder("**" + bl.size() + " blocked tests** (not sent, so not API failures):\n");
        for (Map.Entry<String, List<TestResult>> e : g.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size()).limit(8).collect(Collectors.toList())) {
            FailureExplainer.Explanation ex = FailureExplainer.explain(e.getValue().get(0), model);
            sb.append("- **").append(e.getKey()).append("** x").append(e.getValue().size()).append(": ")
                    .append(ex.nextSteps.isEmpty() ? ex.cause : ex.nextSteps.get(0)).append("\n");
        }
        r.answer = sb.toString();
        r.followUps.add("Summarise the latest run");
        return r;
    }

    private Reply explainTest(TestResult t, ApiModel model) {
        Reply r = new Reply();
        r.intent = "explain-test";
        FailureExplainer.Explanation e = FailureExplainer.explain(t, model);
        StringBuilder sb = new StringBuilder("**" + e.headline + "**\n\n");
        sb.append("- Outcome: ").append(t.outcome).append(t.httpStatus == null ? "" : ", HTTP " + t.httpStatus)
                .append(t.expectedStatus == null || t.expectedStatus.isEmpty() ? "" : " (expected " + t.expectedStatus + ")").append("\n");
        if (t.method != null) sb.append("- Call: `").append(t.method).append(' ').append(t.url).append("`\n");
        sb.append("- Why: ").append(e.cause).append("\n- Owner: ").append(e.owner)
                .append(e.severity == null || "-".equals(e.severity) ? "" : ", severity: " + e.severity).append("\n");
        if (!e.nextSteps.isEmpty()) {
            sb.append("\n**What to do**\n");
            for (String s : e.nextSteps) sb.append("1. ").append(s).append("\n");
        }
        r.answer = sb.toString();
        for (String ref : e.references) {
            Source s = new Source();
            s.title = ref;
            s.source = "knowledge base";
            r.sources.add(s);
        }
        return r;
    }

    private Reply describeTest(TestCase t, ApiModel model) {
        Reply r = new Reply();
        r.intent = "describe-test";
        Endpoint ep = model.endpoint(t.endpointId);
        r.answer = "**" + t.id + "** - " + t.title + "\n- Category: " + t.category + ", lifecycle: " + t.lifecycle + " (v" + t.version + ", " + t.origin + ")\n"
                + (ep == null ? "" : "- Endpoint: `" + ep.method + " " + ep.path + "`\n")
                + "- It has not run in the latest results, or no run exists yet.";
        return r;
    }

    private Reply projectOverview(String project, ApiModel model, List<TestResult> results) {
        Reply r = new Reply();
        r.intent = "project-overview";
        Map<String, Long> mod = model.endpoints.stream().collect(Collectors.groupingBy(
                e -> e.module == null || e.module.isBlank() ? "General" : e.module, TreeMap::new, Collectors.counting()));
        r.answer = "**" + project + "**: " + model.endpoints.size() + " endpoints in " + mod.size() + " modules, " + model.testCases.size()
                + " tests (" + lifecycleCounts(model) + ").\n- Base URL: `" + (model.environments.isEmpty() ? "-" : model.environments.get(0).baseUrl) + "`\n- Modules: "
                + top(mod, 8) + "\n- Source: " + model.api.source.type + (model.api.source.warnings.isEmpty() ? "" : "; " + model.api.source.warnings.size() + " import warnings");
        return r;
    }

    private Reply generate(String project, String lq, ApiModel model) {
        Reply r = new Reply();
        r.intent = "generate-tests";
        TestGenerator.Options o = new TestGenerator.Options();
        boolean onlySecurity = has(lq, "security") && !has(lq, "negative", "validation", "field", "boundary", "invalid data");
        boolean onlyData = has(lq, "boundary", "validation", "invalid data", "data negative", "field") && !has(lq, "security");
        if (onlySecurity) o.data = false;
        if (onlyData) o.security = false;
        o.includeWriteData = has(lq, "include write", "including write", "write api", "create/update", "including create", "also create", "all endpoints including");
        o.allMethods = has(lq, "all methods", "every method");
        o.largePayload = !has(lq, "no large", "without large", "skip large");
        Set<String> only = new HashSet<>();
        for (Endpoint ep : model.endpoints) {
            String id = ep.id == null ? "" : ep.id.toLowerCase(Locale.ROOT);
            String name = ep.name == null ? "" : ep.name.toLowerCase(Locale.ROOT);
            if ((!id.isEmpty() && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(id) + "(?![a-z0-9_])").matcher(lq).find())
                    || (name.length() > 4 && lq.contains(name))) only.add(ep.id);
        }
        AiMemory.Data mem = memory.load(project);
        TestGenerator.Result g = TestGenerator.generateAll(model, o, memory, mem, only.isEmpty() ? null : only);
        List<TestCase> all = g.tests;
        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) {
            sb.append("I found **no new candidates**: the selected endpoints already have the tests I would generate, or the suggestions were dismissed earlier.");
        } else {
            Map<String, Long> byCat = all.stream().collect(Collectors.groupingBy(t -> t.category, TreeMap::new, Collectors.counting()));
            sb.append("From the positive tests and test data in this project I prepared **").append(all.size()).append(" DRAFT tests** for ")
                    .append(g.endpoints).append(" endpoint").append(g.endpoints == 1 ? "" : "s").append(":\n");
            for (Map.Entry<String, Long> e : byCat.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            sb.append("\nEach one changes a single thing of its positive request (see its tags: change and expect), so it can only fail for that reason. Examples:\n");
            all.stream().limit(4).forEach(t -> sb.append("- `").append(t.id).append("` ").append(t.title).append("\n"));
        }
        if (!g.skipped.isEmpty()) {
            sb.append("\n**Left out on purpose:**\n");
            for (Map.Entry<String, Integer> e : g.skipped.entrySet()) sb.append("- ").append(e.getValue()).append(" x ").append(e.getKey()).append("\n");
        }
        sb.append("\nExpected results come from the rule pack");
        List<String> learned = all.stream().map(t -> t.category).distinct().filter(c -> !memory.learnedStatus(mem, c).isEmpty()).collect(Collectors.toList());
        if (!learned.isEmpty()) sb.append(" and from what your team taught this project for ").append(learned);
        sb.append(". Login endpoints get one wrong-password attempt plus a verify-login, never repeated guessing. ")
                .append("Not generated: other-user (BOLA) tests need a second test user, and business-rule tests need a BRD or your rules.");
        r.answer = sb.toString();
        if (!all.isEmpty()) {
            Action a1 = new Action();
            a1.type = "addTests";
            a1.label = "Add " + all.size() + " draft tests to the project";
            a1.data = M.valueToTree(all);
            r.actions.add(a1);
        }
        if (!o.includeWriteData && g.skipped.keySet().stream().anyMatch(k -> k.startsWith("write API"))) {
            r.followUps.add("Also include write APIs (create/update/delete) in the negative tests");
        }
        r.followUps.add("Analyse the BRD for business rules");
        return r;
    }

    private Reply analyzeDocs(String project, ApiModel model) throws IOException {
        Reply r = new Reply();
        r.intent = "analyze-docs";
        int files = kb.reloadProject(project);
        Path docs = store.dir(project).resolve("docs");
        StringBuilder text = new StringBuilder();
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(docs)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(docs)) {
                for (Path p : ds) {
                    String t = KnowledgeBase.readText(p);
                    if (t != null) {
                        text.append(t).append("\n");
                        names.add(p.getFileName().toString());
                    }
                }
            }
        }
        if (text.length() == 0) {
            r.answer = "I found no BRD or flow document for **" + project + "**. Drop a .docx, .pdf, .txt or .md file into `projects/" + project
                    + "/docs/` (or upload it in Test Studio) and ask again. I read it locally; nothing leaves this PC.";
            return r;
        }
        FlowAnalyzer.Analysis a = FlowAnalyzer.analyze(text.toString(), model);
        StringBuilder sb = new StringBuilder("I read " + names + " (" + files + " file" + (files == 1 ? "" : "s") + "). ");
        sb.append("Found **").append(a.steps.size()).append(" steps**, **").append(a.matched).append("** matched to endpoints of this project.\n\n");
        for (FlowAnalyzer.Step s : a.steps.stream().limit(15).collect(Collectors.toList())) {
            sb.append(s.no).append(". ").append(trim(s.text, 90));
            if (s.endpointId != null) sb.append(" -> `").append(s.endpointId).append("` (").append(Math.round(s.confidence * 100)).append("%)");
            else sb.append(" -> *no matching endpoint*");
            if (s.needsPrevious) sb.append(" [uses a value from an earlier step]");
            sb.append("\n");
        }
        if (!a.unmatchedPaths.isEmpty()) {
            sb.append("\n**Mentioned in the document but not in the project** (missing endpoint or outdated document): ")
                    .append(a.unmatchedPaths.stream().limit(8).collect(Collectors.joining(", "))).append("\n");
        }
        if (!a.uncoveredEndpoints.isEmpty() && !a.steps.isEmpty()) {
            sb.append("\n**Endpoints never mentioned in the document** (").append(a.uncoveredEndpoints.size()).append("): ")
                    .append(a.uncoveredEndpoints.stream().limit(8).collect(Collectors.joining(", "))).append("\n");
        }
        if (!a.ruleCandidates.isEmpty()) {
            sb.append("\n**Business-rule sentences to review** (").append(a.ruleCandidates.size()).append("):\n");
            a.ruleCandidates.stream().limit(6).forEach(x -> sb.append("- ").append(trim(x, 140)).append("\n"));
            Files.writeString(store.dir(project).resolve("rule-candidates.txt"), String.join("\n", a.ruleCandidates));
            sb.append("The full list is saved in `projects/").append(project).append("/rule-candidates.txt`. A tester turns the ones that matter into assertions; I do not guess business rules.");
        }
        r.answer = sb.toString();
        r.followUps.add("Suggest negative tests for this project");
        return r;
    }

    private Reply knowledge(String project, String q) {
        Reply r = new Reply();
        r.intent = "knowledge";
        List<Bm25Index.Hit> hits = new ArrayList<>(kb.search(q, project, 6));
        boolean how = q.toLowerCase(Locale.ROOT).matches(".*\\b(how do i|how to|how can i|where do i)\\b.*");
        if (how) hits.sort((x, y) -> Double.compare(boost(y), boost(x)));
        if (hits.size() > 3) hits = new ArrayList<>(hits.subList(0, 3));
        if (hits.isEmpty() || hits.get(0).score < 1.0) {
            r.answer = "I am not sure what you mean, so I will not guess. Tell me which of these you need, or ask in other words (naming the page or button helps):\n"
                    + "- **Run my tests**: where to click, in what order\n"
                    + "- **Credentials / token / OTP / locked account**\n"
                    + "- **Import**: what happens after importing my API\n"
                    + "- **Live console and reports**: what each number means, where the files are\n"
                    + "- **Start fresh**: clear old records\n"
                    + "- **A failed or blocked test**: give me its id, for example TC006";
            r.followUps.add("How do I run my tests step by step?");
            r.followUps.add("Why do I get Token Not Available?");
            r.followUps.add("What happens after I import my API?");
            r.followUps.add("What does the live console show?");
            r.followUps.add("How do I clear old records and start fresh?");
            r.followUps.add("Summarise the latest run");
            return r;
        }
        StringBuilder sb = new StringBuilder();
        Bm25Index.Doc d = hits.get(0).doc;
        sb.append("**").append(d.title).append("**\n").append(trim(d.text.replaceAll("(?m)^\\s*$\\n", ""), 1100));
        r.answer = sb.toString();
        for (Bm25Index.Hit h : hits) {
            Source s = new Source();
            s.title = h.doc.title;
            s.source = h.doc.source;
            s.snippet = trim(h.doc.text, 160);
            r.sources.add(s);
        }
        return r;
    }

    private static double boost(Bm25Index.Hit h) {
        return h.score * (("product-help.md".equals(h.doc.source)) ? 1.8 : 1.0);
    }

    private Reply help() {
        Reply r = new Reply();
        r.intent = "help";
        r.answer = "I am the built-in assistant. I work offline on your PC and answer from your project data and my knowledge base.\n"
                + "- \"Summarise the latest run\" / \"what is blocked\" / \"why did TC006 fail\"\n"
                + "- \"Suggest negative and security tests\" (I prepare DRAFT tests you approve)\n"
                + "- \"Analyse the BRD\" (put it in the project docs folder)\n"
                + "- \"Explain OWASP API2\", \"how do I add an auth profile\"";
        r.followUps.add("Summarise the latest run");
        r.followUps.add("Suggest negative and security tests");
        return r;
    }

    private Reply noProject() {
        Reply r = new Reply();
        r.intent = "no-project";
        r.answer = "Select a project first (or import one), then ask again. I can still answer general questions such as OWASP risks and testing techniques.";
        return r;
    }

    // ------------------------------------------------------------------ optional model polish

    private Reply polish(String q, Reply r) {
        if (llm == null || llm.settings() == null || !llm.settings().polish || !llm.available() || r.answer.isBlank() || "error".equals(r.intent)) return r;
        String facts = r.answer + (r.sources.isEmpty() ? "" : "\nSOURCES: " + r.sources.stream().map(s -> s.title).collect(Collectors.joining("; ")));
        String out = llm.chat(SYSTEM, "QUESTION: " + q + "\n\nFACTS:\n" + facts, 90);
        if (out == null) return r;
        // guard: a rewritten answer may not contain numbers that are not in the facts
        Set<String> allowed = numbers(facts + " " + q);
        for (String n : numbers(out)) {
            if (!allowed.contains(n)) return r;
        }
        r.answer = out;
        r.mode = "rules+llm(" + llm.model() + ")";
        return r;
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private TestResult findResult(String lq, List<TestResult> results) {
        for (TestResult t : results) {
            if (t.testCaseId != null && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(t.testCaseId.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])").matcher(lq).find()) return t;
        }
        return null;
    }

    private TestCase findTest(String lq, ApiModel model) {
        for (TestCase t : model.testCases) {
            if (t.id != null && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(t.id.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])").matcher(lq).find()) return t;
        }
        return null;
    }

    private static String lifecycleCounts(ApiModel m) {
        return m.testCases.stream().collect(Collectors.groupingBy(t -> t.lifecycle, TreeMap::new, Collectors.counting())).toString();
    }

    private static String top(Map<String, Long> m, int n) {
        return m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(n)
                .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
    }

    private static int sev(String s) {
        if ("critical".equals(s)) return 0;
        if ("high".equals(s)) return 1;
        if ("medium".equals(s)) return 2;
        return 3;
    }

    private static String blockKey(String remark) {
        if (remark == null) return "unknown";
        Matcher m = Pattern.compile("Variable not available: \\$\\{([^}]*)}").matcher(remark);
        if (m.find()) return "missing value " + m.group(1);
        if (remark.startsWith("Credentials")) return "credentials not configured";
        if (remark.startsWith("Waiting for OTP")) return "waiting for OTP";
        if (remark.startsWith("Endpoint answered")) return "auth not configured";
        return trim(remark, 50);
    }

    private static String trim(String s, int max) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "...";
    }
}
