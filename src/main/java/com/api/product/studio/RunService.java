package com.api.product.studio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.api.product.core.engine.ModelRunner;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.api.product.core.report.ReportGenerator;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Runs a project in the background (one thread per run) and writes the data file, HTML and Excel report. */
public final class RunService {

    public static final class Run {
        public String id;
        public String project;
        public volatile String state = "RUNNING";   // RUNNING | DONE | CANCELLED | ERROR
        public volatile int total;
        public volatile int done;
        public volatile String current = "";
        public final Map<String, Integer> counts = new ConcurrentHashMap<>();
        public volatile String htmlUrl;
        public volatile String excelUrl;
        public volatile String indexUrl;
        public volatile String pdfUrl;
        public volatile String vaptUrl;
        public volatile String vaptPdfUrl;
        public volatile String error;
        public volatile long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public volatile String baseUrl = "";
        public volatile String note = "";
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final List<TestResult> live = java.util.Collections.synchronizedList(new ArrayList<>());
        /** Every test of this run in execution order, with the model it belongs to (live console tiles). */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final List<ApiModel.TestCase> plan = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonIgnore
        public volatile ApiModel model;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final List<Map<String, Object>> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile String envName = "";
        public volatile String currentTestId;
        public volatile boolean paused;
        public volatile long pausedAt;
        public volatile long pausedTotalMs;
        public volatile String consoleReportUrl;
        final AtomicBoolean cancel = new AtomicBoolean();

        public void event(String level, String tc, String text) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("time", System.currentTimeMillis());
            e.put("level", level);
            e.put("tc", tc);
            e.put("text", text);
            events.add(e);
        }
    }

    /** Pause between two tests / resume (live console button). */
    public void setPaused(String id, boolean pause) {
        Run r = runs.get(id);
        if (r == null || !"RUNNING".equals(r.state) || r.paused == pause) return;
        if (pause) {
            r.pausedAt = System.currentTimeMillis();
            r.paused = true;
            r.event("WARN", null, "Paused by tester - the API that is running finishes, the next one waits");
        } else {
            r.pausedTotalMs += System.currentTimeMillis() - r.pausedAt;
            r.paused = false;
            r.event("INFO", null, "Resumed");
        }
    }

    /** Slim rows for the live console, starting at index {@code from}. Evidence is only sent on request (see {@link #full}). */
    public static com.fasterxml.jackson.databind.node.ObjectNode events(Run run, int from) {
        com.fasterxml.jackson.databind.node.ObjectNode n = M.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode a = n.putArray("events");
        int next;
        synchronized (run.live) {
            next = run.live.size();
            for (int i = Math.max(0, from); i < next; i++) {
                TestResult r = run.live.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode e = a.addObject();
                e.put("i", i);
                e.put("id", r.testCaseId);
                e.put("module", r.module);
                e.put("api", (r.method == null ? "" : r.method + " ") + (r.apiName == null ? "" : r.apiName));
                e.put("category", r.category);
                e.put("outcome", r.outcome);
                if (r.httpStatus != null) e.put("http", r.httpStatus);
                e.put("ms", r.latencyMs);
                e.put("expected", r.expectedStatus == null ? "" : r.expectedStatus.toString());
                String rm = r.remark == null ? "" : r.remark;
                e.put("remark", rm.length() > 220 ? rm.substring(0, 220) + "..." : rm);
                e.put("title", r.title);
            }
        }
        n.put("next", next);
        n.put("state", run.state);
        n.put("total", run.total);
        n.put("done", run.done);
        n.put("current", run.current);
        n.put("startedAt", run.startedAt);
        n.put("finishedAt", run.finishedAt);
        n.put("baseUrl", run.baseUrl);
        n.put("project", run.project);
        n.put("note", run.note);
        com.api.product.core.engine.HumanGate.Pending otp = com.api.product.core.engine.HumanGate.pending();
        if (otp != null && otp.project.equals(run.project)) n.put("otp", otp.testId + " " + otp.api);
        if (run.error != null) n.put("error", run.error);
        com.fasterxml.jackson.databind.node.ObjectNode c = n.putObject("counts");
        run.counts.forEach(c::put);
        if (run.htmlUrl != null) {
            n.put("htmlUrl", run.htmlUrl);
            n.put("excelUrl", run.excelUrl);
            n.put("indexUrl", run.indexUrl);
            n.put("pdfUrl", run.pdfUrl);
            n.put("vaptUrl", run.vaptUrl);
            n.put("vaptPdfUrl", run.vaptPdfUrl);
        }
        return n;
    }

    public static TestResult full(Run run, String testId) {
        synchronized (run.live) {
            for (TestResult r : run.live) if (testId.equals(r.testCaseId)) return r;
        }
        return null;
    }

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ProjectStore store;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public RunService(ProjectStore store) {
        this.store = store;
    }

    /** Runs that are running, or started within the last {@code withinMs}: the page shows them in its run indicator. */
    public List<Run> recent(long withinMs) {
        long now = System.currentTimeMillis();
        List<Run> out = new ArrayList<>();
        for (Run r : runs.values()) if ("RUNNING".equals(r.state) || now - Math.max(r.startedAt, r.finishedAt) < withinMs) out.add(r);
        out.sort(java.util.Comparator.comparingLong((Run r) -> r.startedAt).reversed());
        return out;
    }

    /** The most recently started run of any project, or null. */
    public Run latest() {
        return runs.values().stream().max(java.util.Comparator.comparingLong(r -> r.startedAt)).orElse(null);
    }

    public Run get(String id) {
        return runs.get(id);
    }

    public Run activeFor(String project) {
        return runs.values().stream().filter(r -> r.project.equals(project) && "RUNNING".equals(r.state)).findFirst().orElse(null);
    }

    public Run start(String project, Set<String> ids, boolean includeDraft) throws Exception {
        if (activeFor(project) != null) throw new IllegalStateException("A run is already in progress for " + project);
        ApiModel model = store.load(project);
        Run run = new Run();
        run.id = "R" + System.currentTimeMillis();
        run.project = project;
        int total = 0;
        for (ApiModel.TestCase t : model.testCases) {
            if (ids != null && !ids.contains(t.id)) continue;
            total++;
        }
        run.total = total;
        run.model = model;
        for (ApiModel.TestCase t : model.testCases) if (ids == null || ids.contains(t.id)) run.plan.add(t);
        if (!model.links.isEmpty()) run.plan.sort(java.util.Comparator.comparingInt(t -> com.api.product.core.flow.AutoChain.rank(model, t)));
        runs.put(run.id, run);
        Thread th = new Thread(() -> execute(run, model, ids, includeDraft), "run-" + run.id);
        th.setDaemon(true);
        th.start();
        return run;
    }

    public void cancel(String id) {
        Run r = runs.get(id);
        if (r != null) r.cancel.set(true);
    }

    private void execute(Run run, ApiModel model, Set<String> ids, boolean includeDraft) {
        try {
            Path override = store.dir(run.project).resolve("rules.properties");
            RulePack rules = Files.exists(override) ? RulePack.load(override) : RulePack.classpath("rules/api-test-rules.properties");
            ModelRunner runner = new ModelRunner(model, rules, null, includeDraft);
            run.baseUrl = runner.baseUrl();
            run.envName = runner.environmentName();
            run.event("INFO", null, "Run started: " + run.plan.size() + " tests on " + run.baseUrl + " (" + run.envName + ")");
            List<TestResult> results = runner.run(ids, r -> {
                run.live.add(r);
                run.done++;
                run.counts.merge(r.outcome, 1, Integer::sum);
                run.current = r.testCaseId + " " + r.outcome;
                String st = ConsoleState.status(r);
                String where = (r.module == null ? "" : r.module + " > ") + (r.apiName == null ? "" : r.apiName);
                String label = "POSITIVE".equals(r.category) ? "" : " | " + (r.title == null ? r.category : r.title.replaceFirst("^.* - ", ""));
                if ("PASS".equals(st)) run.event("PASS", r.testCaseId, "PASS  " + where + label + "  \u00b7 HTTP " + r.httpStatus + (r.remark == null || r.remark.isEmpty() ? "" : "  \u00b7 " + r.remark));
                else if ("FAIL".equals(st)) run.event("FAIL", r.testCaseId, "FAIL  " + where + label + "  \u00b7 HTTP " + r.httpStatus + "  \u00b7 " + (r.remark == null ? "" : r.remark));
                else run.event("WARN", r.testCaseId, ("NA".equals(st) ? "NOT EXECUTABLE  " : "SKIP  ") + where + label + "  \u00b7 " + (r.remark == null ? "" : r.remark));
            }, run.cancel::get, tc -> {
                run.currentTestId = tc.id;
                ApiModel.Endpoint ep = model.endpoint(tc.endpointId);
                run.event("STEP", tc.id, (ep == null ? "" : ep.method + " " + ep.path) + (tc.title == null ? "" : "  \u00b7 " + tc.title));
            }, () -> run.paused);
            run.currentTestId = null;

            Path data = Path.of("reports", "model-runs", model.api.name + "_" + runner.runId());
            Files.createDirectories(data);
            M.writeValue(data.resolve("results.json").toFile(), results);

            ReportGenerator.Meta meta = new ReportGenerator.Meta();
            meta.project = model.api.name;
            meta.environment = runner.environmentName();
            meta.baseUrl = runner.baseUrl();
            meta.source = model.api.source.type + (model.api.source.ref == null ? "" : " - " + model.api.source.ref);
            meta.runId = runner.runId();
            meta.ruleVersion = runner.ruleVersion();
            ReportGenerator.Output out = ReportGenerator.write(ReportGenerator.defaultRoot(), meta, new ArrayList<>(results), null);
            String dir = ProjectStore.safe(model.api.name);
            run.htmlUrl = "/files/" + dir + "/" + out.html.getFileName();
            run.excelUrl = "/files/" + dir + "/" + out.excel.getFileName();
            run.indexUrl = "/files/" + dir + "/index.html";
            run.pdfUrl = "/files/" + dir + "/" + out.pdf.getFileName();
            run.vaptUrl = "/files/" + dir + "/" + out.vaptHtml.getFileName();
            run.vaptPdfUrl = "/files/" + dir + "/" + out.vaptPdf.getFileName();
            run.finishedAt = System.currentTimeMillis();
            run.state = run.cancel.get() ? "CANCELLED" : "DONE";
            run.event("INFO", null, run.cancel.get() ? "Run cancelled" : "Run finished");
            try {
                Path page = out.html.getParent().resolve("Report_" + out.html.getFileName().toString().replaceFirst("^Reports_", "").replaceFirst("\\.html$", "") + "_console.html");
                Files.writeString(page, ConsoleState.staticPage(run), java.nio.charset.StandardCharsets.UTF_8);
                run.consoleReportUrl = "/files/" + dir + "/" + page.getFileName();
            } catch (Exception ignored) {
                // the classic report is still there
            }
            Map<String, Integer> c = new TreeMap<>();
            for (TestResult r : results) c.merge(r.outcome, 1, Integer::sum);
            com.fasterxml.jackson.databind.node.ObjectNode ev = M.createObjectNode();
            ev.put("note", "run " + runner.runId() + " " + c);
            store.audit(run.project, null, ev);
        } catch (Exception e) {
            run.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            run.finishedAt = System.currentTimeMillis();
            run.state = "ERROR";
        }
    }
}
