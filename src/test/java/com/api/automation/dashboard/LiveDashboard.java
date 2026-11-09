package com.api.automation.dashboard;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.api.automation.config.ConfigManager;
import com.api.automation.otp.OtpResult;
import com.api.automation.otp.PendingOtp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Live HTML dashboard: http://localhost:8095
 * Shows which endpoint is running, results, request/response, and the OTP popup.
 * At the end of each project the same page is saved as a static HTML report.
 */
public final class LiveDashboard {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_EVENTS = 400;

    private static HttpServer server;
    private static ExecutorService executor;
    private static String url;
    private static volatile long lastClientPoll;
    private static final List<RunView> runs = new CopyOnWriteArrayList<>();
    private static volatile RunView current;
    private static volatile PendingOtp pendingOtp;

    private LiveDashboard() {}

    // ════════════════════════════════════════════════════════════════════
    //  SERVER
    // ════════════════════════════════════════════════════════════════════
    public static synchronized void start() {
        if (server != null || !ConfigManager.getBool("dashboard.enabled", true)) return;

        String bind = ConfigManager.get("dashboard.bind", "127.0.0.1");
        int port = ConfigManager.getInt("dashboard.port", 8095);

        // Created from a daemon thread so the server never keeps the JVM alive.
        Thread starter = new Thread(() -> {
            for (int p = port; p < port + 10; p++) {
                try {
                    HttpServer s = HttpServer.create(new InetSocketAddress(bind, p), 0);
                    s.createContext("/", LiveDashboard::handlePage);
                    s.createContext("/api/state", LiveDashboard::handleState);
                    s.createContext("/api/otp", LiveDashboard::handleOtp);
                    s.createContext("/api/control", LiveDashboard::handleControl);
                    s.createContext("/report", LiveDashboard::handleReport);
                    s.createContext("/api/rec", LiveDashboard::handleRec);
                    s.createContext("/api/open", LiveDashboard::handleOpen);
                    executor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "dashboard-http");
                        t.setDaemon(true);
                        return t;
                    });
                    s.setExecutor(executor);
                    s.start();
                    server = s;
                    url = "http://localhost:" + p + "/";
                    return;
                } catch (BindException e) {
                    // port busy (another run open?) → try next
                } catch (IOException e) {
                    System.out.println("⚠ Dashboard could not start: " + e.getMessage());
                    return;
                }
            }
        }, "dashboard-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (server == null) return;
        System.out.println("🖥  Live dashboard: " + url);

        if (ConfigManager.getBool("dashboard.auto.open", true)) {
            openBrowser(url);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
            server = null;
        }
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static boolean isClientConnected() {
        return System.currentTimeMillis() - lastClientPoll < 5000;
    }

    public static String url() {
        return url;
    }

    /** Opens a saved report (or any local file) in the default browser. */
    public static void openFile(Path file) {
        openBrowser(file.toAbsolutePath().toUri().toString());
    }

    private static void openBrowser(String target) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(target));
            } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", target).start();
            }
        } catch (Exception e) {
            System.out.println("Open the dashboard manually: " + target);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  RUN / TEST EVENTS (called by runner & engine)
    // ════════════════════════════════════════════════════════════════════
    public static void startRun(String project, String displayName, String env, String baseUrl) {
        RunView run = new RunView();
        run.runId = project + "-" + System.currentTimeMillis();
        run.project = project;
        run.displayName = displayName;
        run.env = env;
        run.baseUrl = baseUrl;
        run.startedAt = System.currentTimeMillis();
        run.testedBy = ConfigManager.get("tester.name", System.getProperty("user.name", ""));
        runs.add(run);
        current = run;
        log("INFO", null, "Execution started for " + displayName + " (" + env + ")");
    }

    public static void queueTest(TestView t) {
        RunView run = current;
        if (run != null && run.find(t.id) == null) run.tests.add(t);
    }

    /** Tests that will not run after all (negatives of an unusable API) leave the console. */
    public static void unqueue(java.util.Collection<String> ids) {
        RunView run = current;
        if (run == null) return;
        run.tests.removeIf(t -> ids.contains(t.id) && "QUEUED".equals(t.status));
    }

    /**
     * Planned tests that will not run after all (negatives of an API whose endpoint is not usable).
     * They STAY in the total and are shown as NA, so the total test count never changes during a run.
     */
    public static void markNotExecutable(java.util.Collection<String> ids, String category, String reason, String action) {
        RunView run = current;
        if (run == null) return;
        long now = System.currentTimeMillis();
        int n = 0;
        String first = null;
        for (TestView t : run.tests) {
            if (ids.contains(t.id) && "QUEUED".equals(t.status)) {
                t.status = "NA";
                t.category = category;
                t.reason = reason;
                t.remark = reason;
                t.action = action;
                t.startedAt = t.finishedAt = now;
                if (first == null) first = t.tcId;
                n++;
            }
        }
        if (n > 0) log("WARN", null, first + " +" + (n - 1) + " more  NOT EXECUTABLE - " + reason);
    }

    /** A row that is already decided (e.g. "not executable"), placed after its API. */
    public static void addFinished(TestView t) {
        RunView run = current;
        if (run == null) return;
        int at = run.tests.size();
        String base = t.tcId.replaceAll("_NA$", "");
        for (int i = 0; i < run.tests.size(); i++) {
            if (run.tests.get(i).tcId.startsWith(base)) at = i + 1;
        }
        run.tests.add(Math.min(at, run.tests.size()), t);
        log("WARN", null, t.tcId + "  NOT EXECUTABLE - " + t.reason);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAUSE / RESUME  (the current API finishes, the next one waits)
    // ════════════════════════════════════════════════════════════════════
    private static final Object PAUSE_LOCK = new Object();

    public static void pause() {
        RunView run = current;
        if (run == null || run.paused || !"RUNNING".equals(run.status)) return;
        run.paused = true;
        run.pausedAt = System.currentTimeMillis();
        log("WARN", null, "⏸ Execution PAUSED by tester - the running API finishes, the next one waits");
        System.out.println("⏸  Execution paused from the dashboard - press Resume to continue");
    }

    public static void resume() {
        RunView run = current;
        if (run == null || !run.paused) return;
        run.pausedTotalMs += System.currentTimeMillis() - run.pausedAt;
        run.paused = false;
        log("INFO", null, "▶ Execution RESUMED");
        System.out.println("▶  Execution resumed");
        synchronized (PAUSE_LOCK) {
            PAUSE_LOCK.notifyAll();
        }
    }

    /** Called before every test: waits while the tester has paused the run. */
    public static void awaitIfPaused() {
        RunView run = current;
        while (run != null && run.paused) {
            synchronized (PAUSE_LOCK) {
                try {
                    PAUSE_LOCK.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static TestView test(String id) {
        RunView run = current;
        return run == null ? null : run.find(id);
    }

    public static void testStarted(String id) {
        TestView t = test(id);
        if (t == null) return;
        t.status = "RUNNING";
        t.startedAt = System.currentTimeMillis();
        current.currentTestId = id;
    }

    public static void step(String id, String text) {
        TestView t = test(id);
        if (t != null) t.step = text;
        log("STEP", id, text);
    }

    public static void setStatus(String id, String status) {
        TestView t = test(id);
        if (t != null) t.status = status;
    }

    public static void testFinished(String id) {
        TestView t = test(id);
        if (t == null) return;
        t.finishedAt = System.currentTimeMillis();
        t.step = null;
        String level = "PASS".equals(t.status) ? "PASS" : "FAIL".equals(t.status) ? "FAIL" : "WARN";
        log(level, id, t.status + "  " + (t.apiName == null ? "" : t.apiName)
                + (t.httpStatus == null ? "" : "  · HTTP " + t.httpStatus)
                + (t.reason == null || t.reason.isBlank() || "PASS".equals(t.status) ? "" : "  · " + t.reason));
    }

    public static void setAiStatus(String status) {
        if (current != null) current.aiStatus = status;
    }

    /** The VAPT and failure reports of this run (called before finishRun, so the saved report links to them). */
    public static void setPdfReports(Path vapt, Path failure, String note) {
        RunView run = current;
        if (run == null) return;
        run.vaptPdf = vapt == null ? null : vapt.toAbsolutePath().toString();
        run.failPdf = failure == null ? null : failure.toAbsolutePath().toString();
        run.pdfNote = note == null || note.isEmpty() ? null : note;
    }

    public static void finishRun(String excelPath, String htmlPath) {
        RunView run = current;
        if (run == null) return;
        if (run.paused) resume();
        run.status = "COMPLETED";
        run.finishedAt = System.currentTimeMillis();
        run.currentTestId = null;
        run.excelReport = excelPath;
        run.htmlReport = htmlPath;
        try {
            Path base = com.api.automation.config.ProjectContext.reportBase();
            run.htmlReportUrl = reportUrl(base, htmlPath);
            run.excelReportUrl = reportUrl(base, excelPath);
            run.hubUrl = "/report/index.html";
            if (run.vaptPdf != null) run.vaptPdfUrl = reportUrl(base, run.vaptPdf);
            if (run.failPdf != null) run.failPdfUrl = reportUrl(base, run.failPdf);
        } catch (Exception ignored) {
            // links are a convenience - the run result does not depend on them
        }
        log("INFO", null, "Execution completed. Reports saved.");
    }

    public static RunView currentRun() {
        return current;
    }

    public static void log(String level, String testId, String text) {
        RunView run = current;
        String tc = testId;
        if (run != null && testId != null) {
            TestView t = run.find(testId);
            if (t != null) tc = t.tcId;
        }
        if (run == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", System.currentTimeMillis());
        e.put("level", level);
        e.put("tc", tc);
        e.put("text", text);
        run.events.add(e);
        while (run.events.size() > MAX_EVENTS) run.events.remove(0);
    }

    // ════════════════════════════════════════════════════════════════════
    //  OTP
    // ════════════════════════════════════════════════════════════════════
    public static void showOtp(PendingOtp p) {
        pendingOtp = p;
        if (current != null && current.currentTestId != null) setStatus(current.currentTestId, "WAITING_OTP");
    }

    public static void clearOtp(PendingOtp p) {
        if (pendingOtp == p) pendingOtp = null;
        if (current != null && current.currentTestId != null) {
            TestView t = test(current.currentTestId);
            if (t != null && "WAITING_OTP".equals(t.status)) t.status = "RUNNING";
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SNAPSHOT (JSON for the page) + STATIC HTML REPORT
    // ════════════════════════════════════════════════════════════════════
    public static Map<String, Object> snapshot(RunView only) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("serverTime", System.currentTimeMillis());
        if (only == null) s.put("pageVersion", pageVersion());
        s.put("runs", only != null ? List.of(only) : new ArrayList<>(runs));
        s.put("currentRunId", only != null ? only.runId : current == null ? null : current.runId);

        PendingOtp p = only == null ? pendingOtp : null;
        if (p != null && !p.future.isDone()) {
            Map<String, Object> otp = new LinkedHashMap<>();
            otp.put("request", p.request);
            otp.put("remainingMs", p.remainingMs());
            otp.put("expired", p.isExpired());
            s.put("otp", otp);
        }
        return s;
    }

    public static void writeStaticReport(RunView run, Path out) throws IOException {
        String html = loadPage();
        Map<String, Object> state = snapshot(run);
        state.put("static", true);
        String json = M.writeValueAsString(state).replace("</", "<\\/");
        html = html.replace("<!--STATIC_STATE-->", "<script>window.__STATIC_STATE__ = " + json + ";</script>");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════
    //  HTTP HANDLERS
    // ════════════════════════════════════════════════════════════════════
    private static void handlePage(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", loadPage().replace("__PAGE_VERSION__", pageVersion()));
    }

    /** Changes whenever dashboard.html changes; an open tab reloads itself to get the new page. */
    private static String pageVersion() {
        try {
            return Integer.toHexString(loadPage().hashCode());
        } catch (IOException e) {
            return "0";
        }
    }

    private static void handleState(HttpExchange ex) throws IOException {
        lastClientPoll = System.currentTimeMillis();
        send(ex, 200, "application/json; charset=utf-8", M.writeValueAsString(snapshot(null)));
    }

    private static void handleOtp(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        // OTP can be answered only from this PC, even if the dashboard is shared on LAN
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "application/json", "{\"ok\":false,\"error\":\"OTP can be entered only on the test machine\"}");
            return;
        }
        JsonNode body;
        try (InputStream in = ex.getRequestBody()) {
            body = M.readTree(in);
        }
        PendingOtp p = pendingOtp;
        if (p == null || p.future.isDone() || !p.request.id.equals(body.path("id").asText())) {
            send(ex, 409, "application/json", "{\"ok\":false,\"error\":\"This OTP request is no longer active\"}");
            return;
        }

        String action = body.path("action").asText();
        String error = null;
        switch (action) {
            case "submit":
                String otp = body.path("otp").asText("");
                error = PendingOtp.validate(otp);
                if (error == null && p.isExpired()) error = "Time expired - press Retry first";
                if (error == null) p.complete(OtpResult.submit(otp, "dashboard"));
                break;
            case "retry":
                p.restart();
                break;
            case "expire":
                p.expireNow();
                break;
            case "resend":
                p.complete(OtpResult.resend("dashboard"));
                break;
            case "skip":
                p.complete(OtpResult.skip("Skipped by tester"));
                break;
            default:
                error = "Unknown action";
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", error == null);
        res.put("error", error);
        send(ex, error == null ? 200 : 400, "application/json", M.writeValueAsString(res));
    }

    // ════════════════════════════════════════════════════════════════════
    //  SCREEN RECORDING  (only when the runner enables it)
    //  The browser records the Chrome window and streams the video to /api/rec.
    //  The run waits at the start until the tester pressed "Start recording" (browsers need that click).
    // ════════════════════════════════════════════════════════════════════
    private static final long REC_MAX_BYTES = 3L * 1024 * 1024 * 1024;

    /** Called by the runner when it has screenRecording() = true. */
    public static void enableRecording(String baseName, Path dir) {
        RunView run = current;
        if (run == null) return;
        run.recEnabled = true;
        run.recState = "WAITING";
        run.recName = baseName;
        run.recDir = dir.toAbsolutePath().toString();
        log("INFO", null, "\uD83C\uDFA5 Screen recording is ON for this runner - press 'Start recording' in the console");
    }

    /** Blocks until the tester started the recording, skipped it, or the time is up. true = recording now. */
    public static boolean awaitRecordingStart(int timeoutSec) {
        RunView run = current;
        if (run == null || !run.recEnabled) return false;
        long t0 = System.currentTimeMillis();
        long end = t0 + timeoutSec * 1000L;
        long nextHint = t0 + 15000L;
        String where = url == null ? "the Live dashboard in your browser" : url;
        while (System.currentTimeMillis() < end && "WAITING".equals(run.recState)) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            // no console page opened at all: nobody can press the button, do not make the run wait
            if (now - t0 > 25000L && lastClientPoll < t0) {
                run.recState = "SKIPPED";
                run.recMessage = "The console page was not open (" + where + ") - the run continued without recording";
                break;
            }
            if (now >= nextHint) {
                nextHint = now + 15000L;
                System.out.println("⏳ The run is WAITING for 'Start recording': open " + where + ", press the red button and choose the Chrome window ("
                        + Math.max(0, (end - now) / 1000) + " s left, then it continues without recording)");
            }
        }
        if ("WAITING".equals(run.recState)) {
            run.recState = "SKIPPED";
            run.recMessage = "Recording was not started within " + timeoutSec + " s - the run continued without it";
        }
        if ("SKIPPED".equals(run.recState)) {
            log("WARN", null, run.recMessage);
            System.out.println("⚠ " + run.recMessage);
        }
        return "RECORDING".equals(run.recState);
    }

    /** After the report is written: waits for the console to take the final screenshot and stop the recording. */
    public static void awaitRecordingStop(int timeoutSec) {
        RunView run = current;
        if (run == null || !run.recEnabled) return;
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end && "RECORDING".equals(run.recState)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if ("RECORDING".equals(run.recState)) {
            run.recState = "STOPPED";
            run.recStoppedAt = System.currentTimeMillis();
            run.recMessage = "The console did not confirm the end of the recording - the video saved so far is kept";
        }
    }

    private static String safeName(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** POST /api/rec?a=state | chunk | shot  - from this PC only. */
    private static void handleRec(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) || !ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "application/json", "{\"ok\":false}");
            return;
        }
        RunView run = current;
        if (run == null || !run.recEnabled || run.recDir == null) {
            send(ex, 409, "application/json", "{\"ok\":false,\"error\":\"Screen recording is not enabled for this run\"}");
            return;
        }
        String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String action = q.replaceAll("^(?:.*&)?a=([a-z]+).*$", "$1");
        Path dir = Path.of(run.recDir);
        Files.createDirectories(dir);
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }
        switch (action) {
            case "state": {
                JsonNode n = M.readTree(body);
                String st = n.path("state").asText("");
                if (!java.util.Set.of("RECORDING", "SKIPPED", "STOPPED", "ERROR").contains(st)) {
                    send(ex, 400, "application/json", "{\"ok\":false}");
                    return;
                }
                run.recState = st;
                run.recMessage = n.path("message").asText(null);
                long now = System.currentTimeMillis();
                if ("RECORDING".equals(st)) {
                    run.recStartedAt = now;
                    log("INFO", null, "\uD83C\uDFA5 Screen recording started");
                } else {
                    run.recStoppedAt = now;
                    log("INFO", null, "\uD83C\uDFA5 Screen recording " + st.toLowerCase()
                            + (run.recMessage == null ? "" : " - " + run.recMessage));
                    if ("STOPPED".equals(st) && run.recFile != null) afterRecording(run);
                }
                break;
            }
            case "chunk": {
                Path f = dir.resolve(safeName(run.recName) + ".webm").normalize();
                if (!f.startsWith(dir.toAbsolutePath().normalize()) || (Files.exists(f) && Files.size(f) + body.length > REC_MAX_BYTES)) {
                    send(ex, 413, "application/json", "{\"ok\":false,\"error\":\"Recording is too large\"}");
                    return;
                }
                Files.write(f, body, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                run.recFile = f.getFileName().toString();
                break;
            }
            case "shot": {
                Path f = dir.resolve(safeName(run.recName) + "_final.png").normalize();
                if (!f.startsWith(dir.toAbsolutePath().normalize())) {
                    send(ex, 403, "application/json", "{\"ok\":false}");
                    return;
                }
                Files.write(f, body);
                run.recShot = f.getFileName().toString();
                log("INFO", null, "\uD83D\uDCF8 Final screenshot saved: " + run.recShot);
                break;
            }
            default:
                send(ex, 400, "application/json", "{\"ok\":false,\"error\":\"Unknown action\"}");
                return;
        }
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    /** The video and screenshot are final: refresh the saved HTML report, its summary and the reports hub. */
    private static void afterRecording(RunView run) {
        try {
            if (run.htmlReport == null) return;
            if (run.recFile != null && run.recDir != null) {
                // Chrome writes a live-stream WebM (no duration, no seek index): repair it so the timeline can be clicked / forwarded
                Path video = Path.of(run.recDir).resolve(run.recFile);
                boolean fixed = com.api.automation.report.WebmFixer.fix(video,
                        com.api.automation.config.ConfigManager.getBool("recording.keep.raw", true));
                log("INFO", null, fixed ? "🎥 Recording made seekable (duration + timeline added)"
                        : "🎥 Recording left as recorded (could not add the seek index)");
            }
            Path html = Path.of(run.htmlReport);
            writeStaticReport(run, html);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("recording", run.recFile);
            extra.put("screenshot", run.recShot);
            extra.put("recordingMs", run.recStoppedAt > run.recStartedAt ? run.recStoppedAt - run.recStartedAt : null);
            com.api.automation.report.ReportHub.updateSidecar(html, extra);
            com.api.automation.report.ReportHub.write(com.api.automation.config.ProjectContext.reportBase());
        } catch (Exception e) {
            System.out.println("\u26A0 Could not refresh the report after the recording: " + e.getMessage());
        }
    }

    /**
     * POST /api/open {"what":"excel|html|folder|recording|hub"} - opens the file with its own program
     * (Excel for the .xlsx) or shows the folder. Only files of the current run, only from this PC.
     */
    private static void handleOpen(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) || !ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "application/json", "{\"ok\":false}");
            return;
        }
        JsonNode n;
        try (InputStream in = ex.getRequestBody()) {
            n = M.readTree(in);
        }
        RunView run = current;
        Path target = null;
        if (run != null && run.htmlReport != null) {
            Path html = Path.of(run.htmlReport);
            switch (n.path("what").asText()) {
                case "excel": target = run.excelReport == null ? null : Path.of(run.excelReport); break;
                case "html": target = html; break;
                case "vapt": target = run.vaptPdf == null ? null : Path.of(run.vaptPdf); break;
                case "failure": target = run.failPdf == null ? null : Path.of(run.failPdf); break;
                case "folder": target = html.getParent(); break;
                case "recording": target = run.recFile == null || run.recDir == null ? null : Path.of(run.recDir).resolve(Path.of(run.recFile).getFileName()); break;
                case "hub": target = com.api.automation.config.ProjectContext.reportBase().resolve("index.html"); break;
                default: break;
            }
        }
        boolean ok = false;
        String error = "Nothing to open yet";
        if (target != null && Files.exists(target)) {
            Path base = com.api.automation.config.ProjectContext.reportBase();
            if (!target.toAbsolutePath().normalize().startsWith(base)) {
                error = "Only files inside the reports folder can be opened";
            } else {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(target.toFile());
                    } else {
                        new ProcessBuilder("cmd", "/c", "start", "", target.toAbsolutePath().toString()).start();
                    }
                    ok = true;
                    error = null;
                } catch (Exception e) {
                    error = "Could not open it: " + e.getMessage();
                }
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", ok);
        res.put("error", error);
        send(ex, ok ? 200 : 409, "application/json", M.writeValueAsString(res));
    }

    /** /report/CKYC/Reports_CKYC_16-Oct-26_02-07-42.html  for a file below the reports folder. */
    private static String reportUrl(Path base, String file) {
        String rel = base.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/');
        StringBuilder b = new StringBuilder("/report");
        for (String part : rel.split("/")) {
            b.append('/').append(java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.toString();
    }

    /**
     * Serves the saved reports so "View report" works from the live console:
     *   /report/index.html            the reports hub (every run, project-wise)
     *   /report/CKYC/Reports_*.html   a saved report (and its response viewers / Excel)
     * Only from this PC, and only files below the reports folder (no path tricks).
     */
    private static void handleReport(HttpExchange ex) throws IOException {
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "text/plain", "Reports can be opened only on the test machine");
            return;
        }
        Path base = com.api.automation.config.ProjectContext.reportBase();
        String rel = java.net.URLDecoder.decode(ex.getRequestURI().getRawPath().replaceFirst("^/report/?", ""), StandardCharsets.UTF_8);
        if (rel.isBlank()) rel = "index.html";
        Path file = base.resolve(rel).normalize();
        if ("index.html".equals(rel) && !Files.exists(file)) {
            com.api.automation.report.ReportHub.write(base);
        }
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Report not found: " + rel);
            return;
        }
        String name = file.getFileName().toString().toLowerCase();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".json") ? "application/json; charset=utf-8"
                : name.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : name.endsWith(".txt") ? "text/plain; charset=utf-8"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".pdf") ? "application/pdf"
                : name.endsWith(".webm") ? "video/webm" : name.endsWith(".mp4") ? "video/mp4" : "application/octet-stream";
        if (name.endsWith(".webm") || name.endsWith(".mp4")) {   // big files: stream with Range so the player can seek
            serveRange(ex, file, type);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (name.endsWith(".xlsx")) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
        }
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void serveRange(HttpExchange ex, Path file, String type) throws IOException {
        long size = Files.size(file);
        long from = 0, to = size - 1;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] r = range.substring(6).split("-", 2);
            try {
                if (!r[0].isEmpty()) from = Long.parseLong(r[0]);
                if (r.length > 1 && !r[1].isEmpty()) to = Math.min(size - 1, Long.parseLong(r[1]));
            } catch (NumberFormatException ignored) {
                from = 0;
                to = size - 1;
            }
        }
        if (from > to) {
            ex.sendResponseHeaders(416, -1);
            return;
        }
        long len = to - from + 1;
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (range != null) ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
        ex.sendResponseHeaders(range != null ? 206 : 200, len);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r"); OutputStream os = ex.getResponseBody()) {
            raf.seek(from);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                os.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static void handleControl(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        // like the OTP: only the test machine can pause / resume the run
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(ex, 403, "application/json", "{\"ok\":false,\"error\":\"Pause / resume only on the test machine\"}");
            return;
        }
        JsonNode body;
        try (InputStream in = ex.getRequestBody()) {
            body = M.readTree(in);
        }
        String action = body == null ? "" : body.path("action").asText();
        if ("pause".equals(action)) pause();
        else if ("resume".equals(action)) resume();
        else {
            send(ex, 400, "application/json", "{\"ok\":false,\"error\":\"Unknown action\"}");
            return;
        }
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String loadPage() throws IOException {
        try (InputStream in = LiveDashboard.class.getResourceAsStream("/dashboard/dashboard.html")) {
            if (in == null) return "<h1>dashboard.html not found on classpath</h1>";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
