package com.api.product.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.api.product.core.engine.TargetProbe;
import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Headless runner for CI/CD and schedulers (no UI, no network listener):
 * <pre>
 *   studio-cli run PROJECT [--include-draft] [--ids A,B] [--min-pass-rate 90] [--fail-on-blocked] [--junit file.xml] [--summary file.json]
 *                          [--base-url URL] [--var name=value] [--skip-probe]
 *   studio-cli probe PROJECT [--base-url URL]
 *   studio-cli list
 *   studio-cli export-ci PROJECT [--dir ci]
 * </pre>
 * Exit codes: 0 passed, 1 findings or pass-rate below the gate, 2 usage or configuration error, 3 target is not reachable / not the API,
 * 4 credentials missing (names are printed, values never). Secrets come from environment variables (${ENV:NAME} references).
 */
public final class StudioCli {

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private StudioCli() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            usage();
            return args.length == 0 ? 2 : 0;
        }
        ProjectStore store = ProjectStore.defaults();
        if (args[0].equals("run") || args[0].equals("load")) {
            com.api.product.core.license.LicenseManager.Status lic = com.api.product.core.license.LicenseManager.status();
            if (!lic.active()) {
                System.err.println(lic.message);
                return 2;
            }
        }
        switch (args[0]) {
            case "list":
                for (String p : store.projects()) {
                    ApiModel m = store.load(p);
                    System.out.println(p + "  (" + m.endpoints.size() + " APIs, " + m.testCases.size() + " tests)");
                }
                return 0;
            case "probe":
                return probe(store, new Opts(args));
            case "run":
                return runProject(store, new Opts(args));
            case "load":
                return loadTest(store, new Opts(args));
            case "export-ci":
                return exportCi(store, new Opts(args));
            default:
                System.err.println("Unknown command " + args[0]);
                usage();
                return 2;
        }
    }

    private static void usage() {
        System.out.println("API Quality Studio CLI\n"
                + "  studio-cli run PROJECT [--include-draft] [--ids A,B] [--min-pass-rate N] [--fail-on-blocked]\n"
                + "                         [--junit file.xml] [--summary file.json] [--base-url URL] [--var name=value] [--skip-probe]\n"
                + "  studio-cli probe PROJECT [--base-url URL]\n"
                + "  studio-cli list\n"
                + "  studio-cli export-ci PROJECT [--dir ci]\n"
                + "Exit codes: 0 passed | 1 findings or pass rate below the gate | 2 usage/config | 3 target not reachable or not the API | 4 credentials missing");
    }

    private static final class Opts {
        final String project;
        final Map<String, String> kv = new LinkedHashMap<>();
        final Set<String> flags = new HashSet<>();
        final List<String> vars = new ArrayList<>();

        Opts(String[] a) {
            project = a.length > 1 && !a[1].startsWith("--") ? a[1] : null;
            for (int i = project == null ? 1 : 2; i < a.length; i++) {
                if (!a[i].startsWith("--")) continue;
                String k = a[i].substring(2);
                boolean takesValue = java.util.Set.of("ids", "min-pass-rate", "junit", "summary", "base-url", "var", "dir", "users", "duration", "ramp", "think", "p95", "max-error-pct").contains(k);
                if (takesValue && i + 1 < a.length) {
                    if (k.equals("var")) vars.add(a[++i]);
                    else kv.put(k, a[++i]);
                } else {
                    flags.add(k);
                }
            }
        }
    }

    private static ApiModel load(ProjectStore store, Opts o) throws IOException {
        if (o.project == null) throw new IllegalArgumentException("Give the project name");
        String p = ProjectStore.safe(o.project);
        if (!store.exists(p)) throw new IllegalArgumentException("Unknown project " + p + " (see: studio-cli list)");
        return store.load(p);
    }

    private static int probe(ProjectStore store, Opts o) {
        try {
            ApiModel m = load(store, o);
            String base = o.kv.getOrDefault("base-url", m.environments.isEmpty() ? "" : m.environments.get(0).baseUrl);
            TargetProbe.Result r = new TargetProbe().probe(m, base, 5);
            System.out.println((r.ok ? "OK: " : r.unknown ? "UNKNOWN: " : "NOT THE API: ") + r.message);
            return r.ok || r.unknown ? 0 : 3;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Probe failed: " + e.getMessage());
            return 3;
        }
    }

    private static int loadTest(ProjectStore store, Opts o) {
        try {
            ApiModel m = load(store, o);
            String project = ProjectStore.safe(o.project);
            if (o.kv.containsKey("base-url")) System.setProperty("run.baseurl", o.kv.get("base-url"));
            String base = o.kv.getOrDefault("base-url", m.environments.isEmpty() ? "" : m.environments.get(0).baseUrl);
            if (base == null || base.isBlank() || base.contains("${")) {
                System.err.println("The project has no usable application URL. Use --base-url.");
                return 2;
            }
            List<String> missing = CredentialSetup.missingRefs(m);
            if (!missing.isEmpty()) {
                System.err.println("Credentials missing (set these environment variables): " + String.join(", ", missing));
                return 4;
            }
            TargetProbe.Result pr = new TargetProbe().probe(m, base, 3);
            System.out.println("Target check: " + pr.message);
            if (!pr.ok && !pr.unknown) return 3;
            com.api.product.core.load.LoadRunner.Config cfg = new com.api.product.core.load.LoadRunner.Config();
            LoadService svc = new LoadService(store);
            for (com.fasterxml.jackson.databind.node.ObjectNode c : svc.candidates(m)) {
                boolean wanted = o.kv.containsKey("ids") ? List.of(o.kv.get("ids").split(",")).contains(c.get("id").asText())
                        : c.get("readOnly").asBoolean() && cfg.testIds.size() < 3;
                if (wanted && (c.get("readOnly").asBoolean() || o.flags.contains("include-writes"))) cfg.testIds.add(c.get("id").asText());
            }
            cfg.users = Integer.parseInt(o.kv.getOrDefault("users", "5"));
            cfg.durationSec = Integer.parseInt(o.kv.getOrDefault("duration", "30"));
            cfg.rampSec = Integer.parseInt(o.kv.getOrDefault("ramp", "5"));
            cfg.thinkMs = Integer.parseInt(o.kv.getOrDefault("think", "200"));
            cfg.p95Ms = Long.parseLong(o.kv.getOrDefault("p95", "0"));
            cfg.maxErrorPct = Double.parseDouble(o.kv.getOrDefault("max-error-pct", "5"));
            LoadService.Job j = svc.start(project, cfg);
            System.out.println("Load test " + j.id + ": " + cfg.users + " users, " + cfg.durationSec + " s, " + cfg.testIds.size() + " request(s) on " + base);
            while ("RUNNING".equals(j.result.state)) {
                Thread.sleep(2000);
                System.out.println("  users " + j.result.activeUsers + "  requests " + j.result.totalRequests + "  errors " + j.result.totalErrors);
            }
            Thread.sleep(500);
            for (com.api.product.core.load.LoadRunner.ApiStats a : j.result.apis) {
                System.out.printf("  %-40s n=%d err=%.1f%% avg=%dms p95=%dms max=%dms%n", a.api.length() > 40 ? a.api.substring(0, 40) : a.api, a.count, a.errorPct, a.avgMs, a.p95Ms, a.maxMs);
            }
            j.result.verdict.forEach(v -> System.out.println(v));
            if (j.reportUrl != null) System.out.println("Report: reports/" + j.reportUrl.substring("/files/".length()));
            if (j.error != null) {
                System.err.println(j.error);
                return 2;
            }
            return j.result.passed ? 0 : 1;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Load test failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 2;
        }
    }

    private static int runProject(ProjectStore store, Opts o) {
        try {
            ApiModel m = load(store, o);
            String project = ProjectStore.safe(o.project);
            for (String v : o.vars) {
                int i = v.indexOf('=');
                if (i < 1) throw new IllegalArgumentException("--var expects name=value");
                System.setProperty("var." + v.substring(0, i), v.substring(i + 1));
            }
            if (o.kv.containsKey("base-url")) System.setProperty("run.baseurl", o.kv.get("base-url"));
            String base = o.kv.getOrDefault("base-url", m.environments.isEmpty() ? "" : m.environments.get(0).baseUrl);
            if (base == null || base.isBlank() || base.contains("${")) {
                System.err.println("The project has no usable application URL. Use --base-url.");
                return 2;
            }
            List<String> missing = CredentialSetup.missingRefs(m);
            if (!missing.isEmpty()) {
                System.err.println("Credentials missing (set these environment variables): " + String.join(", ", missing));
                return 4;
            }
            if (!o.flags.contains("skip-probe")) {
                TargetProbe.Result pr = new TargetProbe().probe(m, base, 4);
                System.out.println("Target check: " + pr.message);
                if (!pr.ok && !pr.unknown) return 3;
            }
            Set<String> ids = null;
            if (o.kv.containsKey("ids")) {
                ids = new HashSet<>(List.of(o.kv.get("ids").split(",")));
            }
            RunService runs = new RunService(store);
            RunService.Run run = runs.start(project, ids, o.flags.contains("include-draft"));
            System.out.println("Run " + run.id + " started: " + run.total + " tests on " + base);
            int last = -1;
            while ("RUNNING".equals(run.state)) {
                Thread.sleep(500);
                if (run.done != last && (run.done % 25 == 0 || run.done == run.total)) {
                    System.out.println("  " + run.done + "/" + run.total + "  " + new java.util.TreeMap<>(run.counts));
                    last = run.done;
                }
            }
            if ("ERROR".equals(run.state)) {
                System.err.println("Run failed: " + run.error);
                return 2;
            }
            Map<String, Integer> c = new java.util.TreeMap<>(run.counts);
            int pass = c.getOrDefault("PASS", 0), fail = c.getOrDefault("FAIL", 0), blocked = c.getOrDefault("BLOCKED", 0);
            double rate = pass + fail == 0 ? 100.0 : 100.0 * pass / (pass + fail);
            System.out.printf("Result: %s  pass rate %.1f%%%n", c, rate);
            if (run.htmlUrl != null) System.out.println("Report: reports/" + run.htmlUrl.substring("/files/".length()));
            List<TestResult> results;
            synchronized (run.live) {
                results = new ArrayList<>(run.live);
            }
            if (o.kv.containsKey("junit")) writeJunit(Paths.get(o.kv.get("junit")), project, results, o.flags.contains("fail-on-blocked"));
            if (o.kv.containsKey("summary")) writeSummary(Paths.get(o.kv.get("summary")), project, run, c, rate);
            double gate = o.kv.containsKey("min-pass-rate") ? Double.parseDouble(o.kv.get("min-pass-rate")) : 0;
            boolean bad = fail > 0 && !o.kv.containsKey("min-pass-rate") || rate < gate || (o.flags.contains("fail-on-blocked") && blocked > 0);
            if (bad) System.out.println("GATE FAILED: " + (rate < gate ? "pass rate below " + gate + "%" : fail > 0 && gate == 0 ? fail + " failed test(s)" : "blocked tests present"));
            return bad ? 1 : 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 2;
        }
    }

    static void writeJunit(Path file, String project, List<TestResult> results, boolean blockedAsFailure) throws IOException {
        Map<String, List<TestResult>> byModule = new LinkedHashMap<>();
        for (TestResult r : results) byModule.computeIfAbsent(r.module == null ? "General" : r.module, k -> new ArrayList<>()).add(r);
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"").append(xml(project)).append("\">\n");
        for (Map.Entry<String, List<TestResult>> e : byModule.entrySet()) {
            int fail = 0, skip = 0;
            long ms = 0;
            for (TestResult r : e.getValue()) {
                ms += r.latencyMs;
                if ("FAIL".equals(r.outcome) || (blockedAsFailure && "BLOCKED".equals(r.outcome))) fail++;
                else if (!"PASS".equals(r.outcome)) skip++;
            }
            x.append("  <testsuite name=\"").append(xml(e.getKey())).append("\" tests=\"").append(e.getValue().size()).append("\" failures=\"").append(fail)
                    .append("\" skipped=\"").append(skip).append("\" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0)).append("\">\n");
            for (TestResult r : e.getValue()) {
                x.append("    <testcase classname=\"").append(xml(e.getKey())).append("\" name=\"").append(xml(r.testCaseId + " " + (r.title == null ? "" : r.title)))
                        .append("\" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", r.latencyMs / 1000.0)).append("\"");
                String msg = r.remark == null ? "" : r.remark;
                if ("FAIL".equals(r.outcome) || (blockedAsFailure && "BLOCKED".equals(r.outcome))) {
                    x.append(">\n      <failure message=\"").append(xml(msg)).append("\" type=\"").append(xml(r.failureCategory == null ? r.outcome : r.failureCategory)).append("\">")
                            .append(xml(r.method + " " + r.url + " -> " + r.httpStatus + "\n" + msg)).append("</failure>\n    </testcase>\n");
                } else if (!"PASS".equals(r.outcome)) {
                    x.append(">\n      <skipped message=\"").append(xml(r.outcome + ": " + msg)).append("\"/>\n    </testcase>\n");
                } else {
                    x.append("/>\n");
                }
            }
            x.append("  </testsuite>\n");
        }
        x.append("</testsuites>\n");
        if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, x.toString(), StandardCharsets.UTF_8);
        System.out.println("JUnit XML: " + file);
    }

    private static void writeSummary(Path file, String project, RunService.Run run, Map<String, Integer> counts, double rate) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", project);
        m.put("runId", run.id);
        m.put("baseUrl", run.baseUrl);
        m.put("counts", counts);
        m.put("passRate", Math.round(rate * 10) / 10.0);
        m.put("durationSeconds", (run.finishedAt - run.startedAt) / 1000);
        m.put("report", run.htmlUrl);
        m.put("vapt", run.vaptUrl);
        if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
        M.writeValue(file.toFile(), m);
        System.out.println("Summary: " + file);
    }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    // ------------------------------------------------------------------ CI templates

    private static int exportCi(ProjectStore store, Opts o) {
        try {
            load(store, o);
            Path dir = Paths.get(o.kv.getOrDefault("dir", "ci"));
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : ciFiles(ProjectStore.safe(o.project)).entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
                System.out.println("Wrote " + dir.resolve(e.getKey()));
            }
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Could not write: " + e.getMessage());
            return 2;
        }
    }

    /** Ready-to-use pipeline files; the secrets stay in the CI secret store and arrive as environment variables. */
    public static Map<String, String> ciFiles(String project) {
        String cmd = "java -cp \"target/classes:target/lib/*\" com.api.product.studio.StudioCli run " + project
                + " --junit build/studio-junit.xml --summary build/studio-summary.json --min-pass-rate 90";
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Jenkinsfile", "pipeline {\n  agent any\n  environment {\n    // add one credentials binding per secret the project needs (names are listed by: studio-cli run " + project + ")\n"
                + "    // " + project.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_LOGIN_PASSWORD = credentials('studio-login-password')\n  }\n  stages {\n    stage('API quality') {\n      steps {\n        sh '" + cmd + "'\n      }\n    }\n  }\n"
                + "  post {\n    always {\n      junit 'build/studio-junit.xml'\n      archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true\n    }\n  }\n}\n");
        f.put("github-actions.yml", "name: API quality\non:\n  workflow_dispatch:\n  schedule:\n    - cron: '0 2 * * *'\njobs:\n  studio:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                + "      - uses: actions/setup-java@v4\n        with: { distribution: temurin, java-version: '17' }\n      - run: mvn -q compile dependency:copy-dependencies -DoutputDirectory=target/lib\n"
                + "      - run: " + cmd + "\n        env:\n          # " + project.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_LOGIN_PASSWORD: ${{ secrets.STUDIO_LOGIN_PASSWORD }}\n          NOTHING: ''\n"
                + "      - uses: actions/upload-artifact@v4\n        if: always()\n        with: { name: api-quality-reports, path: 'reports/**' }\n      - uses: mikepenz/action-junit-report@v4\n        if: always()\n        with: { report_paths: 'build/studio-junit.xml' }\n");
        f.put("azure-pipelines.yml", "trigger: none\nschedules:\n  - cron: '0 2 * * *'\n    displayName: Nightly API quality\n    branches: { include: [ main ] }\npool: { vmImage: ubuntu-latest }\nsteps:\n"
                + "  - task: JavaToolInstaller@0\n    inputs: { versionSpec: '17', jdkArchitectureOption: x64, jdkSourceOption: PreInstalled }\n"
                + "  - script: mvn -q compile dependency:copy-dependencies -DoutputDirectory=target/lib\n  - script: " + cmd + "\n    env:\n      # " + project.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_LOGIN_PASSWORD: $(StudioLoginPassword)\n      NOTHING: ''\n"
                + "  - task: PublishTestResults@2\n    condition: always()\n    inputs: { testResultsFormat: JUnit, testResultsFiles: 'build/studio-junit.xml' }\n"
                + "  - task: PublishBuildArtifacts@1\n    condition: always()\n    inputs: { PathtoPublish: reports, ArtifactName: api-quality-reports }\n");
        f.put("windows-task.bat", "@echo off\nrem Run from Windows Task Scheduler. Secrets must be set as machine/user environment variables.\ncd /d \"%~dp0..\"\n"
                + "java -cp \"target\\classes;target\\lib\\*\" com.api.product.studio.StudioCli run " + project + " --junit build\\studio-junit.xml --summary build\\studio-summary.json --min-pass-rate 90\nexit /b %errorlevel%\n");
        return f;
    }
}
