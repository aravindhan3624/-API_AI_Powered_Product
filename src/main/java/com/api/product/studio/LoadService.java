package com.api.product.studio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.api.product.core.engine.RulePack;
import com.api.product.core.load.LoadReport;
import com.api.product.core.load.LoadRunner;
import com.api.product.core.model.ApiModel;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Runs load tests in the background (one per project at a time) and writes an HTML report when it ends. */
public final class LoadService {

    public static final class Job {
        public String id;
        public String project;
        public String baseUrl = "";
        public LoadRunner.Config config;
        public LoadRunner.Result result = new LoadRunner.Result();
        public volatile String reportUrl;
        public volatile String error;
        final AtomicBoolean cancel = new AtomicBoolean();
    }

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ProjectStore store;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public LoadService(ProjectStore store) {
        this.store = store;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public Job active(String project) {
        return jobs.values().stream().filter(j -> j.project.equals(project) && "RUNNING".equals(j.result.state)).findFirst().orElse(null);
    }

    public Job start(String project, LoadRunner.Config cfg) throws Exception {
        cfg.validate();
        if (active(project) != null) throw new IllegalStateException("A load test is already running for " + project);
        ApiModel model = store.load(project);
        Job j = new Job();
        j.id = "L" + System.currentTimeMillis();
        j.project = project;
        j.config = cfg;
        j.baseUrl = model.environments.isEmpty() ? "" : String.valueOf(model.environments.get(0).baseUrl);
        jobs.put(j.id, j);
        Thread t = new Thread(() -> execute(j, model), "load-" + j.id);
        t.setDaemon(true);
        t.start();
        return j;
    }

    public void cancel(String id) {
        Job j = jobs.get(id);
        if (j != null) j.cancel.set(true);
    }

    private void execute(Job j, ApiModel model) {
        try {
            Path override = store.dir(j.project).resolve("rules.properties");
            RulePack rules = Files.exists(override) ? RulePack.load(override) : RulePack.classpath("rules/api-test-rules.properties");
            LoadRunner.run(model, rules, j.config, j.cancel, j.result);
            String dir = ProjectStore.safe(model.api.name);
            Path out = Paths.get("reports", dir);
            Files.createDirectories(out);
            String stamp = new java.text.SimpleDateFormat("dd-MMM-yy_HH-mm-ss").format(new java.util.Date(j.result.startedAt));
            Path file = out.resolve("Load_" + dir + "_" + stamp + ".html");
            Files.writeString(file, LoadReport.html(model.api.name, j.baseUrl, j.config, j.result), java.nio.charset.StandardCharsets.UTF_8);
            j.reportUrl = "/files/" + dir + "/" + file.getFileName();
            ObjectNode ev = M.createObjectNode();
            ev.put("note", "load test " + j.config.users + " users, " + j.config.durationSec + " s: " + j.result.totalRequests + " requests, " + j.result.totalErrors + " errors - "
                    + (j.result.passed ? "PASSED" : "FAILED"));
            store.audit(j.project, null, ev);
        } catch (Exception e) {
            j.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            j.result.state = "ERROR";
            j.result.finishedAt = System.currentTimeMillis();
        }
    }

    /** Valid requests that can be load tested: id, label, whether the method is read-only. */
    public List<ObjectNode> candidates(ApiModel m) {
        List<ObjectNode> out = new ArrayList<>();
        for (ApiModel.TestCase t : m.testCases) {
            if (!"POSITIVE".equals(t.category)) continue;
            ApiModel.Endpoint e = m.endpoint(t.endpointId);
            if (e == null || e.path != null && e.path.toLowerCase().matches(".*(login|logout|signin|otp|password).*")) continue;
            ObjectNode o = M.createObjectNode();
            o.put("id", t.id);
            o.put("label", e.method + " " + e.path);
            o.put("method", e.method);
            o.put("readOnly", e.method.equals("GET") || e.method.equals("HEAD"));
            out.add(o);
        }
        return out;
    }
}
