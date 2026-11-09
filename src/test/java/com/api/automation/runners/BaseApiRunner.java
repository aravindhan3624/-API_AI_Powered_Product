package com.api.automation.runners;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.api.automation.ai.AiVerifier;
import com.api.automation.config.ProjectContext;
import com.api.automation.dashboard.LiveDashboard;
import com.api.automation.dashboard.TestView;
import com.api.automation.engine.ExecutionEngine;
import com.api.automation.listeners.FrameworkListener;
import com.api.automation.otp.OtpContext;
import com.api.automation.payloads.PayloadResolver;
import com.api.automation.payloads.ResolvedPayload;
import com.api.automation.utils.ExcelUtils;
import com.api.automation.utils.GlobalStore;
import com.api.automation.utils.TestDecision;
import com.api.automation.utils.TokenManager;

/**
 * Common runner for every project. A project runner only says which project it is:
 *
 *   public class RNSB_Runner extends BaseApiRunner {
 *       protected String project() { return "RNSB"; }
 *   }
 *
 * Reads   src/test/resources/API_TestData/<PROJECT>/<PROJECT>_testdata.xlsx
 * Writes  reports/<PROJECT>/Reports_<PROJECT>_<dd-MMM-yy>_<HH-mm>.xlsx (+ .html)
 */
@Listeners(FrameworkListener.class)
public abstract class BaseApiRunner implements org.testng.ITest {

    static {
        com.api.automation.report.QuietLogging.init();   // no red logger noise in IntelliJ
    }

    // ── Readable test names in IntelliJ / Maven:  "TC015 · 01 Master > Get All Master Data" ──
    private final ThreadLocal<String> testName = ThreadLocal.withInitial(() -> "");

    @org.testng.annotations.BeforeMethod(alwaysRun = true)
    public void nameTest(java.lang.reflect.Method m, Object[] params) {
        if (params != null && params.length > 0 && params[0] instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> row = (Map<String, String>) params[0];
            ResolvedPayload p = params.length > 1 && params[1] instanceof ResolvedPayload ? (ResolvedPayload) params[1] : null;
            int idx = params.length > 2 && params[2] instanceof Integer ? (Integer) params[2] : 0;
            testName.set(ExecutionEngine.testCaseId(row, p, idx) + " - " + row.getOrDefault("API_Name", "").trim());
        }
    }

    @Override
    public String getTestName() {
        return testName.get();
    }

    /** Project code = folder name under API_TestData / payloads / reports. */
    protected abstract String project();

    /** What a runner executes and reports. */
    public enum RunMode { ALL, POSITIVE, NEGATIVE }

    /**
     * ALL      = positive + negative tests in one report (default)
     * POSITIVE = only positive tests
     * NEGATIVE = only the negative tests in the report (the positive calls still run first,
     *            because they discover today's customer ids and prove the API works)
     */
    protected RunMode runMode() {
        return RunMode.ALL;
    }

    /**
     * Screen recording of the Chrome window. OFF by default - a runner switches it on:
     *
     *     @Override protected boolean screenRecording() { return true; }
     *
     * When on, the run waits at the start until you press "Start recording" in the console (Chrome always needs
     * that click and its window picker), records until the report is generated, takes a final screenshot, and
     * saves both under reports/<PROJECT>/<date_time>/. Every runner shows this switch (false by default).
     */
    protected boolean screenRecording() {
        return false;
    }

    // Login row, used to log in again when negative input has broken the session
    private Map<String, String> loginRow;
    private String loginBody;

    // ── Result list (for Excel report) ─────────────────────────────────────
    private final List<Map<String, String>> finalReport = Collections.synchronizedList(new ArrayList<>());
    private long runStart;
    /** Values of the shared store right after each positive test (by TC_ID): its negatives run later with the SAME data. */
    private final Map<String, Map<String, String>> positiveSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    /** keys that the re-login before the negative phase refreshed (token, session ...): never overwritten by a snapshot */
    private final java.util.Set<String> sessionKeys = new java.util.HashSet<>();
    private int sheetRows, sheetRunN, sheetModuleSkipped;   // test-data rows: all / Run=N / Run=Y but outside the selected modules
    private boolean started;
    private String reportPath;

    // ── START PROJECT ─────────────────────────────────────────────────────
    @BeforeClass(alwaysRun = true)
    public void startProject() {
        ensureStarted();
    }

    private synchronized void ensureStarted() {
        ProjectContext.set(project());
        if (started) return;
        started = true;

        GlobalStore.clear();
        ExecutionEngine.POSITIVE_OUTCOME.clear();
        ExecutionEngine.POSITIVE_HTTP.clear();
        com.api.automation.negative.VaptRules.reset();
        OtpContext.clear();
        TokenManager.reset();
        com.api.automation.security.EnvelopeCrypto.reset();
        com.api.automation.security.SessionFilter.reset();
        AiVerifier.reset();
        runStart = System.currentTimeMillis();
        ExecutionEngine.ABORT_REASON = null;
        reportPath = generateReportPath();
        com.api.automation.report.ResponseStore.start(Path.of(reportPath.replaceAll("\\.xlsx$", "")));

        LiveDashboard.start();
        LiveDashboard.startRun(project(), ProjectContext.displayName(), ProjectContext.env(), ProjectContext.baseUrl());
        LiveDashboard.setAiStatus(AiVerifier.statusText());

        if (screenRecording()) {
            if (LiveDashboard.isRunning()) {
                // Recording_CKYC_16-Oct-26_02-07-42  (same stamp as the report of this run)
                String stem = Path.of(reportPath).getFileName().toString().replaceAll("\\.xlsx$", "").replaceFirst("^Reports_", "Recording_");
                LiveDashboard.enableRecording(stem, Path.of(reportPath).getParent());
                int wait = com.api.automation.config.ConfigManager.getInt("screen.recording.start.wait.seconds", 120);
                System.out.println("🎥 Screen recording is ON for this runner - press 'Start recording' in the console (waiting up to " + wait + " s)");
                LiveDashboard.awaitRecordingStart(wait);
            } else {
                System.out.println("⚠ screenRecording() is on but the live console is disabled (dashboard.enabled=false) - nothing is recorded");
            }
        }

        System.out.println("\n══════════════════════════════════════════════════════");
        System.out.println(" PROJECT  : " + ProjectContext.displayName() + "  [" + ProjectContext.env() + "]");
        System.out.println(" BASE URL : " + ProjectContext.baseUrl());
        System.out.println(" TEST DATA: " + ProjectContext.testDataPath());
        System.out.println(" " + AiVerifier.statusText());
        System.out.println("══════════════════════════════════════════════════════\n");
    }

    // ── DATA PROVIDER (EXPANDS EACH PAYLOAD AS A TEST) ────────────────────
    /** One Excel row: its positive test(s) and the negatives generated from it. */
    private static final class Group {
        Map<String, String> row;
        List<Object[]> positives = new ArrayList<>();
        List<Object[]> negatives = new ArrayList<>();
    }

    /**
     * Lazy data provider: TestNG asks for the next test only after the previous one finished, so the
     * negatives of an API are decided AFTER its positive test ran. When the endpoint cannot be used
     * (not deployed / no permission / not executed) the negatives are not run at all - the report gets
     * one clear "Not executable" row instead of 15-20 meaningless skips.
     */
    @DataProvider(name = "apiData")
    public java.util.Iterator<Object[]> getData() throws Exception {

        ensureStarted();
        List<Group> groups = new ArrayList<>();
        List<com.api.automation.report.TestDataCatalog.Entry> catalog = new ArrayList<>();

        List<Map<String, String>> rows = ExcelUtils.readData(ProjectContext.testDataPath());

        // which step provides each extracted value (used to explain skips clearly)
        for (Map<String, String> r : rows) {
            String ex = r.getOrDefault("Extract", "");
            if (ex == null || ex.isBlank() || ex.trim().equalsIgnoreCase("NA")) continue;
            for (String pair : ex.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) ExecutionEngine.VALUE_SOURCE.merge(pair.substring(0, eq).trim(),
                        r.getOrDefault("TC_ID", "") + " " + r.getOrDefault("API_Name", "").replaceAll(" \\(.*\\)$", ""),
                        (a, b) -> a.contains(b) || a.split(" / ").length >= 3 ? a : a + " / " + b);
            }
        }

        sheetRows = rows.size();
        sheetRunN = 0;
        sheetModuleSkipped = 0;
        for (Map<String, String> excelRow : rows) {
            Map<String, String> row = new TestRow(excelRow);

            if (!"Y".equalsIgnoreCase(row.getOrDefault("Run", "").trim())) {
                sheetRunN++;
                continue;
            }
            if (!inSelectedModules(row)) {
                sheetModuleSkipped++;
                continue;
            }
            Group g = new Group();
            g.row = row;
            groups.add(g);

            List<ResolvedPayload> payloads = PayloadResolver.resolve(
                    row.getOrDefault("Payload", "NA"),
                    ProjectContext.payloadFolder().toString(),
                    ProjectContext.sharedPayloadFolder().toString());

            // ✅ No payload → still one test
            if (payloads.isEmpty()) {
                g.positives.add(new Object[]{row, null, 0});
                queue(row, null, 0);
                catalog.add(com.api.automation.report.TestDataCatalog.positive(row, null, ExecutionEngine.testCaseId(row, null, 0)));
                continue;
            }
            for (int i = 0; i < payloads.size(); i++) {
                g.positives.add(new Object[]{row, payloads.get(i), i});
                queue(row, payloads.get(i), i);
                catalog.add(com.api.automation.report.TestDataCatalog.positive(row, payloads.get(i), ExecutionEngine.testCaseId(row, payloads.get(i), i)));
            }
            if (loginRow == null && row.getOrDefault("Endpoint", "").toLowerCase().contains("/auth/login")) {
                loginRow = row;
                loginBody = payloads.get(0).getBody();
            }
            // ── negative tests right after the positive: TC047_01, TC047_02 ... ──
            if (runMode() != RunMode.POSITIVE && com.api.automation.negative.NegativeGenerator.enabled()
                    && com.api.automation.negative.NegativeGenerator.eligible(row)) {
                int k = payloads.size();
                for (ResolvedPayload neg : com.api.automation.negative.NegativeGenerator.generate(row, payloads.get(0))) {
                    g.negatives.add(new Object[]{row, neg, k});
                    catalog.add(com.api.automation.report.TestDataCatalog.negative(row, neg, ExecutionEngine.testCaseId(row, neg, k)));
                    k++;
                }
            }
        }

        // run order: ALL positives first (the whole flow), then ALL negatives - the console lists them in that order
        boolean anyNegatives = false;
        for (Group g : groups) {
            for (Object[] o : g.negatives) {
                queue(g.row, (ResolvedPayload) o[1], (Integer) o[2]);
                anyNegatives = true;
            }
        }
        final boolean hasNegatives = anyNegatives;

        // one workbook with every positive + negative test (same place every run)
        java.nio.file.Path cat = com.api.automation.report.TestDataCatalog.write(
                java.nio.file.Paths.get(ProjectContext.testDataPath()).getParent().resolve("generated"), project(), catalog);
        if (cat != null) System.out.println(" TEST DATA (positive + negative): " + cat);

        return new java.util.Iterator<Object[]>() {
            private int gi = 0, pi = 0, ni = 0, ii = 0;
            private boolean phase2, negChecked, phase3;
            private List<Object[]> idor = new ArrayList<>();

            @Override
            public boolean hasNext() {
                if (!phase2) {
                    // PHASE 1 - every positive test, in the order of the sheet (the valid flow gives the data the negatives need)
                    while (gi < groups.size()) {
                        if (pi < groups.get(gi).positives.size()) return true;
                        gi++;
                        pi = 0;
                    }
                    phase2 = true;
                    gi = 0;
                    ni = 0;
                    negChecked = false;
                    if (hasNegatives) beginNegativePhase();
                }
                // PHASE 2 - the negatives, API by API, each with the data of its own positive test
                while (gi < groups.size()) {
                    Group g = groups.get(gi);
                    if (!g.negatives.isEmpty() && !negChecked) {
                        negChecked = true;
                        String tc = g.row.getOrDefault("TC_ID", "");
                        String blocked = ExecutionEngine.negativesBlockedReason(tc);
                        if (blocked != null) {
                            notExecutable(g, blocked);
                            ni = g.negatives.size();
                        } else {
                            restorePositiveData(tc);
                        }
                    }
                    if (ni < g.negatives.size()) return true;
                    gi++;
                    ni = 0;
                    negChecked = false;
                }
                // PHASE 3 (optional, idor.enabled=true) - two-user authorization check: user B replays user A's requests
                if (!phase3) {
                    phase3 = true;
                    if (hasNegatives) idor = idorPhase(groups);
                }
                return ii < idor.size();
            }

            @Override
            public Object[] next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                if (phase3) {
                    Object[] o = idor.get(ii++);
                    restoreIdorData((String) ((Map<?, ?>) o[0]).get("TC_ID"));
                    return o;
                }
                Group g = groups.get(gi);
                return !phase2 ? g.positives.get(pi++) : g.negatives.get(ni++);
            }
        };
    }

    /** Start of the negative phase: the positive flow may have ended with a logout, so log in again and note which values the login refreshed. */
    private void beginNegativePhase() {
        if (loginRow == null || loginBody == null) return;
        Map<String, String> before = GlobalStore.getAll();
        System.out.println("\n\u25B6 All positive tests are done - negative tests start (logging in again for a fresh session)");
        LiveDashboard.log("INFO", null, "All positive tests are done - negative tests start (fresh login)");
        restoreSession(loginRow.getOrDefault("TC_ID", "TC001"));
        sessionKeys.clear();
        Map<String, String> after = GlobalStore.getAll();
        for (Map.Entry<String, String> e : after.entrySet()) {
            if (!java.util.Objects.equals(before.get(e.getKey()), e.getValue())) sessionKeys.add(e.getKey());
        }
    }

    /** Puts back the values the positive test of this API used (ids, codes ...), except the refreshed session values. */
    private void restorePositiveData(String tc) {
        Map<String, String> snap = positiveSnapshots.get(tc);
        if (snap == null) return;
        for (Map.Entry<String, String> e : snap.entrySet()) {
            if (!sessionKeys.contains(e.getKey())) GlobalStore.put(e.getKey(), e.getValue());
        }
    }

    // ── PHASE 3: two-user IDOR (broken object level authorization) check ──
    /** values of the shared store right after user A's call of each API (user A's own ids), by TC_ID */
    private final Map<String, Map<String, String>> idorSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    /** keys user B's login refreshed (token, session ...): never overwritten by user A's snapshot */
    private final java.util.Set<String> idorSessionKeys = new java.util.HashSet<>();

    /**
     * idor.enabled=true with two LOW-privileged users of different FI / branch (never the admin test user):
     *   <P>.idor.userA.loginId, <P>.idor.userA.password   (or environment variable IDOR_USER_A_PASSWORD)
     *   <P>.idor.userB.loginId, <P>.idor.userB.password   (or environment variable IDOR_USER_B_PASSWORD)
     *   idor.apis=customer,dvs,image,bulk,request,kyc       (path words of APIs whose records belong to a user / FI)
     * User A logs in and calls the read APIs in sheet order (its list calls give its own ids); user B then logs in and
     * sends user A's requests. FAIL only when user B receives user A's records (VaptRules). System-wide settings and
     * state-changing APIs are never used. Credentials are never printed or written to a report.
     */
    private List<Object[]> idorPhase(List<Group> groups) {
        List<Object[]> out = new ArrayList<>();
        if (!com.api.automation.config.ConfigManager.getBool("idor.enabled", false) || loginRow == null || loginBody == null) return out;
        String aId = cfg("idor.userA.loginId"), aPw = secret("idor.userA.password", "IDOR_USER_A_PASSWORD");
        String bId = cfg("idor.userB.loginId"), bPw = secret("idor.userB.password", "IDOR_USER_B_PASSWORD");
        if (aId.isEmpty() || aPw.isEmpty() || bId.isEmpty() || bPw.isEmpty()) {
            String msg = "Two-user IDOR check skipped: set idor.userA.loginId / idor.userA.password and idor.userB.loginId / idor.userB.password "
                    + "(two low-privileged users of different FI / branch) in config.properties";
            System.out.println("⚠ " + msg);
            LiveDashboard.log("WARN", null, msg);
            return out;
        }
        List<String> words = new ArrayList<>();
        for (String w : cfg("idor.apis").isEmpty() ? new String[]{"customer", "dvs", "image", "bulk", "request", "kyc"} : cfg("idor.apis").split(",")) {
            if (!w.isBlank()) words.add(w.trim().toLowerCase());
        }
        List<Group> eligible = new ArrayList<>();
        for (Group g : groups) {
            String tc = g.row.getOrDefault("TC_ID", "");
            String ep = g.row.getOrDefault("Endpoint", "");
            if (g.positives.isEmpty() || ExecutionEngine.POSITIVE_OUTCOME.get(tc) != com.api.automation.utils.TestOutcome.PASS) continue;
            if (com.api.automation.negative.ApiKind.of(g.row.getOrDefault("Method", ""), ep).writes()
                    || com.api.automation.negative.ApiKind.isSystemConfig(ep) || ep.toLowerCase().contains("/auth/")) continue;
            String path = com.api.automation.negative.ApiKind.path(ep).toLowerCase();
            if (words.stream().anyMatch(path::contains)) eligible.add(g);
        }
        if (eligible.isEmpty()) return out;
        System.out.println("\n▶ Two-user IDOR check: " + eligible.size() + " API(s) - user A first, then user B sends user A's requests");
        LiveDashboard.log("INFO", null, "Two-user IDOR check: " + eligible.size() + " API(s)");

        if (!loginAs(aId, aPw, "A")) {
            LiveDashboard.log("WARN", null, "Two-user IDOR check skipped: user A could not log in");
            return out;
        }
        for (Group g : eligible) {
            String tc = g.row.getOrDefault("TC_ID", "");
            String[] r = ExecutionEngine.callAsCurrentUser(g.row, (ResolvedPayload) g.positives.get(0)[1]);
            if (r == null) continue;
            com.api.automation.negative.VaptRules.IDOR_A.put(tc, Integer.parseInt(r[0]) < 400 ? r[1] : "");
            idorSnapshots.put(tc, new HashMap<>(GlobalStore.getAll()));
        }
        Map<String, String> before = GlobalStore.getAll();
        if (!loginAs(bId, bPw, "B")) {
            LiveDashboard.log("WARN", null, "Two-user IDOR check skipped: user B could not log in");
            return out;
        }
        for (Map.Entry<String, String> e : GlobalStore.getAll().entrySet()) {
            if (!java.util.Objects.equals(before.get(e.getKey()), e.getValue())) idorSessionKeys.add(e.getKey());
        }
        for (Group g : eligible) {
            String tc = g.row.getOrDefault("TC_ID", "");
            if (!com.api.automation.negative.VaptRules.IDOR_A.containsKey(tc)) continue;
            ResolvedPayload pos = (ResolvedPayload) g.positives.get(0)[1];
            ResolvedPayload p = ResolvedPayload.negative("IB", "OTHER_USER", "Another user's records (two users)",
                    "user A's request sent by user B (" + bId + ", other FI / branch)", "Refused (403), or none of user A's records returned",
                    pos.getBody()).withMode(ResolvedPayload.Mode.IDOR_B);
            if (pos.getEndpointOverride() != null) p.withEndpoint(pos.getEndpointOverride());
            queue(g.row, p, 0);
            out.add(new Object[]{g.row, p, 0});
        }
        return out;
    }

    /** Before each user-B request: user A's values for that API (except user B's own session values). */
    private void restoreIdorData(String tc) {
        Map<String, String> snap = idorSnapshots.get(tc);
        if (snap == null) return;
        for (Map.Entry<String, String> e : snap.entrySet()) {
            if (!idorSessionKeys.contains(e.getKey())) GlobalStore.put(e.getKey(), e.getValue());
        }
    }

    /** Logs in as an IDOR test user; the login id / password are only placed in the store for this one call. */
    private boolean loginAs(String id, String password, String label) {
        String oldId = GlobalStore.get("loginId"), oldPw = GlobalStore.get("password");
        GlobalStore.put("loginId", id);
        GlobalStore.put("password", password);
        try {
            String body = loginBody;
            try {
                com.fasterxml.jackson.databind.node.ObjectNode n = (com.fasterxml.jackson.databind.node.ObjectNode)
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginBody);
                n.put("sessionId", 1);
                body = n.toString();
            } catch (Exception ignored) { }
            ResolvedPayload p = ResolvedPayload.negative("I" + label, "RELOGIN", "IDOR user " + label + " login",
                    "login as IDOR user " + label, "Login Successful", body).asVerifyPositive();
            TestDecision d = ExecutionEngine.executeSingle(loginRow, p, 0, new HashMap<>());
            System.out.println("IDOR user " + label + " login: " + d.outcome);
            return d.outcome == com.api.automation.utils.TestOutcome.PASS;
        } finally {
            if (oldId == null) GlobalStore.remove("loginId"); else GlobalStore.put("loginId", oldId);
            if (oldPw == null) GlobalStore.remove("password"); else GlobalStore.put("password", oldPw);
        }
    }

    private static String cfg(String key) {
        String v = com.api.automation.config.ConfigManager.get(key, "");
        return v == null ? "" : v.trim();
    }

    private static String secret(String key, String env) {
        String v = cfg(key);
        if (v.isEmpty()) v = System.getenv(env) == null ? "" : System.getenv(env).trim();
        return v;
    }

    /** The negatives of one API cannot run: remove them from the console and write ONE explaining row. */
    private void notExecutable(Group g, String blocked) {
        String tc = g.row.getOrDefault("TC_ID", "");
        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (Object[] o : g.negatives) {
            ResolvedPayload p = (ResolvedPayload) o[1];
            ids.add(ExecutionEngine.testCaseId(g.row, p, (Integer) o[2]));
            if (!titles.contains(p.getNegTitle())) titles.add(p.getNegTitle());
        }

        String category = "";
        synchronized (finalReport) {
            for (Map<String, String> r : finalReport) {
                if (tc.equals(r.get("TC_ID"))) category = r.getOrDefault("Failure_Category", "");
            }
        }
        String reason = blocked + ". The " + g.negatives.size() + " negative checks (" + String.join(", ", titles)
                + ") were not run - a rejection from an unusable endpoint proves nothing.";
        Map<String, String> rr = new HashMap<>();
        rr.put("TC_ID", tc + "_NA");
        rr.put("Module", g.row.getOrDefault("Module", "").trim());
        rr.put("API_Name", g.row.getOrDefault("API_Name", "").trim());
        rr.put("Method", g.row.getOrDefault("Method", "").trim().toUpperCase());
        rr.put("Endpoint", g.row.getOrDefault("Endpoint", "").trim());
        rr.put("Negative", "Y");
        rr.put("NegCategory", "NOT_EXECUTABLE");
        rr.put("NegTitle", "Not executable (positive failed)");
        rr.put("TestData", g.negatives.size() + " negative checks not run");
        rr.put("NA_Count", String.valueOf(g.negatives.size()));
        rr.put("ExpectedResult", "Negative checks run once the positive test works");
        rr.put("ActualResult", "Not executed");
        rr.put("Result", "N/A");
        rr.put("Failure_Category", "NOT EXECUTABLE" + (category.isEmpty() ? "" : " (" + category + ")"));
        rr.put("Failure_Reason", reason);
        rr.put("Action_Needed", "Fix / deploy the API (see the positive test " + tc + "); its negatives then run automatically.");
        rr.put("Error", blocked);
        rr.put("Exec Time", "0 ms");
        if (runMode() != RunMode.POSITIVE) finalReport.add(rr);

        // the planned checks stay in the console total, shown as "Not executable" (total never shrinks)
        LiveDashboard.markNotExecutable(ids, "NOT EXECUTABLE", reason, "Fix / deploy the API (positive test " + tc + "); its negatives then run automatically.");
    }

    /**
     * Run only some modules:  -Dmodule=02            (one module)
     *                         -Dmodule=02,05,07      (several)
     *                         -Dmodule="DVS"         (by name)
     * Login / Logout rows (Module "00 Auth" with Login/Logout) are always kept so the session works.
     */
    private static boolean inSelectedModules(Map<String, String> row) {
        String sel = System.getProperty("module", "").trim();
        if (sel.isEmpty()) return true;
        String module = row.getOrDefault("Module", "").trim();
        String name = row.getOrDefault("API_Name", "").trim();
        if (name.matches("(?i)^(00 Auth > )?(Login.*|Logout)$")) return true;
        for (String s : sel.split(",")) {
            String k = s.trim().toLowerCase();
            if (k.isEmpty()) continue;
            if (module.toLowerCase().startsWith(k) || module.toLowerCase().contains(k)
                    || name.toLowerCase().startsWith(k)) return true;
        }
        return false;
    }

    private void queue(Map<String, String> row, ResolvedPayload payload, int index) {
        TestView t = new TestView();
        t.id = ExecutionEngine.testCaseId(row, payload, index);
        t.tcId = t.id;
        t.apiName = ExecutionEngine.apiName(row, payload);
        t.method = row.getOrDefault("Method", "").trim().toUpperCase();
        t.endpoint = row.getOrDefault("Endpoint", "").trim();
        ExecutionEngine.setUrl(t, GlobalStore.resolve(payload != null && payload.getEndpointOverride() != null
                ? payload.getEndpointOverride() : t.endpoint));
        t.label = payload == null ? "default" : payload.getLabel();
        t.negative = payload != null && (payload.isNegative() || payload.getNegCode() != null);
        t.module = row.getOrDefault("Module", "").trim();
        LiveDashboard.queueTest(t);
    }

    // ── TEST METHOD (ONE PAYLOAD = ONE TEST CASE) ─────────────────────────
    @Test(dataProvider = "apiData")
    public void ExecuteAPITest(
            Map<String, String> row,
            ResolvedPayload payload,
            int payloadIndex
    ) {

        if (row == null || row.isEmpty()) {
            throw new SkipException("Skipping invalid test row");
        }
        ProjectContext.set(project());
        LiveDashboard.awaitIfPaused();

        Map<String, String> reportRow = new HashMap<>();

        TestDecision decision =
                ExecutionEngine.executeSingle(
                        row,
                        payload,
                        payloadIndex,
                        reportRow
                );

        if (payload == null || (!payload.isNegative() && payload.getNegCode() == null)) {
            positiveSnapshots.put(row.getOrDefault("TC_ID", ""), new HashMap<>(GlobalStore.getAll()));
        }

        // first login failed: unlock the user (optional) or stop the run with ONE clear message
        boolean positiveRow = payload == null || (!payload.isNegative() && payload.getNegCode() == null);
        boolean firstLogin = positiveRow && loginRow != null && row.getOrDefault("TC_ID", "").equals(loginRow.getOrDefault("TC_ID", ""));
        if (firstLogin && decision.outcome == com.api.automation.utils.TestOutcome.FAIL) {
            String why = (reportRow.getOrDefault("ActualResponse", "") + " " + reportRow.getOrDefault("ActualResult", "") + " " + decision.reason);
            boolean locked = why.toLowerCase().contains("locked");
            String note = "";
            if (locked && com.api.automation.engine.AccountUnlocker.enabled()) {
                String target = GlobalStore.resolve("${loginId}");
                com.api.automation.engine.AccountUnlocker.Outcome u = com.api.automation.engine.AccountUnlocker.unlock(row, loginBody, target);
                note = u.ok ? " | Auto-unlock: " + u.message + ", login retried" : " | Auto-unlock failed: " + u.message;
                if (u.ok) {
                    Map<String, String> again = new HashMap<>();
                    TestDecision d2 = ExecutionEngine.executeSingle(row, payload, payloadIndex, again);
                    if (!again.isEmpty()) {
                        reportRow = again;
                        decision = d2;
                    }
                }
            }
            if (!reportRow.isEmpty() && !note.isEmpty()) reportRow.put("ActualResult", reportRow.getOrDefault("ActualResult", "") + note);
            if (decision.outcome == com.api.automation.utils.TestOutcome.FAIL) {
                String msg = "Login failed" + (locked ? " - the account is LOCKED" : "") + ", so no other API can run. "
                        + (locked ? "Ask an administrator to unlock '" + GlobalStore.resolve("${loginId}") + "' (or switch on unlock.enabled=true with a maker and checker in config.properties)."
                        : "Check the login id and password in config.properties.") + note;
                ExecutionEngine.ABORT_REASON = msg;
                System.out.println("\n⛔ " + msg + "\n");
                LiveDashboard.log("ERROR", null, msg);
            }
        }

        // plain-English failure analysis for the report
        if (!reportRow.isEmpty()) {
            reportRow.put("Module", row.getOrDefault("Module", "").trim());
            int http = 0;
            try { http = Integer.parseInt(reportRow.getOrDefault("HTTP", "0")); } catch (NumberFormatException ignored) { }
            com.api.automation.report.FailureClassifier.Verdict v = com.api.automation.report.FailureClassifier
                    .classify(decision.outcome.name(), http, decision.reason);
            reportRow.put("Failure_Category", v.category);
            reportRow.put("Failure_Reason", v.reason);
            reportRow.put("Action_Needed", v.action);
            // VAPT reference for crashes / leaks found on any request
            String vref = v.category.startsWith("SERVER ERROR") ? com.api.automation.negative.ExpectedStatus.owasp("SERVER_ERROR")
                    : v.category.startsWith("INTERNAL ERROR") ? com.api.automation.negative.ExpectedStatus.owasp("INTERNAL_LEAK") : "";
            if (!vref.isEmpty()) {
                String old = reportRow.getOrDefault("VAPT_Ref", "");
                reportRow.put("VAPT_Ref", old.isEmpty() ? vref : old + "  |  " + vref);
            }
            // VAPT classification of negatives (negative.VaptRules): not applicable / inconclusive / functional are not VAPT failures
            switch (reportRow.getOrDefault("VAPT_Class", "")) {
                case "NA":
                    reportRow.put("Failure_Category", "NOT NEEDED - NOT APPLICABLE");
                    reportRow.put("Failure_Reason", decision.reason);
                    reportRow.put("Action_Needed", "None - this check does not apply to this API.");
                    break;
                case "INCONCLUSIVE":
                    reportRow.put("Failure_Category", "INCONCLUSIVE");
                    reportRow.put("Failure_Reason", decision.reason);
                    reportRow.put("Action_Needed", "Tester: give the positive test data that returns a record, then run again.");
                    break;
                case "FUNCTIONAL":
                    reportRow.put("Failure_Category", "FUNCTIONAL - QA (NOT VAPT)");
                    reportRow.put("Failure_Reason", decision.reason);
                    reportRow.put("Action_Needed", "Developer: add the business validation and return 400 with a clear message (no security impact).");
                    break;
                default:
                    break;
            }
            TestView tv = LiveDashboard.test(reportRow.getOrDefault("TC_ID", ""));
            if (tv != null && !reportRow.getOrDefault("Failure_Category", "").isEmpty()) {
                tv.category = reportRow.get("Failure_Category");
                tv.remark = reportRow.getOrDefault("Failure_Reason", "");
                tv.action = reportRow.getOrDefault("Action_Needed", "");
                tv.vapt = reportRow.getOrDefault("VAPT_Ref", "");
            }
        }
        boolean stateBroken = payload != null && "STATE_CHECK".equals(payload.getNegCategory())
                && decision.outcome == com.api.automation.utils.TestOutcome.FAIL;
        if (stateBroken) {
            reportRow.put("Failure_Category", "STATE BROKEN BY NEGATIVE INPUT");
            reportRow.put("Failure_Reason", "After the negative tests above, the same valid request now fails (" + decision.reason
                    + "). One of the invalid inputs changed the session, permissions or data on the server.");
            reportRow.put("Action_Needed", "Developer: invalid input must never change server state. Check the negatives of "
                    + row.getOrDefault("TC_ID", "") + " that returned HTTP 200. The suite logged in again to continue.");
        }
        boolean negativeRow = payload != null && payload.getNegCode() != null;
        if (!reportRow.isEmpty() && (runMode() != RunMode.NEGATIVE || negativeRow)) {
            finalReport.add(reportRow);
        }
        if (stateBroken) {
            restoreSession(row.getOrDefault("TC_ID", ""));
        }
        switch (decision.outcome) {

            case PASS:
                break;

            case FAIL:
                throw new com.api.automation.report.ApiFailure(reportRow.getOrDefault("Failure_Category", "FAIL") + " - " + decision.reason);

            case SKIP:
                System.out.println("Skipped: " + decision.reason);
                throw new SkipException(decision.reason) {
                    @Override public synchronized Throwable fillInStackTrace() { return this; }
                };
        }
    }

    // ── GENERATE EXCEL + HTML REPORT AFTER RUN ────────────────────────────
    @AfterClass(alwaysRun = true)
    public void generateReport() throws Exception {

        ProjectContext.set(project());
        long runEnd = System.currentTimeMillis();

        System.out.println("Final Report Size: " + finalReport.size());

        if (reportPath == null) reportPath = generateReportPath();
        String htmlPath = reportPath.replaceAll("\\.xlsx$", ".html");
        com.api.automation.report.ResponseStore.stop();

        ExcelUtils.ReportMeta meta = new ExcelUtils.ReportMeta();
        meta.project = project();
        meta.displayName = ProjectContext.displayName();
        meta.env = ProjectContext.env();
        meta.baseUrl = ProjectContext.baseUrl();
        meta.startedAt = runStart;
        meta.finishedAt = runEnd;
        meta.aiStatus = AiVerifier.statusText();
        meta.testedBy = com.api.automation.config.ConfigManager.get("tester.name", System.getProperty("user.name", ""));
        String suite = System.getProperty("suite");
        meta.sheetRows = sheetRows;
        meta.sheetRunN = sheetRunN;
        meta.sheetModuleSkipped = sheetModuleSkipped;
        meta.runner = getClass().getSimpleName() + (suite == null || suite.isBlank() ? " (IntelliJ)" : " (" + suite + ")")
                + "  |  Test data: " + java.nio.file.Paths.get(ProjectContext.testDataPath()).getFileName();

        List<Map<String, String>> rows;
        synchronized (finalReport) {
            rows = new ArrayList<>(finalReport);
        }
        String pdfStamp = Path.of(reportPath).getFileName().toString().replaceAll("\\.xlsx$", "").replaceFirst("^Reports_", "");
        if (com.api.automation.config.ConfigManager.getBool("report.pdf.enabled", true)) {
            meta.vaptPdfName = "VAPT_Report_" + pdfStamp + ".pdf";
            meta.failurePdfName = "Failure_Report_" + pdfStamp + ".pdf";
        }
        ExcelUtils.writeReport(reportPath, rows, meta);

        // two PDFs next to the Excel: the VAPT report (every VAPT point) and the failure report (steps to reproduce)
        com.api.automation.report.PdfReports.Result pdf = null;
        if (com.api.automation.config.ConfigManager.getBool("report.pdf.enabled", true)) {
            String stamp = Path.of(reportPath).getFileName().toString().replaceAll("\\.xlsx$", "").replaceFirst("^Reports_", "");
            pdf = com.api.automation.report.PdfReports.generate(rows, meta, Path.of(reportPath).getParent(), stamp);
            LiveDashboard.setPdfReports(pdf.vaptPdf != null ? pdf.vaptPdf : pdf.vaptHtml,
                    pdf.failPdf != null ? pdf.failPdf : pdf.failHtml, pdf.note);
            if (!pdf.note.isEmpty()) System.out.println("⚠ " + pdf.note);
        }

        long pass = rows.stream().filter(r -> "PASS".equals(r.get("Result"))).count();
        long fail = rows.stream().filter(r -> "FAIL".equals(r.get("Result"))).count();
        long skip = rows.stream().filter(r -> "SKIP".equals(r.get("Result"))).count();
        long notExec = rows.stream().filter(r -> "N/A".equals(r.get("Result")))
                .mapToLong(r -> { try { return Long.parseLong(r.getOrDefault("NA_Count", "1")); } catch (NumberFormatException e) { return 1; } }).sum();

        LiveDashboard.finishRun(reportPath, htmlPath);
        if (LiveDashboard.currentRun() != null) {
            LiveDashboard.writeStaticReport(LiveDashboard.currentRun(), Path.of(htmlPath));
        }

        // summary next to the report + reports/index.html (the hub that lists every run, project-wise)
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("project", project());
        info.put("displayName", ProjectContext.displayName());
        info.put("mode", runMode().name());
        info.put("when", runStart);
        info.put("durationMs", runEnd - runStart);
        info.put("total", pass + fail + skip + notExec);
        info.put("passed", pass);
        info.put("failed", fail);
        info.put("skipped", skip);
        info.put("notExecutable", notExec);
        info.put("positive", rows.stream().filter(r -> !"Y".equals(r.get("Negative"))).count());
        info.put("negative", rows.stream().filter(r -> "Y".equals(r.get("Negative"))).count());
        info.put("testedBy", meta.testedBy);
        info.put("runner", getClass().getSimpleName());
        info.put("env", meta.env);
        info.put("baseUrl", meta.baseUrl);
        if (pdf != null) {
            info.put("vaptReport", (pdf.vaptPdf != null ? pdf.vaptPdf : pdf.vaptHtml) == null ? null : (pdf.vaptPdf != null ? pdf.vaptPdf : pdf.vaptHtml).getFileName().toString());
            info.put("failureReport", (pdf.failPdf != null ? pdf.failPdf : pdf.failHtml) == null ? null : (pdf.failPdf != null ? pdf.failPdf : pdf.failHtml).getFileName().toString());
        }
        com.api.automation.report.ReportHub.writeSidecar(Path.of(htmlPath), info);
        Path hub = com.api.automation.report.ReportHub.write(ProjectContext.reportBase());
        if (com.api.automation.config.ConfigManager.getBool("report.auto.open", false)) {
            LiveDashboard.openFile(Path.of(htmlPath));
        }
        if (pdf != null && com.api.automation.config.ConfigManager.getBool("report.pdf.autoopen", true)) {
            com.api.automation.report.PdfReports.open(pdf.vaptPdf != null ? pdf.vaptPdf : pdf.vaptHtml,
                    pdf.failPdf != null ? pdf.failPdf : pdf.failHtml);
        }

        System.out.println("\n══════════════════════════════════════════════════════");
        System.out.println(" " + ProjectContext.displayName() + " - EXECUTION SUMMARY");
        System.out.println(" Total: " + (pass + fail + skip + notExec) + " | PASS: " + pass + " | FAIL: " + fail + " | SKIP: " + skip
                + " | NOT EXECUTABLE: " + notExec + "  (same numbers as the live console and the Excel Summary)");
        System.out.println(" Tested by: " + meta.testedBy);
        System.out.println(" Overall Time Taken: " + ExcelUtils.formatDuration(runEnd - runStart));
        System.out.println(" Excel report : " + reportPath);
        System.out.println(" HTML report  : " + htmlPath);
        if (pdf != null) {
            System.out.println(" VAPT report  : " + (pdf.vaptPdf != null ? pdf.vaptPdf : pdf.vaptHtml));
            System.out.println(" Failure report: " + (pdf.failPdf != null ? pdf.failPdf : pdf.failHtml));
        }
        if (hub != null) System.out.println(" All reports  : " + hub + "   (open it: every run, project-wise)");
        System.out.println("══════════════════════════════════════════════════════\n");
    }

    /** Log in again (taking over the session) so one broken API does not fail every API after it. */
    private void restoreSession(String brokenTc) {
        if (loginRow == null || loginBody == null) return;
        String body = loginBody;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode n = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginBody);
            n.put("sessionId", 1);
            body = n.toString();
        } catch (Exception ignored) { }
        ResolvedPayload p = ResolvedPayload.negative("RL", "RELOGIN", "Session restored by logging in again",
                "login again after " + brokenTc + " broke the session", "Login Successful", body).asVerifyPositive();
        Map<String, String> rr = new HashMap<>();
        TestDecision d = ExecutionEngine.executeSingle(loginRow, p, 0, rr);
        System.out.println("Session restored after " + brokenTc + ": " + d.outcome + " " + d.reason);
        if (!rr.isEmpty()) {
            rr.put("TC_ID", brokenTc + "_RL");
            rr.put("Module", row0Module(brokenTc));
            finalReport.add(rr);
        }
    }

    private String row0Module(String tc) {
        synchronized (finalReport) {
            for (Map<String, String> r : finalReport) {
                if (r.getOrDefault("TC_ID", "").startsWith(tc)) return r.getOrDefault("Module", "");
            }
        }
        return "";
    }

    // ── REPORT PATH BUILDER: reports/<PROJECT>/Reports_<PROJECT>[_Positive|_Negative]_<date>_<time>.xlsx ──
    public String generateReportPath() {
        String suffix = runMode() == RunMode.ALL ? "" : "_" + (runMode() == RunMode.POSITIVE ? "Positive" : "Negative");
        String project = ProjectContext.require() + suffix;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH));
        String time = now.format(DateTimeFormatter.ofPattern("HH-mm-ss"));   // seconds: two runs in one minute never overwrite each other

        // one folder per run, sorted by date and time:  reports/<PROJECT>/2026-10-18_17-06-07[_Positive|_Negative]/
        // it holds the Excel, HTML, summary json, responses/, recording and screenshot of THAT run only
        Path folder = ProjectContext.reportFolder().resolve(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + suffix);
        folder.toFile().mkdirs();

        return folder.resolve("Reports_" + project + "_" + date + "_" + time + ".xlsx").toString();
    }

    /** Excel row whose toString is short, so IntelliJ / Maven do not print the whole row map. */
    private static final class TestRow extends java.util.LinkedHashMap<String, String> {
        TestRow(Map<String, String> src) {
            super(src);
        }

        @Override
        public String toString() {
            return getOrDefault("TC_ID", "").trim();
        }
    }
}
