package com.api.product.studio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;

import com.api.product.ai.AiMemory;
import com.api.product.ai.Assistant;
import com.api.product.ai.KnowledgeBase;
import com.api.product.ai.OllamaClient;
import com.api.product.core.engine.TestResult;
import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.importer.PostmanModelImporter;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;
import com.api.product.core.load.LoadRunner;
import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Test Studio: local web UI + JSON API (JDK HttpServer, no extra dependency). Listens on 127.0.0.1 only; state-changing
 * calls need the X-Studio header and a localhost Host header (blocks cross-site and DNS-rebinding requests).
 */
public final class StudioServer {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> NEXT_OK = Set.of("DRAFT>REVIEWED", "REVIEWED>APPROVED", "APPROVED>ACTIVE");

    private final ProjectStore store = ProjectStore.defaults();
    private final KnowledgeBase kb = new KnowledgeBase(store);
    private final AiMemory memory = new AiMemory(store);
    private final OllamaClient llm = OllamaClient.fromProperties();
    private final Assistant assistant = new Assistant(store, kb, memory, llm);
    private final RunService runs = new RunService(store);
    private final Scheduler scheduler = new Scheduler(store, runs);
    private final LoadService loads = new LoadService(store);
    private final MockServer mocks = new MockServer();
    private final int port;
    private HttpServer server;
    /** Team mode: sign-in and roles. Off unless -Dstudio.team=true. */
    private final boolean team = Boolean.getBoolean("studio.team");
    private final TeamAuth auth = new TeamAuth(TeamAuth.defaultFile());
    private boolean tls;

    public StudioServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        System.setProperty("otp.web", "true");   // OTP is typed on the Live run page, not in a desktop popup that can hide behind the browser
        String bind = System.getProperty("studio.bind", "");
        InetAddress addr = bind.isBlank() ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind);
        String keystore = System.getProperty("studio.tls.keystore", "");
        tls = !keystore.isBlank();
        if (!addr.isLoopbackAddress()) {
            if (!team) throw new IllegalStateException("Listening on a network address needs team mode (sign-in and roles): start with -Dstudio.team=true");
            if (!tls && !Boolean.getBoolean("studio.team.insecure")) {
                throw new IllegalStateException("Team mode on a network address needs HTTPS: set -Dstudio.tls.keystore=<file.p12> -Dstudio.tls.password=<password> "
                        + "(or -Dstudio.team.insecure=true for a trial on a trusted network; passwords then travel unencrypted).");
            }
        }
        if (team) {
            String pw = auth.bootstrapAdmin();
            if (pw != null) {
                System.out.println("TEAM MODE: first start. Sign in as  admin  with the one-time password:  " + pw);
                System.out.println("           Change it under Users after signing in. It is not shown again.");
            }
        }
        if (tls) {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                try (java.io.InputStream in = Files.newInputStream(Paths.get(keystore))) {
                    ks.load(in, System.getProperty("studio.tls.password", "").toCharArray());
                }
                javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, System.getProperty("studio.tls.password", "").toCharArray());
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), null, null);
                com.sun.net.httpserver.HttpsServer hs = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(addr, port), 0);
                hs.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ctx));
                server = hs;
            } catch (Exception e) {
                throw new IllegalStateException("Could not start HTTPS: " + e.getMessage());
            }
        } else {
            server = HttpServer.create(new InetSocketAddress(addr, port), 0);
        }
        server.createContext("/", this::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        for (String p : store.projects()) kb.reloadProject(p);
        scheduler.start();
    }

    public void stop() {
        scheduler.stop();
        mocks.stopAll();
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            String allowedHosts = System.getProperty("studio.allowedHosts", "");
            boolean hostOk = host != null && (host.startsWith("localhost") || host.startsWith("127.0.0.1")
                    || (team && (allowedHosts.isBlank() || java.util.Arrays.stream(allowedHosts.split(",")).anyMatch(h -> host.toLowerCase().startsWith(h.trim().toLowerCase())))));
            if (!hostOk) {
                send(ex, 403, err("Forbidden host"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"1".equals(ex.getRequestHeaders().getFirst("X-Studio"))) {
                send(ex, 403, err("Missing X-Studio header"));
                return;
            }
            if (path.equals("/api/me")) {
                teamMe(ex);
                return;
            }
            if (team) {
                if (path.equals("/api/login") && "POST".equals(method)) {
                    teamLogin(ex);
                    return;
                }
                boolean open = path.equals("/") || path.equals("/index.html") || path.equals("/app") || path.equals("/app/") || path.startsWith("/manual");
                TeamAuth.Session sess = auth.session(cookie(ex, "aqs"));
                if (sess == null && !open) {
                    send(ex, 401, err("login required"));
                    return;
                }
                if (sess != null) {
                    if (!TeamAuth.allowed(sess.role, method, path)) {
                        send(ex, 403, err("Your role (" + sess.role + ") is not allowed to do this"));
                        return;
                    }
                    com.api.product.core.store.Actor.set(sess.user);
                    if (path.equals("/api/logout") && "POST".equals(method)) {
                        auth.logout(cookie(ex, "aqs"));
                        ex.getResponseHeaders().add("Set-Cookie", "aqs=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (tls ? "; Secure" : ""));
                        send(ex, 200, ok());
                        return;
                    }
                    if (path.equals("/api/me/password") && "POST".equals(method)) {
                        JsonNode pb = body(ex);
                        auth.login(sess.user, pb.path("old").asText(""));   // proves the old password (and counts failures)
                        auth.setPasswordFor(sess.user, pb.path("new").asText(""));
                        auth.audit(sess.user, "password changed");
                        send(ex, 200, ok());
                        return;
                    }
                    if (path.equals("/api/users")) {
                        teamUsers(ex, method);
                        return;
                    }
                }
            }
            if (path.equals("/") || path.equals("/index.html")) {
                sendBytes(ex, 200, "text/html; charset=utf-8", resource("studio/landing.html"));
            } else if (path.equals("/app") || path.equals("/app/")) {
                sendBytes(ex, 200, "text/html; charset=utf-8", resource("studio/studio.html"));
            } else if (path.equals("/api/demo/create") && "POST".equals(method)) {
                createDemo(ex);
            } else if (path.equals("/api/import/preview") && "POST".equals(method)) {
                previewImport(ex, body(ex));
            } else if (path.equals("/api/check") && "POST".equals(method)) {
                checkConnection(ex, body(ex));
            } else if (path.equals("/api/templates")) {
                send(ex, 200, templateList());
            } else if (path.startsWith("/templates/")) {
                serveTemplate(ex, path.substring(11));
            } else if (path.startsWith("/files/")) {
                serveFile(ex, path.substring(7));
            } else if (path.equals("/api/status")) {
                status(ex);
            } else if (path.equals("/api/projects")) {
                send(ex, 200, projectList());
            } else if (path.equals("/api/import") && "POST".equals(method)) {
                importProject(ex, body(ex));
            } else if (path.equals("/api/runs/active")) {
                ArrayNode ra = M.createArrayNode();
                for (RunService.Run r : runs.recent(24 * 3600_000L)) {
                    ObjectNode o = ra.addObject();
                    o.put("id", r.id);
                    o.put("project", r.project);
                    o.put("state", r.state);
                    o.put("done", r.done);
                    o.put("total", r.total);
                    o.put("paused", r.paused);
                    o.put("startedAt", r.startedAt);
                    o.put("finishedAt", r.finishedAt);
                    int pass = r.counts.getOrDefault("PASS", 0), fail = r.counts.getOrDefault("FAIL", 0);
                    o.put("passRate", pass + fail == 0 ? -1 : Math.round(1000.0 * pass / (pass + fail)) / 10.0);
                    com.api.product.core.engine.HumanGate.Pending pd = com.api.product.core.engine.HumanGate.pending();
                    o.put("otpWaiting", pd != null && pd.project.equals(r.project) && "RUNNING".equals(r.state));
                    if (r.htmlUrl != null) o.put("reportUrl", r.consoleReportUrl != null ? r.consoleReportUrl : r.htmlUrl);
                    if (r.error != null) o.put("error", r.error);
                }
                send(ex, 200, ra);
            } else if (path.equals("/console")) {
                sendBytes(ex, 200, "text/html; charset=utf-8", ConsoleState.livePage().getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/console/state")) {
                RunService.Run cr = consoleRun(ex);
                if (cr == null) send(ex, 404, err("Unknown run"));
                else sendBytes(ex, 200, "application/json; charset=utf-8", M.writeValueAsBytes(ConsoleState.state(cr)));
            } else if (path.equals("/api/console/control") && "POST".equals(method)) {
                RunService.Run cr = consoleRun(ex);
                if (cr == null) {
                    send(ex, 404, err("Unknown run"));
                } else {
                    String act = body(ex).path("action").asText("");
                    runs.setPaused(cr.id, "pause".equals(act));
                    send(ex, 200, M.createObjectNode().put("ok", true));
                }
            } else if (path.equals("/api/open")) {
                RunService.Run lr = runs.latest();
                String what = "POST".equals(method) ? body(ex).path("what").asText("") : "";
                String url = lr == null ? null : "excel".equals(what) ? lr.excelUrl : "vapt".equals(what) ? lr.vaptPdfUrl : "html".equals(what) ? lr.consoleReportUrl : "hub".equals(what) ? lr.indexUrl : "folder".equals(what) ? lr.indexUrl : null;
                boolean opened = false;
                if (url != null && java.awt.Desktop.isDesktopSupported()) {
                    try {
                        Path f = Paths.get("reports").toAbsolutePath().normalize().resolve(url.replaceFirst("^/files/", "")).normalize();
                        if (f.startsWith(Paths.get("reports").toAbsolutePath().normalize()) && Files.exists(f)) {
                            java.awt.Desktop.getDesktop().open("folder".equals(what) ? f.getParent().toFile() : f.toFile());
                            opened = true;
                        }
                    } catch (Exception ignored) {
                        // fall back to the link next to the button
                    }
                }
                send(ex, 200, M.createObjectNode().put("ok", opened).put("error", opened ? "" : "Use the link next to the button"));
            } else if (path.equals("/api/otp")) {
                if ("POST".equals(method)) {
                    JsonNode ob = body(ex);
                    String action = ob.path("action").asText("");
                    boolean ok;
                    if ("submit".equals(action)) ok = com.api.product.core.engine.HumanGate.submit(ob.path("otp").asText(ob.path("value").asText("")));
                    else if ("skip".equals(action) || ob.path("cancel").asBoolean(false)) ok = com.api.product.core.engine.HumanGate.submit(null);
                    else ok = true;   // retry / expire / resend: nothing to do, Studio waits for the person
                    send(ex, 200, M.createObjectNode().put("ok", ok).put("accepted", ok));
                } else {
                    com.api.product.core.engine.HumanGate.Pending pd = com.api.product.core.engine.HumanGate.pending();
                    ObjectNode on = M.createObjectNode();
                    on.put("waiting", pd != null);
                    if (pd != null) {
                        on.put("project", pd.project);
                        on.put("testId", pd.testId);
                        on.put("api", pd.api);
                    }
                    send(ex, 200, on);
                }
            } else if (path.equals("/api/ai")) {
                if ("POST".equals(method)) {
                    JsonNode ab = body(ex);
                    com.api.product.ai.AiSettings ns = new com.api.product.ai.AiSettings();
                    ns.enabled = ab.path("enabled").asBoolean(false);
                    ns.url = ab.path("url").asText("http://localhost:11434").trim();
                    ns.model = ab.path("model").asText("auto").trim();
                    ns.lowPower = ab.path("lowPower").asBoolean(true);
                    ns.polish = ab.path("polish").asBoolean(false);
                    ns.timeoutSeconds = ab.path("timeoutSeconds").asInt(180);
                    ns.connectTimeoutMs = ab.path("connectTimeoutMs").asInt(0);
                    ns.validate();
                    ns.save();
                    llm.apply(ns);
                }
                send(ex, 200, aiStatus());
            } else if (path.equals("/manual")) {
                sendBytes(ex, 200, "text/html; charset=utf-8", ManualRenderer.html().getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/manual.pdf")) {
                Path tmp = Files.createTempFile("studio-manual", ".pdf");
                try {
                    ManualRenderer.pdf(tmp);
                    ex.getResponseHeaders().add("Content-Disposition", "inline; filename=\"API_Quality_Studio_User_Manual.pdf\"");
                    sendBytes(ex, 200, "application/pdf", Files.readAllBytes(tmp));
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } else if (path.equals("/api/license")) {
                if ("POST".equals(method)) {
                    send(ex, 200, com.api.product.core.license.LicenseManager.toJson(com.api.product.core.license.LicenseManager.install(body(ex).path("license").asText(""))));
                } else {
                    send(ex, 200, com.api.product.core.license.LicenseManager.toJson(com.api.product.core.license.LicenseManager.status()));
                }
            } else if (path.equals("/api/chat") && "POST".equals(method)) {
                JsonNode b = body(ex);
                send(ex, 200, M.valueToTree(assistant.reply(b.path("project").asText(null), b.path("message").asText(""))));
            } else if (path.equals("/api/feedback") && "POST".equals(method)) {
                JsonNode b = body(ex);
                if ("dismiss".equals(b.path("kind").asText())) {
                    memory.dismiss(b.path("project").asText(), b.path("endpointId").asText(), b.path("category").asText());
                }
                send(ex, 200, ok());
            } else if (path.startsWith("/api/load/")) {
                loadRoute(ex, path.substring(10));
            } else if (path.startsWith("/api/run/")) {
                runRoute(ex, path.substring(9), method);
            } else if (path.startsWith("/api/project/")) {
                projectRoute(ex, path.substring(13), method);
            } else {
                send(ex, 404, err("Not found"));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            send(ex, 400, err(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, err(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            com.api.product.core.store.Actor.set(null);
        }
    }

    // ------------------------------------------------------------------ team mode

    private static String cookie(HttpExchange ex, String name) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    /** Fills ${name} from the project's own (non-secret) variables, a few levels deep. Unknown names stay as they are. */
    private static String resolveVars(String s, Map<String, String> vars) {
        String cur = s == null ? "" : s;
        for (int i = 0; i < 5 && cur.contains("${"); i++) {
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)\\??}").matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (mm.find()) {
                String v = vars.get(mm.group(1));
                mm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null || v.contains("${ENV:") ? mm.group() : v));
                any |= v != null;
            }
            mm.appendTail(sb);
            cur = sb.toString();
            if (!any) break;
        }
        return cur;
    }

    /** Edits where one API is called: a full URL, or base URL + API path; optionally makes that base URL the project's. */
    private void editEndpointUrl(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        Endpoint ep = m.endpoint(b.path("id").asText(""));
        if (ep == null) throw new IllegalArgumentException("Unknown API");
        if (m.environments.isEmpty()) m.environments.add(new ApiModel.Environment());
        ApiModel.Environment env = m.environments.get(0);
        String full = b.path("fullUrl").asText("").trim();
        String bs = b.path("baseUrl").asText("").trim();
        String ap = b.path("apiPath").asText("").trim();
        if (full.isEmpty()) full = bs.replaceAll("/+$", "") + (ap.startsWith("/") || ap.isEmpty() ? "" : "/") + ap;
        java.util.regex.Matcher um = java.util.regex.Pattern.compile("(?i)^(https?://[^/?#\\s]+)(.*)$").matcher(full);
        String envBase = resolveVars(env.baseUrl == null ? "" : env.baseUrl, env.variables);
        if (um.matches()) {
            String origin = um.group(1), rest = um.group(2).isEmpty() ? "/" : um.group(2);
            String eb = envBase.replaceAll("/+$", "");
            boolean underBase = !eb.isEmpty() && full.regionMatches(true, 0, eb, 0, eb.length()) && (full.length() == eb.length() || "/?".indexOf(full.charAt(eb.length())) >= 0);
            // under the project's base URL (including any path prefix of it) the API keeps a short relative path; anywhere else it keeps its full URL
            ep.path = underBase ? (full.substring(eb.length()).isEmpty() ? "/" : full.substring(eb.length())) : full;
            String host = java.net.URI.create(origin).getHost();
            if (host != null && !env.scopeAllowlist.contains(host)) env.scopeAllowlist.add(host);
            if (b.path("projectBase").asBoolean(false)) env.baseUrl = origin;
        } else if (full.startsWith("/") || full.contains("${")) {
            ep.path = full;
        } else {
            throw new IllegalArgumentException("Enter a full URL such as https://host/api/path, or an API path starting with /");
        }
        store.save(project, m, null, "API address changed: " + ep.id + " -> " + ep.path);
        send(ex, 200, M.createObjectNode().put("path", ep.path));
    }

    private ObjectNode aiStatus() {
        ObjectNode n = M.createObjectNode();
        com.api.product.ai.AiSettings s = llm.settings();
        n.put("enabled", s != null && s.enabled);
        n.put("url", s == null ? "" : s.url);
        n.put("model", s == null ? "auto" : s.model);
        n.put("lowPower", s == null || s.lowPower);
        n.put("polish", s != null && s.polish);
        n.put("timeoutSeconds", s == null ? 180 : s.timeoutSeconds);
        n.put("connectTimeoutMs", s == null ? 0 : s.connectTimeoutMs);
        n.put("remote", llm.remote());
        boolean on = s != null && s.enabled;
        n.put("reachable", on && llm.available());
        n.put("activeModel", on && llm.available() ? llm.model() : "");
        n.set("installedModels", M.valueToTree(on ? llm.installedModels() : java.util.List.of()));
        n.put("recommended", llm.hardware().recommendedModel());
        n.put("hardware", llm.hardware().summary());
        return n;
    }

    /** Extra test ideas: built-in (field meaning) and, when the AI engine is on, from the model. Nothing is saved until "add". */
    private void ideas(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        if ("add".equals(b.path("action").asText())) {
            int added = 0;
            for (JsonNode x : b.path("ideas")) {
                com.api.product.ai.TestIdeas.Idea i = new com.api.product.ai.TestIdeas.Idea();
                i.endpointId = x.path("endpointId").asText("");
                i.field = x.path("field").asText("");
                i.category = x.path("category").asText("");
                i.title = x.path("title").asText("");
                i.value = x.get("value");
                i.reason = x.path("reason").asText("");
                i.source = x.path("source").asText("built-in");
                Endpoint ep = m.endpoint(i.endpointId);
                if (ep == null || ep.requestBody == null || !ep.requestBody.has(i.field) || i.value == null || !i.value.isValueNode()) continue;
                m.testCases.add(com.api.product.ai.TestIdeas.toTest(m, i));
                added++;
            }
            store.save(project, m, null, "test ideas added as DRAFT: " + added);
            send(ex, 200, M.createObjectNode().put("added", added));
            return;
        }
        List<com.api.product.ai.TestIdeas.Idea> all = new ArrayList<>();
        StringBuilder note = new StringBuilder();
        boolean aiUsed = false;
        int n = 0;
        for (JsonNode id : b.path("endpointIds")) {
            Endpoint ep = m.endpoint(id.asText());
            if (ep == null || n++ >= 12) continue;
            all.addAll(com.api.product.ai.TestIdeas.builtIn(m, ep));
            if (b.path("useAi").asBoolean(false)) {
                com.api.product.ai.TestIdeas.AiResult ar = com.api.product.ai.TestIdeas.viaAi(m, ep, businessNotes(project, ep), llm);
                all.addAll(ar.ideas);
                aiUsed |= ar.used;
                if (note.indexOf(ar.message) < 0) note.append(ar.message).append(' ');
            }
        }
        com.api.product.ai.TestIdeas.markDuplicates(m, all);
        ObjectNode out = M.createObjectNode();
        ArrayNode a = out.putArray("ideas");
        int k = 0;
        for (com.api.product.ai.TestIdeas.Idea i : all) {
            ObjectNode o = a.addObject();
            o.put("n", ++k);
            o.put("endpointId", i.endpointId);
            o.put("endpoint", i.endpointLabel);
            o.put("field", i.field);
            o.put("category", i.category);
            o.put("title", i.title);
            o.set("value", i.value);
            o.put("reason", i.reason == null ? "" : i.reason);
            o.put("source", i.source);
            o.put("duplicate", i.duplicate);
        }
        out.put("aiUsed", aiUsed);
        out.put("aiMessage", note.toString().trim());
        send(ex, 200, out);
    }

    /** A few lines of the project's documents that mention the API path or name (context for the model). */
    private String businessNotes(String project, Endpoint ep) {
        try {
            Path docs = store.dir(project).resolve("docs");
            if (!Files.isDirectory(docs)) return "";
            StringBuilder sb = new StringBuilder();
            try (java.util.stream.Stream<Path> st = Files.list(docs)) {
                for (Path f : (Iterable<Path>) st::iterator) {
                    String t = KnowledgeBase.readText(f);
                    if (t == null) continue;
                    for (String line : t.split("\\R")) {
                        if ((line.contains(String.valueOf(ep.path)) || (ep.name != null && ep.name.length() > 3 && line.toLowerCase(Locale.ROOT).contains(ep.name.toLowerCase(Locale.ROOT)))) && sb.length() < 700) sb.append(line.trim()).append(' ');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Plain-English explanation of one result of the latest run, with an optional model wording. */
    private void explain(HttpExchange ex, String project, JsonNode b) throws Exception {
        String id = b.path("testId").asText("");
        TestResult found = store.latestResults(project).stream().filter(r -> id.equals(r.testCaseId)).findFirst().orElse(null);
        if (found == null) {
            RunService.Run lr = runs.latest();
            if (lr != null && lr.project.equals(project)) found = RunService.full(lr, id);
        }
        if (found == null) {
            send(ex, 404, err("No result for " + id + " yet"));
            return;
        }
        com.api.product.ai.PlainExplainer.Explanation e = com.api.product.ai.PlainExplainer.explain(found);
        if (b.path("useAi").asBoolean(false)) com.api.product.ai.PlainExplainer.addAi(e, found, llm);
        send(ex, 200, M.valueToTree(e));
    }

    private RunService.Run consoleRun(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) if (part.startsWith("run=")) return runs.get(part.substring(4));
        return null;
    }

    private void teamMe(HttpExchange ex) throws IOException {
        ObjectNode n = M.createObjectNode();
        n.put("team", team);
        TeamAuth.Session s = team ? auth.session(cookie(ex, "aqs")) : null;
        n.put("authenticated", !team || s != null);
        if (s != null) {
            n.put("user", s.user);
            n.put("role", s.role);
        }
        send(ex, 200, n);
    }

    private void teamLogin(HttpExchange ex) throws IOException {
        JsonNode b = body(ex);
        String token = auth.login(b.path("user").asText(""), b.path("password").asText(""));
        ex.getResponseHeaders().add("Set-Cookie", "aqs=" + token + "; Path=/; HttpOnly; SameSite=Strict" + (tls ? "; Secure" : ""));
        TeamAuth.Session s = auth.session(token);
        auth.audit(s.user, "signed in");
        send(ex, 200, M.createObjectNode().put("user", s.user).put("role", s.role));
    }

    private void teamUsers(HttpExchange ex, String method) throws IOException {
        if ("POST".equals(method)) {
            JsonNode b = body(ex);
            String u = b.path("username").asText("");
            switch (b.path("action").asText("")) {
                case "add": auth.add(u, b.path("password").asText(""), b.path("role").asText("viewer")); break;
                case "password": auth.setPasswordFor(u, b.path("password").asText("")); break;
                case "enabled": auth.setEnabled(u, b.path("enabled").asBoolean(true)); break;
                case "role": auth.setRole(u, b.path("role").asText("viewer")); break;
                default: throw new IllegalArgumentException("Unknown action");
            }
            auth.audit(com.api.product.core.store.Actor.current(), "user admin: " + b.path("action").asText() + " " + u);
        }
        send(ex, 200, M.valueToTree(auth.listUsers()));
    }

    private void projectRoute(HttpExchange ex, String rest, String method) throws Exception {
        String[] seg = rest.split("/");
        String project = ProjectStore.safe(seg[0]);
        if (!store.exists(project)) throw new IllegalArgumentException("Unknown project " + project);
        String sub = seg.length > 1 ? seg[1] : "";
        switch (sub) {
            case "":
                send(ex, 200, projectDetail(project));
                return;
            case "test":
                if (seg.length < 3) throw new IllegalArgumentException("test id missing");
                updateTest(ex, project, seg[2], body(ex));
                return;
            case "bulk":
                bulk(ex, project, body(ex));
                return;
            case "tests":
                addTests(ex, project, body(ex));
                return;
            case "run": {
                JsonNode b = body(ex);
                Set<String> ids = null;
                if (b.has("ids") && b.get("ids").isArray() && b.get("ids").size() > 0) {
                    ids = new HashSet<>();
                    for (JsonNode n : b.get("ids")) ids.add(n.asText());
                }
                requireLicense(false);
                RunService.Run r = runs.start(project, ids, b.path("includeDraft").asBoolean(false));
                send(ex, 200, M.createObjectNode().put("runId", r.id).put("total", r.total));
                return;
            }
            case "reports":
                send(ex, 200, reportList(project));
                return;
            case "history": {
                ArrayNode a = M.createArrayNode();
                store.history(project, 100).forEach(a::add);
                send(ex, 200, a);
                return;
            }
            case "target":
                setTarget(ex, project, body(ex));
                return;
            case "overview":
                send(ex, 200, overview(project));
                return;
            case "result": {
                String id = seg.length > 2 ? seg[2] : "";
                TestResult found = store.latestResults(project).stream().filter(r -> id.equals(r.testCaseId)).findFirst().orElse(null);
                if (found == null) {
                    send(ex, 404, err("No result for " + id + " in the latest run"));
                } else {
                    send(ex, 200, M.valueToTree(found));
                }
                return;
            }
            case "testdata":
                if ("POST".equals(method)) {
                    uploadTestData(ex, project, body(ex));
                } else {
                    byte[] x = com.api.product.core.store.TestDataSheet.export(project, store.load(project));
                    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + project + "_testdata.xlsx\"");
                    sendBytes(ex, 200, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", x);
                }
                return;
            case "assess":
                assess(ex, project, body(ex));
                return;
            case "credentials":
                if ("POST".equals(method)) {
                    ApiModel cm = store.load(project);
                    String summary = CredentialSetup.apply(project, cm, body(ex));
                    store.save(project, cm, null, "credentials: " + summary);
                }
                send(ex, 200, CredentialSetup.status(project, store.load(project)));
                return;
            case "secure": {
                ApiModel sm = store.load(project);
                boolean apply = body(ex).path("apply").asBoolean(true);
                com.api.product.core.store.SecretScrubber.Result sr = com.api.product.core.store.SecretScrubber.scrub(project, sm, apply);
                if (apply && sr.count() > 0) {
                    sr.secrets.forEach((k, v) -> com.api.product.core.store.SecretVault.put(k, v, false));
                    store.save(project, sm, null, "secrets moved out of the project file: " + sr.count());
                }
                ObjectNode sn = M.createObjectNode();
                sn.put("moved", sr.count());
                sn.put("applied", apply);
                sn.set("items", M.valueToTree(sr.report.stream().limit(30).toArray()));
                send(ex, 200, sn);
                return;
            }
            case "endpoint":
                editEndpointUrl(ex, project, body(ex));
                return;
            case "ideas":
                ideas(ex, project, body(ex));
                return;
            case "explain":
                explain(ex, project, body(ex));
                return;
            case "export": {
                String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
                ApiModel xm = store.load(project);
                Path ov = store.dir(project).resolve("rules.properties");
                com.api.product.core.engine.RulePack xr = Files.exists(ov) ? com.api.product.core.engine.RulePack.load(ov) : com.api.product.core.engine.RulePack.classpath("rules/api-test-rules.properties");
                com.api.product.core.report.CodeExport.Options xo = new com.api.product.core.report.CodeExport.Options();
                xo.includeDraft = q.contains("draft=1");
                boolean ka = q.contains("format=karate");
                byte[] zip = ka ? com.api.product.core.report.CodeExport.karate(xm, xr, xo) : com.api.product.core.report.CodeExport.restAssured(xm, xr, xo);
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + project + (ka ? "_karate" : "_restassured") + ".zip\"");
                sendBytes(ex, 200, "application/zip", zip);
                return;
            }
            case "clear":
                clearOld(ex, project, "POST".equals(method) ? body(ex) : null);
                return;
            case "audit": {
                String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
                if (q.contains("verify=1")) {
                    send(ex, 200, store.verifyAudit(project));
                    return;
                }
                List<ObjectNode> hist = store.history(project, 100000);
                java.util.Collections.reverse(hist);
                if (q.contains("format=csv")) {
                    StringBuilder csv = new StringBuilder("time,user,action,hash\n");
                    for (ObjectNode h : hist) {
                        String what = h.path("note").asText(h.path("action").asText(h.path("event").asText("")));
                        csv.append(h.path("ts").asText()).append(',').append('"').append(h.path("actor").asText().replace("\"", "\"\"")).append("\",\"")
                                .append(what.replace("\"", "\"\"")).append("\",").append(h.path("hash").asText()).append('\n');
                    }
                    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + project + "_audit.csv\"");
                    sendBytes(ex, 200, "text/csv; charset=utf-8", ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                ArrayNode all = M.createArrayNode();
                hist.forEach(all::add);
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + project + "_audit.json\"");
                sendBytes(ex, 200, "application/json; charset=utf-8", M.writerWithDefaultPrettyPrinter().writeValueAsBytes(all));
                return;
            }
            case "issues": {
                String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
                String fmt = q.contains("format=azure") ? "azure" : "jira";
                boolean group = !q.contains("group=0");
                List<com.api.product.core.report.IssueExport.Issue> is = com.api.product.core.report.IssueExport.build(project, store.latestResults(project), group, q.contains("blocked=1"));
                if (q.contains("count=1")) {
                    send(ex, 200, M.createObjectNode().put("issues", is.size()));
                    return;
                }
                String csv = "azure".equals(fmt) ? com.api.product.core.report.IssueExport.azureCsv(is) : com.api.product.core.report.IssueExport.jiraCsv(is);
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + project + "_defects_" + fmt + ".csv\"");
                sendBytes(ex, 200, "text/csv; charset=utf-8", ("﻿" + csv).getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "ws": {
                JsonNode wb = body(ex);
                ApiModel wm = store.load(project);
                if (wm.environments.isEmpty()) throw new IllegalArgumentException("Set the application URL first");
                Endpoint wep = com.api.product.core.importer.WebSocketBuilder.add(wm, wb.path("path").asText(""), wb.path("message").asText(""), wb.path("expectMessages").asInt(1), wb.path("expectContains").asText(""));
                store.save(project, wm, null, "WebSocket request added: " + wep.path);
                send(ex, 200, M.createObjectNode().put("endpoint", wep.id));
                return;
            }
            case "mock": {
                if ("POST".equals(method)) {
                    JsonNode mb = body(ex);
                    if ("stop".equals(mb.path("action").asText())) {
                        mocks.stop(project);
                    } else {
                        mocks.start(project, store.load(project), mb.path("port").asInt(8181), mb.path("latencyMs").asInt(0), mb.path("errorPct").asInt(0));
                    }
                }
                ObjectNode mn = M.createObjectNode();
                MockServer.Instance mi = mocks.get(project);
                mn.put("running", mi != null);
                if (mi != null) {
                    mn.put("url", mi.url());
                    mn.put("port", mi.port);
                    mn.put("latencyMs", mi.latencyMs);
                    mn.put("errorPct", mi.errorPct);
                    mn.put("served", mi.served.get());
                }
                send(ex, 200, mn);
                return;
            }
            case "load": {
                if (!"POST".equals(method)) {
                    ApiModel lm = store.load(project);
                    ObjectNode ln = M.createObjectNode();
                    ln.set("tests", M.valueToTree(loads.candidates(lm)));
                    LoadService.Job aj = loads.active(project);
                    if (aj != null) ln.put("activeId", aj.id);
                    send(ex, 200, ln);
                    return;
                }
                startLoad(ex, project, body(ex));
                return;
            }
            case "rules":
                rules(ex, project, "POST".equals(method) ? body(ex) : null);
                return;
            case "synthetic":
                synthetic(ex, project, "POST".equals(method) ? body(ex) : null);
                return;
            case "schedule": {
                if ("POST".equals(method)) {
                    JsonNode sb = body(ex);
                    switch (sb.path("action").asText("")) {
                        case "add": {
                            Scheduler.Sched sc = new Scheduler.Sched();
                            sc.kind = sb.path("kind").asText("daily");
                            sc.time = sb.path("time").asText("02:00");
                            sc.day = sb.path("day").asText("MON");
                            sc.everyMinutes = sb.path("everyMinutes").asInt(60);
                            sc.label = sb.path("label").asText("");
                            scheduler.add(project, sc);
                            break;
                        }
                        case "remove":
                            scheduler.remove(project, sb.path("id").asText(""));
                            break;
                        case "toggle":
                            scheduler.toggle(project, sb.path("id").asText(""), sb.path("enabled").asBoolean(true));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown action");
                    }
                }
                send(ex, 200, M.valueToTree(scheduler.list(project)));
                return;
            }
            case "automation": {
                ObjectNode an = M.createObjectNode();
                ObjectNode files = an.putObject("files");
                StudioCli.ciFiles(project).forEach(files::put);
                an.put("command", "java -cp \"target\\classes;target\\lib\\*\" com.api.product.studio.StudioCli run " + project + " --min-pass-rate 90 --junit build\\studio-junit.xml");
                an.set("missingSecrets", M.valueToTree(CredentialSetup.missingRefs(store.load(project))));
                send(ex, 200, an);
                return;
            }
            case "flow":
                flow(ex, project, "POST".equals(method) ? body(ex) : null);
                return;
            case "contract": {
                JsonNode cb = body(ex);
                String mode = cb.path("mode").asText("");
                if (!java.util.List.of("off", "warn", "fail").contains(mode)) throw new IllegalArgumentException("mode must be off, warn or fail");
                ApiModel cm = store.load(project);
                cm.api.contractMode = mode;
                store.save(project, cm, null, "contract checking set to " + mode);
                send(ex, 200, M.createObjectNode().put("mode", mode));
                return;
            }
            case "drift":
                drift(ex, project, body(ex));
                return;
            case "probe":
                probe(ex, project, body(ex), false);
                return;
            case "findbase":
                probe(ex, project, body(ex), true);
                return;
            case "capture":
                captureRoute(ex, project, seg.length > 2 ? seg[2] : "", "GET".equals(method) ? M.createObjectNode() : body(ex));
                return;
            case "docs":
                if ("POST".equals(method)) {
                    uploadDoc(ex, project, body(ex));
                } else {
                    send(ex, 200, docList(project));
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown action " + sub);
        }
    }

    /**
     * One-click assessment: derive negative and security tests from the positive tests and data of the project, approve everything
     * that can run, run it in the background and build HTML, Excel, PDF and VAPT reports. Needs an explicit authorization flag.
     */
    private void assess(HttpExchange ex, String project, JsonNode b) throws Exception {
        if (b.path("dryRun").asBoolean(false)) {
            // preview only: nothing is saved and nothing is sent
            ApiModel pm = store.load(project);
            com.api.product.ai.TestGenerator.Options po = new com.api.product.ai.TestGenerator.Options();
            po.includeWriteData = b.path("includeWriteData").asBoolean(false);
            po.largePayload = b.path("largePayload").asBoolean(true);
            po.allMethods = b.path("allMethods").asBoolean(false);
            com.api.product.ai.TestGenerator.Result pg = com.api.product.ai.TestGenerator.generateAll(pm, po, memory, memory.load(project), null);
            int runnable = 0;
            for (TestCase t : pm.testCases) {
                boolean ready = "ACTIVE".equals(t.lifecycle) || "APPROVED".equals(t.lifecycle)
                        || (("DRAFT".equals(t.lifecycle) || "REVIEWED".equals(t.lifecycle)) && !t.tags.contains("unsupported-body") && !t.tags.contains("run-flag-N"));
                if (ready) runnable++;
            }
            String pb = pm.environments.isEmpty() || pm.environments.get(0).baseUrl == null ? "" : pm.environments.get(0).baseUrl;
            ObjectNode pn = M.createObjectNode();
            pn.put("generated", pg.tests.size());
            pn.put("runnable", runnable + pg.tests.size());
            pn.put("endpoints", pm.endpoints.size());
            pn.put("baseUrl", pb);
            pn.put("baseUrlResolved", !pb.contains("${"));
            pn.set("skipped", M.valueToTree(pg.skipped));
            send(ex, 200, pn);
            return;
        }
        if (!b.path("authorized").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm that you are authorised to test this target (authorized=true)");
        }
        requireLicense(false);
        ApiModel m = store.load(project);
        String baseUrl = m.environments.isEmpty() || m.environments.get(0).baseUrl == null ? "" : m.environments.get(0).baseUrl;
        if (baseUrl.isBlank()) throw new IllegalArgumentException("The project has no base URL; set it before running");
        if (baseUrl.toLowerCase(Locale.ROOT).matches(".*(prod|production|live).*") && !b.path("confirmProduction").asBoolean(false)) {
            throw new IllegalArgumentException("The target looks like production (" + baseUrl + "). Confirm explicitly with confirmProduction=true and agree conservative limits first.");
        }
        java.util.List<String> missingSecrets = CredentialSetup.missingRefs(m);
        if (!missingSecrets.isEmpty()) {
            throw new IllegalArgumentException("Credentials are missing (" + missingSecrets.size() + "): " + String.join(", ", missingSecrets.stream().limit(6).toArray(String[]::new))
                    + ". Open Credentials and enter them (they are kept in memory, not in the project file).");
        }
        if (!b.path("skipProbe").asBoolean(false)) {
            com.api.product.core.engine.TargetProbe.Result pr = new com.api.product.core.engine.TargetProbe().probe(m, baseUrl, 4);
            if (!pr.ok && !pr.unknown) {
                throw new IllegalArgumentException(pr.message + " Nothing was generated or sent. Use \"Find the API address\" or enter the correct base URL, then start again.");
            }
        }
        com.api.product.ai.TestGenerator.Options o = new com.api.product.ai.TestGenerator.Options();
        o.includeWriteData = b.path("includeWriteData").asBoolean(false);
        o.largePayload = b.path("largePayload").asBoolean(true);
        o.allMethods = b.path("allMethods").asBoolean(false);
        AiMemory.Data mem = memory.load(project);
        com.api.product.ai.TestGenerator.Result g = com.api.product.ai.TestGenerator.generateAll(m, o, memory, mem, null);
        m.testCases.addAll(g.tests);
        int approved = 0;
        for (TestCase t : m.testCases) {
            if (("DRAFT".equals(t.lifecycle) || "REVIEWED".equals(t.lifecycle)) && !t.tags.contains("unsupported-body") && !t.tags.contains("run-flag-N")) {
                t.lifecycle = "ACTIVE";
                approved++;
            }
        }
        store.save(project, m, null, "assessment started on " + baseUrl + ": generated " + g.tests.size() + " negative/security tests, auto-approved " + approved
                + " tests (user confirmed authorization)");
        RunService.Run run = runs.start(project, null, false);
        ObjectNode n = M.createObjectNode();
        n.put("runId", run.id);
        n.put("total", run.total);
        n.put("generated", g.tests.size());
        n.put("approved", approved);
        n.set("skipped", M.valueToTree(g.skipped));
        send(ex, 200, n);
    }

    // ------------------------------------------------------------------ probe, source parsing

    /** Proves that the project's API paths exist at an address; with {@code find} it also tries the usual API base addresses. */
    private void probe(HttpExchange ex, String project, JsonNode b, boolean find) throws Exception {
        ApiModel m = store.load(project);
        String url = b.path("baseUrl").asText("").trim();
        if (url.isEmpty() && !m.environments.isEmpty() && m.environments.get(0).baseUrl != null) url = m.environments.get(0).baseUrl;
        if (!url.matches("(?i)^https?://[^\\s/]+.*")) throw new IllegalArgumentException("Enter the application URL first, for example https://qa.example.com");
        com.api.product.core.engine.TargetProbe tp = new com.api.product.core.engine.TargetProbe();
        com.api.product.core.engine.TargetProbe.Result r = find ? tp.findBase(m, url) : tp.probe(m, url, 5);
        send(ex, 200, M.valueToTree(r));
    }

    private void loadRoute(HttpExchange ex, String rest) throws IOException {
        String[] seg = rest.split("/");
        LoadService.Job j = loads.get(seg[0]);
        if (j == null) {
            send(ex, 404, err("Unknown load test"));
            return;
        }
        if (seg.length > 1 && "cancel".equals(seg[1])) {
            loads.cancel(j.id);
            send(ex, 200, ok());
            return;
        }
        ObjectNode n = (ObjectNode) M.valueToTree(j.result);
        n.put("id", j.id);
        n.put("project", j.project);
        n.put("baseUrl", j.baseUrl);
        n.set("config", M.valueToTree(j.config));
        if (j.reportUrl != null) n.put("reportUrl", j.reportUrl);
        if (j.error != null) n.put("error", j.error);
        send(ex, 200, n);
    }

    private void startLoad(HttpExchange ex, String project, JsonNode b) throws Exception {
        if (!b.path("authorized").asBoolean(false)) throw new IllegalArgumentException("Confirm that you are authorised to load test this target (authorized=true)");
        requireLicense(false);
        ApiModel m = store.load(project);
        String base = m.environments.isEmpty() || m.environments.get(0).baseUrl == null ? "" : m.environments.get(0).baseUrl;
        if (base.isBlank() || base.contains("${")) throw new IllegalArgumentException("The project has no usable application URL");
        if (base.toLowerCase(Locale.ROOT).matches(".*(prod|production|live).*") && !b.path("confirmProduction").asBoolean(false))
            throw new IllegalArgumentException("The target looks like production (" + base + "). Load testing production needs written approval: confirm explicitly.");
        List<String> missing = CredentialSetup.missingRefs(m);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Credentials are missing (" + String.join(", ", missing.stream().limit(5).toArray(String[]::new)) + "). Enter them first.");
        com.api.product.core.engine.TargetProbe.Result pr = new com.api.product.core.engine.TargetProbe().probe(m, base, 3);
        if (!pr.ok && !pr.unknown) throw new IllegalArgumentException(pr.message + " Nothing was sent.");
        LoadRunner.Config cfg = new LoadRunner.Config();
        boolean writes = b.path("includeWrites").asBoolean(false);
        for (JsonNode x : b.path("testIds")) {
            TestCase t = m.testCases.stream().filter(c -> c.id.equals(x.asText())).findFirst().orElse(null);
            if (t == null || !"POSITIVE".equals(t.category)) continue;
            Endpoint e = m.endpoint(t.endpointId);
            boolean ro = e != null && (e.method.equals("GET") || e.method.equals("HEAD"));
            if (!ro && !writes) throw new IllegalArgumentException(t.id + " is a " + (e == null ? "" : e.method) + " request; load tests of create/update/delete APIs would create junk data. Tick the write option to allow it.");
            cfg.testIds.add(t.id);
        }
        cfg.users = b.path("users").asInt(5);
        cfg.durationSec = b.path("durationSec").asInt(30);
        cfg.rampSec = b.path("rampSec").asInt(5);
        cfg.thinkMs = b.path("thinkMs").asInt(200);
        cfg.p95Ms = b.path("p95Ms").asLong(0);
        cfg.maxErrorPct = b.path("maxErrorPct").asDouble(5.0);
        LoadService.Job j = loads.start(project, cfg);
        send(ex, 200, M.createObjectNode().put("loadId", j.id));
    }

    /** Runs, imports and new projects need an active licence (or trial). Reading, reports and exports always work. */
    private void requireLicense(boolean newProject) {
        com.api.product.core.license.LicenseManager.Status st = com.api.product.core.license.LicenseManager.status();
        if (!st.active()) throw new IllegalStateException(st.message);
        if (newProject && st.maxProjects > 0 && store.projects().size() >= st.maxProjects) {
            throw new IllegalStateException("Your " + st.edition + " licence allows " + st.maxProjects + " projects. Install a licence with more projects to add another.");
        }
    }

    /**
     * "Start fresh": GET counts what can be cleared; POST moves the old run results and report files of the project to reports/_archive/
     * (nothing is deleted for good) and, when asked, removes the auto-generated negative/security tests. The audit history is never touched.
     */
    private void clearOld(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        String dir = ProjectStore.safe(m.api.name);
        Path reportsDir = Paths.get("reports", dir);
        Path runsRoot = Paths.get("reports", "model-runs");
        List<Path> runDirs = new ArrayList<>();
        if (Files.isDirectory(runsRoot)) {
            try (java.util.stream.Stream<Path> st = Files.list(runsRoot)) {
                st.filter(p -> p.getFileName().toString().startsWith(m.api.name + "_RUN-")).forEach(runDirs::add);
            }
        }
        int reportFiles = 0;
        if (Files.isDirectory(reportsDir)) {
            try (java.util.stream.Stream<Path> st = Files.list(reportsDir)) {
                reportFiles = (int) st.filter(Files::isRegularFile).count();
            }
        }
        List<TestCase> generated = new ArrayList<>();
        for (TestCase t : m.testCases) if ("generated".equals(t.origin) && t.tags.contains("auto")) generated.add(t);
        ObjectNode n = M.createObjectNode();
        n.put("runs", runDirs.size());
        n.put("reportFiles", reportFiles);
        n.put("generatedTests", generated.size());
        n.put("totalTests", m.testCases.size());
        if (b == null) {
            send(ex, 200, n);
            return;
        }
        if (runs.activeFor(project) != null) throw new IllegalStateException("A run is in progress for this project. Cancel it first.");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        Path archive = Paths.get("reports", "_archive", dir + "_" + stamp);
        int moved = 0;
        if (b.path("results").asBoolean(true)) {
            Files.createDirectories(archive);
            for (Path r : runDirs) {
                Files.move(r, archive.resolve(r.getFileName().toString()));
                moved++;
            }
            if (Files.isDirectory(reportsDir)) {
                Files.move(reportsDir, archive.resolve("reports"));
                moved++;
            }
        }
        int removed = 0;
        if (b.path("generated").asBoolean(false) && !generated.isEmpty()) {
            m.testCases.removeAll(generated);
            removed = generated.size();
        }
        store.save(project, m, null, "cleared old records: " + moved + " result folder(s) archived, " + removed + " generated test(s) removed");
        ObjectNode out = M.createObjectNode();
        out.put("archived", moved);
        out.put("removedTests", removed);
        out.put("archive", moved > 0 ? archive.toString().replace('\\', '/') : "");
        send(ex, 200, out);
    }

    /** Business rules of the project's documents as reviewable test proposals; POST applies the approved ones. */
    private void rules(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        if (b != null) {
            int made = 0, skipped = 0;
            List<String> notes = new ArrayList<>();
            for (JsonNode x : b.path("proposals")) {
                com.api.product.core.flow.RuleAssertions.Proposal p = new com.api.product.core.flow.RuleAssertions.Proposal();
                p.ruleId = x.path("ruleId").asText("");
                p.kind = x.path("kind").asText("");
                p.endpointId = x.path("endpointId").asText("");
                p.field = x.path("field").asText("").trim();
                p.value = x.path("value").asText("");
                String res = com.api.product.core.flow.RuleAssertions.apply(m, p);
                if (res == null) {
                    skipped++;
                } else {
                    made++;
                    notes.add(res);
                }
            }
            store.save(project, m, null, "business rules applied: " + made + " approved, " + skipped + " could not be applied");
            ObjectNode r = M.createObjectNode();
            r.put("applied", made);
            r.put("skipped", skipped);
            r.set("notes", M.valueToTree(notes));
            send(ex, 200, r);
            return;
        }
        StringBuilder text = new StringBuilder();
        List<String> files = new ArrayList<>();
        Path docs = store.dir(project).resolve("docs");
        if (Files.isDirectory(docs)) {
            try (java.util.stream.Stream<Path> st = Files.list(docs)) {
                for (Path f : (Iterable<Path>) st.sorted()::iterator) {
                    String t = KnowledgeBase.readText(f);
                    if (t != null && !t.isBlank()) {
                        text.append('\n').append(t);
                        files.add(f.getFileName().toString());
                    }
                }
            }
        }
        ObjectNode n = M.createObjectNode();
        n.set("files", M.valueToTree(files));
        if (files.isEmpty()) {
            n.set("proposals", M.createArrayNode());
            n.set("unparsed", M.createArrayNode());
            send(ex, 200, n);
            return;
        }
        com.api.product.core.flow.RuleAssertions.Result res = com.api.product.core.flow.RuleAssertions.analyze(m, text.toString());
        ArrayNode a = n.putArray("proposals");
        res.proposals.forEach(p -> a.add(com.api.product.core.flow.RuleAssertions.toJson(p)));
        n.set("unparsed", M.valueToTree(res.unparsed));
        send(ex, 200, n);
    }

    /** Synthetic values for empty or placeholder request fields: GET previews, POST applies the chosen changes. */
    private void synthetic(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        List<com.api.product.core.flow.SyntheticData.Change> plan = com.api.product.core.flow.SyntheticData.plan(m);
        ObjectNode n = M.createObjectNode();
        if (b != null) {
            Set<String> pick = new HashSet<>();
            for (JsonNode x : b.path("only")) pick.add(x.asText());
            List<com.api.product.core.flow.SyntheticData.Change> chosen = new ArrayList<>();
            for (com.api.product.core.flow.SyntheticData.Change c : plan) if (pick.isEmpty() || pick.contains(c.endpointId + "|" + c.field)) chosen.add(c);
            int done = com.api.product.core.flow.SyntheticData.apply(m, chosen);
            store.save(project, m, null, "synthetic test data filled: " + done + " field(s)");
            n.put("applied", done);
            plan = com.api.product.core.flow.SyntheticData.plan(m);
        }
        n.set("changes", M.valueToTree(plan));
        send(ex, 200, n);
    }

    /** Data dependencies between APIs: list, detect, add, remove. Applying a link edits the producer (extract) and the consumer (${var}). */
    private void flow(HttpExchange ex, String project, JsonNode b) throws Exception {
        ApiModel m = store.load(project);
        String note = null;
        if (b != null) {
            String action = b.path("action").asText("");
            switch (action) {
                case "add": {
                    int added = 0;
                    for (JsonNode x : b.path("links")) {
                        ApiModel.Link l = new ApiModel.Link();
                        l.var = x.path("var").asText("").trim();
                        l.fromEndpointId = x.path("fromEndpointId").asText("");
                        l.toEndpointId = x.path("toEndpointId").asText("");
                        l.jsonPath = x.path("jsonPath").asText("").trim();
                        l.where = x.path("where").asText("path");
                        l.name = x.path("name").asText("").trim();
                        l.origin = x.path("origin").asText("manual");
                        l.confidence = x.path("confidence").asText("medium");
                        if (!l.var.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("Variable name must be letters, digits or _ (for example petId)");
                        if (m.endpoint(l.fromEndpointId) == null || m.endpoint(l.toEndpointId) == null) throw new IllegalArgumentException("Choose both APIs");
                        if (l.fromEndpointId.equals(l.toEndpointId)) throw new IllegalArgumentException("An API cannot feed itself");
                        if (!java.util.List.of("path", "query", "header", "body").contains(l.where)) throw new IllegalArgumentException("where must be path, query, header or body");
                        if (l.name.isEmpty()) l.name = l.var;
                        if (!l.jsonPath.startsWith("$")) throw new IllegalArgumentException("The response path must start with $ (for example $.id)");
                        boolean dup = m.links.stream().anyMatch(o -> o.toEndpointId.equals(l.toEndpointId) && o.where.equals(l.where) && o.name.equals(l.name));
                        if (dup) continue;
                        l.id = "L" + (m.links.stream().mapToInt(o -> o.id.replaceAll("\\D", "").isEmpty() ? 0 : Integer.parseInt(o.id.replaceAll("\\D", ""))).max().orElse(0) + 1);
                        m.links.add(l);
                        added++;
                    }
                    int edits = com.api.product.core.flow.AutoChain.apply(m);
                    note = added + " link(s) added, " + edits + " edit(s) applied";
                    break;
                }
                case "remove":
                    com.api.product.core.flow.AutoChain.remove(m, b.path("id").asText(""));
                    note = "link removed";
                    break;
                default:
                    throw new IllegalArgumentException("Unknown action " + action);
            }
            store.save(project, m, null, "data links: " + note);
        }
        ObjectNode n = M.createObjectNode();
        if (note != null) n.put("note", note);
        n.set("links", M.valueToTree(m.links));
        java.util.List<ApiModel.Link> props = com.api.product.core.flow.AutoChain.detect(m);
        n.set("proposals", M.valueToTree(props));
        ArrayNode order = n.putArray("order");
        for (Endpoint e : com.api.product.core.flow.AutoChain.order(m)) {
            TestCase t = new TestCase();
            t.endpointId = e.id;
            t.category = "POSITIVE";
            int rank = com.api.product.core.flow.AutoChain.rank(m, t);
            ObjectNode o = order.addObject();
            o.put("id", e.id);
            o.put("label", e.method + " " + (e.specKey != null ? e.specKey.replaceFirst("^\\S+ ", "") : e.path));
            o.put("rank", rank);
            o.put("phase", rank >= 200 ? "cleanup" : rank < 100 && m.links.stream().anyMatch(l -> l.enabled && l.fromEndpointId.equals(e.id)) ? "setup" : "test");
        }
        send(ex, 200, n);
    }

    /** Compares the project with a newer specification; with apply=true the safe changes are made (nothing is deleted). */
    private void drift(HttpExchange ex, String project, JsonNode b) throws Exception {
        Path file;
        boolean temp = false;
        String orig = b.path("filename").asText(null);
        if (b.hasNonNull("contentBase64")) {
            Path up = Paths.get("projects", "_uploads");
            Files.createDirectories(up);
            String ext = extOf(orig);
            file = up.resolve("drift_" + System.currentTimeMillis() + "." + (ext.isEmpty() ? "json" : ext.replaceAll("[^a-z0-9]", "")));
            Files.write(file, Base64.getDecoder().decode(b.get("contentBase64").asText()));
            temp = true;
        } else {
            file = Paths.get(b.path("path").asText(""));
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("File not found: " + file);
        }
        try {
            ApiModel current = store.load(project);
            ApiModel latest = parseSource(file, orig, "DRAFT", new String[1]);
            com.api.product.core.importer.SpecDrift.Report rep = com.api.product.core.importer.SpecDrift.compare(current, latest);
            ObjectNode n = (ObjectNode) M.valueToTree(rep);
            n.put("any", rep.any());
            List<com.api.product.core.importer.SpecHeal.Repair> repairs = com.api.product.core.importer.SpecHeal.propose(current, latest);
            n.set("repairs", com.api.product.core.importer.SpecHeal.toJson(repairs, M));
            if (b.path("apply").asBoolean(false) && rep.any()) {
                Set<String> okIds = new HashSet<>();
                b.path("healIds").forEach(h -> okIds.add(h.asText()));
                int healed = com.api.product.core.importer.SpecHeal.apply(current, repairs, okIds);
                String summary = com.api.product.core.importer.SpecDrift.apply(current, latest) + (healed > 0 ? ", " + healed + " repair(s) applied to the requests" : "");
                store.save(project, current, null, "spec drift applied: " + summary);
                n.put("applied", summary);
            }
            send(ex, 200, n);
        } finally {
            if (temp) Files.deleteIfExists(file);
        }
    }

    private ApiModel grpc(JsonNode b, String lifecycle, String[] typeOut) throws Exception {
        ApiModel gm = com.api.product.core.importer.GrpcImporter.fromServer(b.path("grpcUrl").asText(""));
        gm.testCases.forEach(t -> t.lifecycle = lifecycle);
        typeOut[0] = "gRPC";
        return gm;
    }

    private ApiModel webSocket(JsonNode b, String lifecycle, String[] typeOut) {
        ApiModel wm = com.api.product.core.importer.WebSocketBuilder.newModel(b.path("wsUrl").asText(""), b.path("message").asText(""), b.path("expectMessages").asInt(1), b.path("expectContains").asText(""));
        wm.testCases.forEach(t -> t.lifecycle = lifecycle);
        typeOut[0] = "WebSocket";
        return wm;
    }

    /** Reads the schema of a live GraphQL endpoint by introspection (headers are used once, never stored). */
    private ApiModel graphQl(JsonNode b, String lifecycle, String[] typeOut) throws Exception {
        String url = b.path("gqlUrl").asText("").trim();
        if (!url.matches("(?i)^https?://[^\s/]+.*")) throw new IllegalArgumentException("Enter the GraphQL endpoint address, for example https://host/graphql");
        Map<String, String> headers = new LinkedHashMap<>();
        b.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        JsonNode schema = com.api.product.core.importer.GraphQlImporter.introspect(url, headers);
        ApiModel gm = com.api.product.core.importer.GraphQlImporter.fromIntrospection(schema, url);
        gm.testCases.forEach(t -> t.lifecycle = lifecycle);
        typeOut[0] = "GraphQL";
        return gm;
    }

    private static String extOf(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isDocument(String name) {
        String e = extOf(name);
        return e.equals("md") || e.equals("txt") || e.equals("docx") || e.equals("pdf");
    }

    /** Postman / OpenAPI / Swagger JSON, or an API document (.md .txt .docx .pdf). {@code typeOut[0]} receives a readable type. */
    private ApiModel parseSource(Path file, String origName, String lifecycle, String[] typeOut) throws Exception {
        if (isDocument(origName == null ? file.getFileName().toString() : origName)) {
            String text = KnowledgeBase.readText(file);
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Could not read any text from this document (scanned PDF or empty file?)");
            ApiModel dm = com.api.product.core.importer.DocApiImporter.importText(text, origName == null ? file.getFileName().toString() : origName);
            dm.testCases.forEach(t -> t.lifecycle = lifecycle);
            typeOut[0] = "API document";
            return dm;
        }
        String lowerName = (origName == null ? file.getFileName().toString() : origName).toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".wsdl") || lowerName.endsWith(".xml")) {
            ApiModel wm = com.api.product.core.importer.WsdlImporter.importFile(file, origName == null ? file.getFileName().toString() : origName);
            wm.testCases.forEach(t -> t.lifecycle = lifecycle);
            typeOut[0] = "WSDL (SOAP)";
            return wm;
        }
        JsonNode root;
        try {
            root = M.readTree(file.toFile());
        } catch (Exception e) {
            throw new IllegalArgumentException("This file is not JSON. Supported: Postman v2.1, OpenAPI 3, Swagger 2 (JSON), WSDL (.wsdl) and API documents (.md .txt .docx .pdf).");
        }
        ApiModel model;
        if (com.api.product.core.importer.GraphQlImporter.looksLikeIntrospection(root)) {
            model = com.api.product.core.importer.GraphQlImporter.fromIntrospection(root, null);
            model.testCases.forEach(t -> t.lifecycle = lifecycle);
            typeOut[0] = "GraphQL (introspection file)";
        } else if (OpenApiModelImporter.looksLikeOpenApi(root)) {
            model = OpenApiModelImporter.importSpec(file, lifecycle);
            typeOut[0] = "OpenAPI / Swagger";
        } else if (root.has("item") && root.has("info")) {
            model = PostmanModelImporter.importCollection(file);
            model.testCases.forEach(t -> t.lifecycle = lifecycle);
            typeOut[0] = "Postman collection";
        } else {
            throw new IllegalArgumentException("This JSON is not a Postman collection or an OpenAPI/Swagger document");
        }
        return model;
    }

    // ------------------------------------------------------------------ demo, preview, target, connection check

    private void createDemo(HttpExchange ex) throws Exception {
        String name = "Demo_Shop";
        boolean existed = store.exists(name);
        requireLicense(!existed);
        if (!existed) {
            ApiModel m = M.readValue(resource("demo/demo-shop-model.json"), ApiModel.class);
            store.save(name, m, null, "demo project created (public sandbox dummyjson.com)");
            kb.reloadProject(name);
        }
        send(ex, 200, M.createObjectNode().put("project", name).put("existed", existed));
    }

    /** Parses an upload or a path without saving anything: what would be imported and which application URL was found. */
    private void previewImport(HttpExchange ex, JsonNode b) throws Exception {
        Path file;
        boolean temp = false;
        String orig = b.path("filename").asText(null);
        if (b.hasNonNull("contentBase64")) {
            Path up = Paths.get("projects", "_uploads");
            Files.createDirectories(up);
            String ext = extOf(orig);
            file = up.resolve("preview_" + System.currentTimeMillis() + "." + (ext.isEmpty() ? "json" : ext.replaceAll("[^a-z0-9]", "")));
            byte[] data = Base64.getDecoder().decode(b.get("contentBase64").asText());
            if (data.length > 30_000_000) throw new IllegalArgumentException("File is larger than 30 MB");
            Files.write(file, data);
            temp = true;
        } else if (b.hasNonNull("gqlUrl") || b.hasNonNull("wsUrl") || b.hasNonNull("grpcUrl")) {
            file = null;
        } else {
            file = Paths.get(b.path("path").asText(""));
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("File not found: " + file);
        }
        try {
            String[] type = new String[1];
            ApiModel model = b.hasNonNull("grpcUrl") ? grpc(b, "DRAFT", type) : b.hasNonNull("gqlUrl") ? graphQl(b, "DRAFT", type) : b.hasNonNull("wsUrl") ? webSocket(b, "DRAFT", type) : parseSource(file, orig, "DRAFT", type);
            ObjectNode n = M.createObjectNode();
            n.put("type", type[0]);
            String sug = model.api.name == null ? "" : model.api.name.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_|_$", "");
            n.put("suggestedName", sug);
            n.put("endpoints", model.endpoints.size());
            n.put("tests", model.testCases.size());
            String base = model.environments.isEmpty() || model.environments.get(0).baseUrl == null ? "" : model.environments.get(0).baseUrl;
            n.put("baseUrl", base);
            n.put("baseUrlIsVariable", base.contains("${"));
            n.set("modules", M.valueToTree(model.endpoints.stream().map(e -> e.module == null ? "General" : e.module).distinct().limit(12).toArray()));
            n.set("warnings", M.valueToTree(model.api.source.warnings.stream().limit(6).toArray()));
            n.put("warningCount", model.api.source.warnings.size());
            n.put("secretsFound", com.api.product.core.store.SecretScrubber.scrub(sug.isEmpty() ? "P" : sug, model, false).count());
            Endpoint lg = CredentialSetup.guessLogin(model);
            n.put("loginEndpoint", lg == null ? "" : lg.method + " " + lg.path);
            send(ex, 200, n);
        } finally {
            if (temp) Files.deleteIfExists(file);
        }
    }

    private void setTarget(HttpExchange ex, String project, JsonNode b) throws IOException {
        ApiModel m = store.load(project);
        if (m.environments.isEmpty()) m.environments.add(new ApiModel.Environment());
        ApiModel.Environment env = m.environments.get(0);
        String url = b.path("baseUrl").asText("").trim();
        if (!url.matches("(?i)^https?://[^\\s/]+.*")) throw new IllegalArgumentException("Enter the application URL, for example https://qa-api.example.com");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        env.baseUrl = url;
        if (b.hasNonNull("environment") && !b.get("environment").asText().isBlank()) env.name = b.get("environment").asText().trim();
        env.scopeAllowlist = new ArrayList<>();
        env.scopeAllowlist.add(java.net.URI.create(url).getHost());
        b.path("allowedHosts").forEach(n -> {
            if (!n.asText().isBlank() && !env.scopeAllowlist.contains(n.asText().trim())) env.scopeAllowlist.add(n.asText().trim());
        });
        store.save(project, m, null, "target set: " + env.name + " " + url);
        send(ex, 200, M.createObjectNode().put("baseUrl", url).put("environment", env.name));
    }

    /** Connectivity check of the application URL the user typed (http/https only; certificate problems are reported, not fatal). */
    private void checkConnection(HttpExchange ex, JsonNode b) throws Exception {
        String url = b.path("url").asText("").trim();
        if (!url.matches("(?i)^https?://[^\\s/]+.*")) throw new IllegalArgumentException("Enter a full URL starting with http:// or https://");
        if (url.contains("${")) throw new IllegalArgumentException("The URL still contains a variable; enter the real application URL");
        ObjectNode n = M.createObjectNode();
        long t0 = System.nanoTime();
        try {
            javax.net.ssl.TrustManager[] trustAll = { new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            } };
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(null, trustAll, new java.security.SecureRandom());
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(6))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            java.net.http.HttpResponse<Void> r = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            n.put("reachable", true);
            n.put("status", r.statusCode());
            n.put("ms", (System.nanoTime() - t0) / 1_000_000);
            n.put("message", "The server answered (HTTP " + r.statusCode() + (r.statusCode() >= 400 ? ": a 4xx/5xx on the root URL is normal for many APIs" : "") + ").");
        } catch (Exception e) {
            n.put("reachable", false);
            n.put("message", "Cannot reach this URL from this PC: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + ". Check the address, VPN/proxy and firewall.");
        }
        send(ex, 200, n);
    }

    // ------------------------------------------------------------------ templates, overview, test data

    private static final Map<String, String[]> TEMPLATES = new java.util.LinkedHashMap<>();

    static {
        TEMPLATES.put("BRD_Template_Blank.docx", new String[] { "BRD template (blank)",
                "Fill what you know and delete the rest. Every section is optional. Flow steps, rules and tables are read by the assistant." });
        TEMPLATES.put("BRD_Sample_Filled.docx", new String[] { "BRD sample (filled example)",
                "A worked example for a login and products API. Upload it to a project to see how \"Analyse the BRD\" maps steps and rules." });
    }

    private ArrayNode templateList() {
        ArrayNode a = M.createArrayNode();
        TEMPLATES.forEach((file, info) -> {
            ObjectNode n = a.addObject();
            n.put("file", file);
            n.put("title", info[0]);
            n.put("description", info[1]);
            n.put("url", "/templates/" + file);
        });
        return a;
    }

    private void serveTemplate(HttpExchange ex, String file) throws IOException {
        if (!TEMPLATES.containsKey(file)) {
            send(ex, 404, err("Unknown template"));
            return;
        }
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + file + "\"");
        sendBytes(ex, 200, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", resource("templates/" + file));
    }

    private void uploadTestData(HttpExchange ex, String project, JsonNode b) throws IOException {
        byte[] data = Base64.getDecoder().decode(b.path("contentBase64").asText(""));
        if (data.length == 0) throw new IllegalArgumentException("No file received");
        if (data.length > 20_000_000) throw new IllegalArgumentException("File is larger than 20 MB");
        ApiModel m = store.load(project);
        com.api.product.core.store.TestDataSheet.Summary sm;
        try {
            sm = com.api.product.core.store.TestDataSheet.apply(project, m, data);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("This is not a valid test-data workbook: " + e.getMessage());
        }
        store.save(project, m, null, "test data imported: " + sm.updatedEndpoints + " updated, " + sm.newEndpoints + " new, " + sm.switchedOff + " switched off, "
                + sm.variablesSet + " variables");
        ObjectNode n = M.createObjectNode();
        n.put("rows", sm.rows);
        n.put("updated", sm.updatedEndpoints);
        n.put("added", sm.newEndpoints);
        n.put("switchedOff", sm.switchedOff);
        n.put("variables", sm.variablesSet);
        n.set("warnings", M.valueToTree(sm.warnings));
        n.set("changes", M.valueToTree(sm.changes));
        send(ex, 200, n);
    }

    /** Everything the dashboard shows, from persisted data only (no placeholders): counts, last run, modules, trend, findings, next steps. */
    private ObjectNode overview(String project) throws IOException {
        ApiModel m = store.load(project);
        List<TestResult> last = store.latestResults(project);
        ObjectNode n = M.createObjectNode();
        n.put("name", project);
        n.put("baseUrl", m.environments.isEmpty() || m.environments.get(0).baseUrl == null ? "" : m.environments.get(0).baseUrl);
        n.put("source", m.api.source.type);
        n.put("environment", m.environments.isEmpty() || m.environments.get(0).name == null ? "" : m.environments.get(0).name);
        n.set("allowedHosts", M.valueToTree(m.environments.isEmpty() ? new ArrayList<String>() : m.environments.get(0).scopeAllowlist));
        n.put("endpoints", m.endpoints.size());
        n.put("tests", m.testCases.size());
        Map<String, Integer> life = count(m.testCases.stream().map(t -> t.lifecycle).toArray(String[]::new));
        n.set("lifecycle", M.valueToTree(life));
        int positives = (int) m.testCases.stream().filter(t -> "POSITIVE".equals(t.category)).count();
        int generated = (int) m.testCases.stream().filter(t -> "generated".equals(t.origin)).count();
        int draft = (int) m.testCases.stream().filter(t -> ("DRAFT".equals(t.lifecycle) || "REVIEWED".equals(t.lifecycle))
                && !t.tags.contains("run-flag-N") && !t.tags.contains("unsupported-body")).count();
        n.put("positives", positives);
        n.put("generated", generated);
        n.put("draft", draft);

        Map<String, Integer> oc = count(last.stream().map(r -> r.outcome).toArray(String[]::new));
        ObjectNode lr = n.putObject("lastRun");
        lr.set("counts", M.valueToTree(oc));
        int pass = oc.getOrDefault("PASS", 0), fail = oc.getOrDefault("FAIL", 0), den = pass + fail;
        lr.put("total", last.size());
        lr.put("passRate", den == 0 ? -1 : Math.round(1000.0 * pass / den) / 10.0);
        lr.put("denominator", den);
        lr.put("avgMs", Math.round(last.stream().filter(r -> r.httpStatus != null).mapToLong(r -> r.latencyMs).average().orElse(0)));

        ArrayNode mods = n.putArray("modules");
        Map<String, List<TestResult>> g = last.stream().collect(java.util.stream.Collectors.groupingBy(r -> r.module == null ? "General" : r.module, java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        g.forEach((k, v) -> {
            int p = (int) v.stream().filter(r -> "PASS".equals(r.outcome)).count(), f = (int) v.stream().filter(r -> "FAIL".equals(r.outcome)).count();
            int bl = (int) v.stream().filter(r -> "BLOCKED".equals(r.outcome)).count();
            ObjectNode o = mods.addObject();
            o.put("name", k);
            o.put("total", v.size());
            o.put("pass", p);
            o.put("fail", f);
            o.put("blocked", bl);
            o.put("passRate", p + f == 0 ? -1 : Math.round(1000.0 * p / (p + f)) / 10.0);
        });

        ArrayNode trend = n.putArray("trend");
        Path runsFile = Paths.get("reports", ProjectStore.safe(m.api.name), "runs.json");
        if (Files.exists(runsFile)) {
            List<JsonNode> list = new ArrayList<>();
            M.readTree(runsFile.toFile()).forEach(list::add);
            list.sort((a, b) -> Long.compare(a.path("epoch").asLong(), b.path("epoch").asLong()));
            for (int i = Math.max(0, list.size() - 12); i < list.size(); i++) {
                JsonNode r = list.get(i);
                ObjectNode o = trend.addObject();
                o.put("when", r.path("when").asText());
                o.put("passRate", r.path("passRate").asText());
                o.put("total", r.path("total").asInt());
                o.put("passed", r.path("passed").asInt());
                o.put("failed", r.path("failed").asInt());
            }
        }
        n.put("reports", trend.size());

        com.api.product.core.report.VaptReport.Report vr = com.api.product.core.report.VaptReport.build(last);
        ObjectNode vp = n.putObject("vapt");
        vp.set("severity", M.valueToTree(com.api.product.core.report.VaptReport.bySeverity(vr)));
        ArrayNode fa = vp.putArray("findings");
        vr.findings.stream().limit(6).forEach(f -> {
            ObjectNode o = fa.addObject();
            o.put("id", f.id);
            o.put("title", f.info == null ? f.key : f.info.title);
            o.put("severity", f.severity);
            o.put("endpoints", f.affected.size());
        });
        vp.put("securityTests", vr.securityTests);

        ArrayNode att = n.putArray("attention");
        last.stream().filter(r -> "FAIL".equals(r.outcome)).sorted((a, b) -> com.api.product.core.report.VaptReport.rank(b.severity) - com.api.product.core.report.VaptReport.rank(a.severity))
                .limit(8).forEach(r -> {
                    ObjectNode o = att.addObject();
                    o.put("id", r.testCaseId);
                    o.put("title", r.title == null ? r.apiName : r.title);
                    o.put("category", r.category);
                    o.put("http", r.httpStatus == null ? 0 : r.httpStatus);
                    o.put("severity", r.severity);
                    o.put("remark", r.remark);
                });
        Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        for (TestResult r : last) {
            if (!"BLOCKED".equals(r.outcome)) continue;
            String k = r.remark == null ? "unknown" : r.remark.replaceAll("\\$\\{[^}]*}", java.util.regex.Matcher.quoteReplacement("${..}"));
            if (k.length() > 100) k = k.substring(0, 100) + "...";
            reasons.merge(k, 1, Integer::sum);
        }
        ArrayNode br = n.putArray("blockedReasons");
        reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> br.addObject().put("reason", e.getKey()).put("count", e.getValue()));

        // steps + next best actions
        boolean importDone = !m.endpoints.isEmpty();
        boolean dataDone = "excel".equals(m.api.source.type) || "demo".equals(m.api.source.type) || store.history(project, 500).stream().anyMatch(h -> h.path("note").asText("").startsWith("test data imported"));
        boolean docs = false;
        Path dd = store.dir(project).resolve("docs");
        if (Files.isDirectory(dd)) {
            try (var ds = Files.newDirectoryStream(dd)) {
                docs = ds.iterator().hasNext();
            }
        }
        boolean ran = !last.isEmpty();
        ArrayNode steps = n.putArray("steps");
        String[][] st = { { "import", "Import API", importDone ? "done" : "current" },
                { "testdata", "Test data", !importDone ? "todo" : dataDone ? "done" : "current" },
                { "review", "Review tests", !importDone ? "todo" : draft == 0 && generated + positives > 0 ? "done" : "current" },
                { "generate", "Negatives & security", generated > 0 ? "done" : importDone ? "current" : "todo" },
                { "run", "Run", ran ? "done" : importDone ? "current" : "todo" },
                { "report", "Reports", n.path("reports").asInt() > 0 ? "done" : ran ? "current" : "todo" } };
        for (String[] x : st) steps.addObject().put("id", x[0]).put("label", x[1]).put("state", x[2]);

        ArrayNode next = n.putArray("next");
        int blocked = oc.getOrDefault("BLOCKED", 0);
        if (!importDone) {
            next.addObject().put("id", "import").put("title", "Import your API").put("detail", "Upload a Postman collection or an OpenAPI/Swagger file. Each import becomes a project.").put("action", "import");
        } else {
            if (!dataDone) {
                next.addObject().put("id", "testdata").put("title", "Prepare the test data").put("detail",
                        "Download " + project + "_testdata.xlsx (pre-filled from your API), enter valid data and upload it. Negatives are generated from this data.").put("action", "testdata");
            }
            if (generated == 0 && positives > 0) {
                next.addObject().put("id", "generate").put("title", "Generate negative and security tests").put("detail",
                        "The assistant derives them from your positive tests and data, one change per test.").put("action", "generate");
            }
            if (draft > 0) {
                next.addObject().put("id", "review").put("title", "Review " + draft + " draft tests").put("detail", "Approve what you want to run, or use Run full assessment to approve and run in one step.").put("action", "tests-draft");
            }
            if (!ran) {
                next.addObject().put("id", "assess").put("title", "Run the assessment").put("detail", "Sends the tests to the target and builds HTML, Excel, PDF and VAPT reports. Needs your authorization confirmation.").put("action", "assess");
            }
            if (blocked > 0) {
                next.addObject().put("id", "blocked").put("title", "Fix " + blocked + " blocked tests").put("detail", "They were not sent (missing credentials, a failed prerequisite or a missing value). Blocked tests hide real coverage.").put("action", "blocked");
            }
            if (fail > 0) {
                next.addObject().put("id", "failures").put("title", "Triage " + fail + " failed tests").put("detail", "Ask the assistant why a test failed, then raise confirmed defects.").put("action", "failures");
            }
            if (!docs) {
                next.addObject().put("id", "brd").put("title", "Add a BRD (optional)").put("detail", "Business flow and rules improve test design. Download the template under Help.").put("action", "docs").put("optional", true);
            }
            if (next.size() == 0 || (ran && fail == 0 && blocked == 0)) {
                next.addObject().put("id", "reports").put("title", "Share the reports").put("detail", "Open the PDF and VAPT report for stakeholders.").put("action", "reports");
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ flow capture

    private com.api.product.capture.FlowCapture capture;
    private String captureProject;
    private com.api.product.capture.CaptureAnalyzer.Analysis lastAnalysis;

    private void captureRoute(HttpExchange ex, String project, String action, JsonNode b) throws Exception {
        switch (action) {
            case "start": {
                if (capture != null && capture.isRunning()) throw new IllegalStateException("A capture is already running (project " + captureProject + ")");
                String url = b.path("startUrl").asText("");
                Set<String> hosts = new HashSet<>();
                b.path("allowedHosts").forEach(n -> {
                    if (!n.asText().isBlank()) hosts.add(n.asText().trim());
                });
                if (hosts.isEmpty() && !url.isBlank()) hosts.add(java.net.URI.create(url).getHost());
                if (url.isBlank() || hosts.isEmpty()) throw new IllegalArgumentException("Give the start URL (and the hosts you are allowed to record)");
                com.api.product.capture.FlowCapture c = new com.api.product.capture.FlowCapture(hosts);
                try {
                    c.start(url, false);
                } catch (Exception e) {
                    c.close();
                    throw new IllegalStateException("Could not open the browser window: " + e.getMessage());
                }
                capture = c;
                captureProject = project;
                send(ex, 200, M.createObjectNode().put("started", true).put("hosts", String.join(", ", hosts)));
                return;
            }
            case "status": {
                ObjectNode n = M.createObjectNode();
                boolean on = capture != null && capture.isRunning() && project.equals(captureProject);
                n.put("running", on);
                n.put("calls", on ? capture.entries().size() : 0);
                send(ex, 200, n);
                return;
            }
            case "stop": {
                if (capture == null || !capture.isRunning()) throw new IllegalStateException("No capture is running");
                List<com.api.product.capture.FlowCapture.Entry> entries = capture.entries();
                List<String> pages = capture.pages();
                capture.close();
                ApiModel m = store.load(project);
                lastAnalysis = com.api.product.capture.CaptureAnalyzer.analyze(m, entries);
                Path dir = store.dir(project).resolve("captures");
                Files.createDirectories(dir);
                ObjectNode saved = M.createObjectNode();
                saved.set("pages", M.valueToTree(pages));
                saved.set("entries", M.valueToTree(entries));
                saved.set("analysis", M.valueToTree(lastAnalysis));
                Files.writeString(dir.resolve("capture-" + System.currentTimeMillis() + ".json"), saved.toPrettyString());
                ObjectNode n = M.createObjectNode();
                n.put("observed", lastAnalysis.observed);
                n.put("matched", lastAnalysis.matched);
                n.set("flow", M.valueToTree(lastAnalysis.flow));
                n.set("newCalls", M.valueToTree(lastAnalysis.newCalls));
                n.set("neverSeen", M.valueToTree(lastAnalysis.neverSeen));
                n.put("proposed", lastAnalysis.proposedEndpoints.size());
                send(ex, 200, n);
                return;
            }
            case "apply": {
                if (lastAnalysis == null) throw new IllegalStateException("Stop a capture first");
                ApiModel m = store.load(project);
                Set<String> have = new HashSet<>();
                m.endpoints.forEach(e -> have.add(e.id));
                int added = 0;
                for (int i = 0; i < lastAnalysis.proposedEndpoints.size(); i++) {
                    Endpoint e = lastAnalysis.proposedEndpoints.get(i);
                    if (have.contains(e.id)) continue;
                    m.endpoints.add(e);
                    m.testCases.add(lastAnalysis.proposedTests.get(i));
                    have.add(e.id);
                    added++;
                }
                if (added > 0) store.save(project, m, null, "added " + added + " endpoints captured from a browser session (DRAFT)");
                send(ex, 200, M.createObjectNode().put("added", added));
                return;
            }
            default:
                throw new IllegalArgumentException("Unknown capture action " + action);
        }
    }

    private void runRoute(HttpExchange ex, String rest, String method) throws IOException {
        String[] seg = rest.split("/");
        RunService.Run r = runs.get(seg[0]);
        if (r == null) {
            send(ex, 404, err("Unknown run"));
            return;
        }
        if (seg.length > 1 && "cancel".equals(seg[1])) {
            runs.cancel(r.id);
            send(ex, 200, ok());
            return;
        }
        if (seg.length > 1 && "events".equals(seg[1])) {
            int from = 0;
            String q = ex.getRequestURI().getQuery();
            if (q != null && q.matches(".*\\bfrom=\\d+.*")) from = Integer.parseInt(q.replaceAll(".*\\bfrom=(\\d+).*", "$1"));
            send(ex, 200, RunService.events(r, from));
            return;
        }
        if (seg.length > 2 && "result".equals(seg[1])) {
            TestResult tr = RunService.full(r, java.net.URLDecoder.decode(seg[2], StandardCharsets.UTF_8));
            if (tr == null) send(ex, 404, err("That test has not run yet"));
            else send(ex, 200, M.valueToTree(tr));
            return;
        }
        send(ex, 200, M.valueToTree(r));
    }

    // ------------------------------------------------------------------ handlers

    private void status(HttpExchange ex) throws IOException {
        ObjectNode n = M.createObjectNode();
        n.put("product", "API Quality Studio");
        n.put("knowledgeSections", kb.size());
        n.put("llmAvailable", llm.available());
        n.put("llmModel", llm.model());
        n.put("hardware", llm.hardware().summary());
        n.put("recommendedModel", llm.hardware().recommendedModel());
        n.put("recommendedReason", llm.hardware().reason);
        n.set("installedModels", M.valueToTree(llm.installedModels()));
        n.set("license", com.api.product.core.license.LicenseManager.toJson(com.api.product.core.license.LicenseManager.status()));
        n.put("assistantMode", llm.available() ? "rules + local model (" + llm.model() + ")" : "built-in expert engine (no model needed)");
        send(ex, 200, n);
    }

    private ArrayNode projectList() {
        ArrayNode a = M.createArrayNode();
        for (String p : store.projects()) {
            try {
                ApiModel m = store.load(p);
                ObjectNode n = a.addObject();
                n.put("name", p);
                n.put("api", m.api.name);
                n.put("demo", "demo".equals(m.api.source.type));
                n.put("endpoints", m.endpoints.size());
                n.put("tests", m.testCases.size());
                n.set("lifecycle", M.valueToTree(count(m.testCases.stream().map(t -> t.lifecycle).toArray(String[]::new))));
                List<TestResult> last = store.latestResults(p);
                n.set("lastRun", M.valueToTree(count(last.stream().map(t -> t.outcome).toArray(String[]::new))));
            } catch (Exception ignored) {
                // a broken model must not hide the other projects
            }
        }
        return a;
    }

    private ObjectNode projectDetail(String project) throws IOException {
        ApiModel m = store.load(project);
        ObjectNode n = M.createObjectNode();
        n.put("name", project);
        n.set("api", M.valueToTree(m.api));
        n.set("environments", M.valueToTree(m.environments.stream().map(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", e.name);
            x.put("baseUrl", e.baseUrl);
            return x;
        }).toArray()));
        ArrayNode eps = n.putArray("endpoints");
        for (Endpoint e : m.endpoints) {
            ObjectNode o = eps.addObject();
            o.put("id", e.id);
            o.put("name", e.name);
            o.put("module", e.module == null ? "General" : e.module);
            o.put("method", e.method);
            o.put("path", e.path);
            o.put("auth", e.authProfileRef);
            o.put("contract", e.responseSchema != null && e.responseSchema.isObject() && e.responseSchema.size() > 0);
            Map<String, String> ev = m.environments.isEmpty() ? new LinkedHashMap<>() : m.environments.get(0).variables;
            String envBase = resolveVars(m.environments.isEmpty() || m.environments.get(0).baseUrl == null ? "" : m.environments.get(0).baseUrl, ev);
            String rp = resolveVars(e.path == null ? "" : e.path, ev);
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("(?i)^(https?://[^/?#]+)(.*)$").matcher(rp);
            String eb = um.matches() ? um.group(1) : envBase;
            String ep2 = um.matches() ? (um.group(2).isEmpty() ? "/" : um.group(2)) : rp;
            o.put("rawPath", e.path);
            o.put("baseUrl", eb);
            o.put("apiPath", ep2);
            o.put("fullUrl", eb + (ep2.startsWith("/") || eb.isEmpty() ? "" : "/") + ep2);
            o.put("unresolved", (eb + ep2).contains("${"));
        }
        n.set("tests", M.valueToTree(m.testCases));
        ObjectNode last = n.putObject("last");
        for (TestResult r : store.latestResults(project)) {
            ObjectNode o = last.putObject(r.testCaseId);
            o.put("outcome", r.outcome);
            if (r.httpStatus != null) o.put("http", r.httpStatus);
            o.put("remark", r.remark);
        }
        n.set("docs", docList(project));
        return n;
    }

    private void updateTest(HttpExchange ex, String project, String id, JsonNode b) throws IOException {
        ApiModel m = store.load(project);
        TestCase t = m.testCases.stream().filter(x -> id.equals(x.id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown test " + id));
        boolean content = false;
        if (b.has("title")) t.title = b.get("title").asText();
        if (b.has("tags")) t.tags = M.convertValue(b.get("tags"), M.getTypeFactory().constructCollectionType(List.class, String.class));
        if (b.has("category") && !b.get("category").asText().equals(t.category)) {
            t.category = b.get("category").asText();
            content = true;
        }
        if (b.has("expected")) {
            ApiModel.Expected e = M.convertValue(b.get("expected"), ApiModel.Expected.class);
            if (!M.valueToTree(e).equals(M.valueToTree(t.expected))) content = true;
            t.expected = e;
            memory.voteStatus(project, t.category, e.status);
        }
        if (b.has("mutation")) {
            ApiModel.Mutation mu = b.get("mutation").isNull() ? null : M.convertValue(b.get("mutation"), ApiModel.Mutation.class);
            if (!M.valueToTree(mu).equals(M.valueToTree(t.mutation))) content = true;
            t.mutation = mu;
        }
        String note = "edited " + id;
        if (content) {
            t.version++;
            if (!"DRAFT".equals(t.lifecycle)) {
                note += " (v" + t.version + ", back to DRAFT: content changed after " + t.lifecycle + ")";
                t.lifecycle = "DRAFT";
            }
        }
        store.save(project, m, null, note);
        send(ex, 200, M.valueToTree(t));
    }

    private void bulk(HttpExchange ex, String project, JsonNode b) throws IOException {
        ApiModel m = store.load(project);
        String action = b.path("action").asText();
        Set<String> ids = new HashSet<>();
        b.path("ids").forEach(n -> ids.add(n.asText()));
        int changed = 0;
        List<String> skipped = new ArrayList<>();
        if ("DELETE".equals(action)) {
            for (TestCase t : new ArrayList<>(m.testCases)) {
                if (ids.contains(t.id) && ("DRAFT".equals(t.lifecycle) || "REVIEWED".equals(t.lifecycle))) {
                    m.testCases.remove(t);
                    changed++;
                } else if (ids.contains(t.id)) {
                    skipped.add(t.id + " (only DRAFT/REVIEWED tests can be deleted)");
                }
            }
        } else if (Set.of("DRAFT", "REVIEWED", "APPROVED", "ACTIVE").contains(action)) {
            for (TestCase t : m.testCases) {
                if (!ids.contains(t.id)) continue;
                if (t.lifecycle.equals(action)) continue;
                if ("DRAFT".equals(action) || NEXT_OK.contains(t.lifecycle + ">" + action)) {
                    t.lifecycle = action;
                    changed++;
                    if ("APPROVED".equals(action)) memory.accepted(project, t.category);
                } else {
                    skipped.add(t.id + " (" + t.lifecycle + " -> " + action + " is not allowed; steps are DRAFT, REVIEWED, APPROVED, ACTIVE)");
                }
            }
        } else {
            throw new IllegalArgumentException("Unknown action " + action);
        }
        if (changed > 0) store.save(project, m, null, action + " x" + changed);
        ObjectNode n = M.createObjectNode();
        n.put("changed", changed);
        n.set("skipped", M.valueToTree(skipped));
        send(ex, 200, n);
    }

    private void addTests(HttpExchange ex, String project, JsonNode b) throws IOException {
        ApiModel m = store.load(project);
        Set<String> have = new HashSet<>();
        m.testCases.forEach(t -> have.add(t.id));
        int added = 0;
        for (JsonNode n : b.path("tests")) {
            TestCase t = M.convertValue(n, TestCase.class);
            if (t.id == null || have.contains(t.id) || m.endpoint(t.endpointId) == null) continue;
            t.lifecycle = "DRAFT";
            m.testCases.add(t);
            have.add(t.id);
            added++;
        }
        if (added > 0) store.save(project, m, null, "added " + added + " generated DRAFT tests");
        send(ex, 200, M.createObjectNode().put("added", added));
    }

    private ArrayNode reportList(String project) throws IOException {
        ApiModel m = store.load(project);
        String dir = ProjectStore.safe(m.api.name);
        ArrayNode out = M.createArrayNode();
        Path runsFile = Paths.get("reports", dir, "runs.json");
        if (Files.exists(runsFile)) {
            JsonNode arr = M.readTree(runsFile.toFile());
            List<JsonNode> list = new ArrayList<>();
            arr.forEach(list::add);
            list.sort((a, b) -> Long.compare(b.path("epoch").asLong(), a.path("epoch").asLong()));
            for (JsonNode r : list) {
                ObjectNode o = ((ObjectNode) r).deepCopy();
                o.put("htmlUrl", "/files/" + dir + "/" + r.path("file").asText());
                o.put("excelUrl", "/files/" + dir + "/" + r.path("excel").asText());
                String consoleName = "Report_" + r.path("file").asText().replaceFirst("^Reports_", "").replaceFirst("\\.html$", "") + "_console.html";
                if (Files.exists(Paths.get("reports", dir, consoleName))) o.put("consoleUrl", "/files/" + dir + "/" + consoleName);
                out.add(o);
            }
        }
        return out;
    }

    private ArrayNode docList(String project) throws IOException {
        ArrayNode a = M.createArrayNode();
        Path d = store.dir(project).resolve("docs");
        if (Files.isDirectory(d)) {
            try (var ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) a.add(p.getFileName().toString());
            }
        }
        return a;
    }

    private void uploadDoc(HttpExchange ex, String project, JsonNode b) throws IOException {
        String name = b.path("filename").asText("").replaceAll("[^A-Za-z0-9._ -]", "_");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".docx") || lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".md")))
            throw new IllegalArgumentException("Only .docx, .pdf, .txt and .md documents are accepted");
        byte[] data = Base64.getDecoder().decode(b.path("contentBase64").asText(""));
        if (data.length > 20_000_000) throw new IllegalArgumentException("Document is larger than 20 MB");
        Path d = store.dir(project).resolve("docs");
        Files.createDirectories(d);
        Files.write(d.resolve(name), data);
        int n = kb.reloadProject(project);
        send(ex, 200, M.createObjectNode().put("saved", name).put("indexedFiles", n));
    }

    private void importProject(HttpExchange ex, JsonNode b) throws Exception {
        String name = ProjectStore.safe(b.path("name").asText(""));
        if (name.isBlank() || name.equals("project")) throw new IllegalArgumentException("Give the project a name");
        if (store.exists(name) && !b.path("overwrite").asBoolean(false)) throw new IllegalArgumentException("Project " + name + " already exists");
        requireLicense(!store.exists(name));
        Path file;
        if (b.hasNonNull("contentBase64")) {
            Path up = Paths.get("projects", "_uploads");
            Files.createDirectories(up);
            String fn = b.path("filename").asText("upload.json").replaceAll("[^A-Za-z0-9._-]", "_");
            if (extOf(fn).isEmpty()) fn += ".json";
            file = up.resolve(System.currentTimeMillis() + "_" + fn);
            byte[] data = Base64.getDecoder().decode(b.get("contentBase64").asText());
            if (data.length > 30_000_000) throw new IllegalArgumentException("File is larger than 30 MB");
            Files.write(file, data);
        } else if (b.hasNonNull("gqlUrl") || b.hasNonNull("wsUrl") || b.hasNonNull("grpcUrl")) {
            file = null;
        } else {
            file = Paths.get(b.path("path").asText(""));
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("File not found: " + file);
        }
        String lifecycle = b.path("lifecycle").asText("DRAFT");
        ApiModel model = b.hasNonNull("grpcUrl") ? grpc(b, lifecycle, new String[1]) : b.hasNonNull("gqlUrl") ? graphQl(b, lifecycle, new String[1]) : b.hasNonNull("wsUrl") ? webSocket(b, lifecycle, new String[1])
                : parseSource(file, b.hasNonNull("contentBase64") ? b.path("filename").asText(null) : null, lifecycle, new String[1]);
        model.api.name = name;
        if (b.hasNonNull("contentBase64")) model.api.source.ref = b.path("filename").asText(model.api.source.ref);
        if (b.hasNonNull("baseUrl") && !b.get("baseUrl").asText().isBlank()) model.environments.get(0).baseUrl = b.get("baseUrl").asText();
        int moved = CredentialSetup.secure(name, model, false);
        store.save(name, model, null, "imported from " + (file == null ? b.path("gqlUrl").asText(b.path("wsUrl").asText(b.path("grpcUrl").asText())) : file.getFileName().toString()) + (moved > 0 ? "; " + moved + " secrets moved out of the project file" : ""));
        kb.reloadProject(name);
        ObjectNode n = M.createObjectNode();
        n.put("project", name);
        n.put("secretsMoved", moved);
        Endpoint lg = CredentialSetup.guessLogin(model);
        n.put("loginEndpointId", lg == null ? "" : lg.id);
        n.put("endpoints", model.endpoints.size());
        n.put("tests", model.testCases.size());
        n.set("warnings", M.valueToTree(model.api.source.warnings));
        send(ex, 200, n);
    }

    // ------------------------------------------------------------------ plumbing

    private void serveFile(HttpExchange ex, String rel) throws IOException {
        Path root = Paths.get("reports").toAbsolutePath().normalize();
        Path p = root.resolve(java.net.URLDecoder.decode(rel, StandardCharsets.UTF_8)).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) {
            send(ex, 404, err("Not found"));
            return;
        }
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        String ct = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : n.endsWith(".json") ? "application/json"
                : n.endsWith(".png") ? "image/png" : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Disposition", n.endsWith(".xlsx") ? "attachment; filename=\"" + n + "\"" : "inline");
        sendBytes(ex, 200, ct, Files.readAllBytes(p));
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readAllBytes();
            return b.length == 0 ? M.createObjectNode() : M.readTree(b);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = StudioServer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            return in.readAllBytes();
        }
    }

    private static void send(HttpExchange ex, int code, JsonNode n) throws IOException {
        sendBytes(ex, code, "application/json; charset=utf-8", n.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String ct, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (ct.startsWith("text/html")) {
            // the UI and the generated reports are self-contained: nothing may be loaded from or sent to another origin, and nobody may frame them
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; "
                    + "connect-src 'self'; frame-src 'self'; frame-ancestors 'self'; base-uri 'none'; form-action 'self'; object-src 'none'");
            ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        }
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static ObjectNode err(String m) {
        return M.createObjectNode().put("error", m == null ? "error" : m);
    }

    private static ObjectNode ok() {
        return M.createObjectNode().put("ok", true);
    }

    private static Map<String, Integer> count(String[] values) {
        Map<String, Integer> c = new TreeMap<>();
        for (String v : values) c.merge(v == null ? "-" : v, 1, Integer::sum);
        return c;
    }
}
