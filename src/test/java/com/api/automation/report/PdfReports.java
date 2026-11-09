package com.api.automation.report;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.api.automation.config.ConfigManager;
import com.api.automation.utils.ExcelUtils;
import com.api.automation.utils.PiiMasker;

/**
 * Two PDF reports per run, written into the run folder and opened automatically:
 *
 *   VAPT_Report_<stamp>.pdf     category-wise: one numbered VAPT point (VAPT-001 ...) per check, each PASS / FAIL / SKIP / NA,
 *                               with the standard (OWASP / CWE), impact, expected vs actual and the evidence of the failures.
 *   Failure_Report_<stamp>.pdf  only the failed tests: steps to reproduce and the full evidence.
 *
 * Evidence of a test = test data -> request (plain) -> request as sent (encrypted) -> response as received (encrypted)
 * -> response after decryption.
 *
 * The pages are built as HTML and printed to PDF by the PC's own Chrome or Edge (headless), so no PDF library is needed.
 * If no browser is found the .html files are kept - they read and print the same.
 */
public final class PdfReports {

    public static final class Result {
        public Path vaptPdf, failPdf;      // null when the PDF could not be made
        public Path vaptHtml, failHtml;    // kept only when the PDF could not be made
        public String note = "";
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH);
    private static final List<String> SEV = Arrays.asList("Critical", "High", "Medium", "Low", "Info");
    private static final int PLAIN_MAX = 3000, WIRE_MAX = 2200, RESP_MAX = 3500;

    private PdfReports() {}

    // ════════════════════════════════════════════════════════════════════
    public static Result generate(List<Map<String, String>> rows, ExcelUtils.ReportMeta meta, Path runDir, String stamp) {
        Result res = new Result();
        try {
            List<VaptModel.Point> points = VaptModel.build(rows);
            Path vh = runDir.resolve("VAPT_Report_" + stamp + ".html");
            Path fh = runDir.resolve("Failure_Report_" + stamp + ".html");
            Files.writeString(vh, vaptHtml(points, rows, meta), StandardCharsets.UTF_8);
            Files.writeString(fh, failureHtml(rows, meta), StandardCharsets.UTF_8);

            String browser = findBrowser();
            if (browser == null) {
                res.vaptHtml = vh;
                res.failHtml = fh;
                res.note = "No Chrome / Edge found for the PDF - the reports are saved as HTML instead (set pdf.browser.path in config.properties)";
                return res;
            }
            Path vp = runDir.resolve("VAPT_Report_" + stamp + ".pdf");
            Path fp = runDir.resolve("Failure_Report_" + stamp + ".pdf");
            if (toPdf(browser, vh, vp)) { res.vaptPdf = vp; Files.deleteIfExists(vh); } else res.vaptHtml = vh;
            if (toPdf(browser, fh, fp)) { res.failPdf = fp; Files.deleteIfExists(fh); } else res.failHtml = fh;
            if (res.vaptPdf == null || res.failPdf == null) res.note = "The browser could not print one of the PDFs - the HTML version is kept instead";
        } catch (Exception e) {
            res.note = "PDF reports not written: " + e.getMessage();
        }
        return res;
    }

    /** Opens the files with the default program (the PDF viewer / browser). */
    public static void open(Path... files) {
        for (Path f : files) {
            if (f == null || !Files.exists(f)) continue;
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(f.toFile());
                } else {
                    new ProcessBuilder("cmd", "/c", "start", "", f.toAbsolutePath().toString()).start();
                }
                Thread.sleep(600);
            } catch (Exception e) {
                System.out.println("\u26A0 Could not open " + f.getFileName() + ": " + e.getMessage());
            }
        }
    }

    // ── HTML -> PDF with Chrome / Edge ────────────────────────────────────
    private static String findBrowser() {
        List<String> c = new ArrayList<>();
        String cfg = ConfigManager.get("pdf.browser.path", "");
        if (cfg != null && !cfg.isBlank()) c.add(cfg);
        String local = System.getenv("LOCALAPPDATA");
        c.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        c.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
        if (local != null) c.add(local + "\\Google\\Chrome\\Application\\chrome.exe");
        c.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        c.add("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe");
        c.add("/usr/bin/google-chrome");
        c.add("/usr/bin/chromium");
        c.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        for (String p : c) if (Files.isRegularFile(Path.of(p))) return p;
        return null;
    }

    private static boolean toPdf(String browser, Path html, Path pdf) {
        Path profile = null;
        try {
            profile = Files.createTempDirectory("apiauto-pdf-");   // own profile: works even when Chrome is already open
            List<String> cmd = new ArrayList<>(Arrays.asList(browser, "--headless=new", "--disable-gpu", "--no-sandbox",
                    "--no-first-run", "--no-default-browser-check", "--disable-extensions",
                    "--user-data-dir=" + profile, "--no-pdf-header-footer", "--print-to-pdf-no-header",
                    "--generate-pdf-document-outline", "--print-to-pdf=" + pdf.toAbsolutePath(), html.toUri().toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(ConfigManager.getInt("pdf.timeout.seconds", 120), TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return Files.exists(pdf) && Files.size(pdf) > 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (profile != null) deleteQuietly(profile);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    // ════════════════════════════════════════════════════════════════════
    //  VAPT REPORT  (category-wise)
    // ════════════════════════════════════════════════════════════════════
    /**
     * VAPT report:
     *   1. Confirmed findings (only real security issues, most severe first) - steps, request, encrypted request,
     *      raw response, decrypted response of every failed test
     *   2. VAPT points, category-wise (failed points first in each category), with the closure reason
     *   3. Closed tests - the request / response evidence of EVERY passed test, so a reopened point can be compared
     *   4. Functional validation (QA) - business validation gaps, not VAPT
     *   5. Not applicable / inconclusive tests with the reason
     */
    private static String vaptHtml(List<VaptModel.Point> pts, List<Map<String, String>> rows, ExcelUtils.ReportMeta meta) {
        StringBuilder b = new StringBuilder(256 * 1024);
        List<VaptModel.Finding> findings = VaptModel.findings(pts);
        List<Map<String, String>> functional = VaptModel.functional(rows);
        head(b, "VAPT Report", meta);
        b.append("<h1 id=\"top\">VAPT Report</h1><div class=\"sub\">").append(esc(name(meta))).append("</div>");
        projectBox(b, rows, meta);

        // ---- summary ----
        int[] fs = new int[4];
        int failedTests = 0;
        for (VaptModel.Finding f : findings) {
            int i = VaptModel.SEV.indexOf(f.severity);
            if (i >= 0 && i < 4) fs[i]++;
            failedTests += f.rows.size();
        }
        int ptFail = 0, ptPass = 0, ptOther = 0, na = 0, inc = 0;
        for (VaptModel.Point p : pts) {
            if ("FAIL".equals(p.status)) ptFail++; else if ("PASS".equals(p.status)) ptPass++; else ptOther++;
            na += p.notApplicable;
            inc += p.skipped;
        }
        b.append("<h2>Summary</h2><table class=\"grid\"><tr><th>Confirmed findings</th><th>Critical</th><th>High</th><th>Medium</th><th>Low</th>"
                + "<th>Failed tests</th><th>VAPT points failed / passed / not tested</th><th>Functional (QA, not VAPT)</th><th>N/A / inconclusive</th></tr>");
        b.append("<tr><td class=\"c\"><b>").append(findings.size()).append("</b></td>");
        for (int v : fs) b.append("<td class=\"c\">").append(v == 0 ? "-" : String.valueOf(v)).append("</td>");
        b.append("<td class=\"c\">").append(failedTests).append("</td><td class=\"c\">").append(ptFail).append(" / ").append(ptPass).append(" / ").append(ptOther)
                .append("</td><td class=\"c\">").append(functional.size()).append("</td><td class=\"c\">").append(na).append(" / ").append(inc).append("</td></tr></table>");
        b.append("<p class=\"sm muted\">A test is a VAPT failure only with proof of security impact (data exposure, unauthorised action, auth bypass, "
                + "injection / XSS proof, internal detail leak, encryption or resource limits not enforced). Safe handling - a rejection, or an empty "
                + "list on a search API - is a pass. Business validation gaps with no security impact are listed under Functional validation.</p>");

        // ---- pivot: category x severity ----
        b.append("<h2>Summary by category</h2><table class=\"grid\"><tr><th>Category</th><th>Critical</th><th>High</th><th>Medium</th><th>Low</th>"
                + "<th>Open (failed tests)</th><th>Closed (passed tests)</th><th>Status</th></tr>");
        int[] tot = new int[6];
        for (String[] cat : VaptModel.CATEGORIES) {
            int[] c = new int[6];
            boolean any = false, anyFail = false, anyPass = false;
            for (VaptModel.Point p : pts) {
                if (!p.category.equals(cat[0])) continue;
                for (Map<String, String> r : p.failedRows) {
                    int i = VaptModel.SEV.indexOf(VaptModel.rowSeverity(r, p.key));
                    if (i >= 0 && i < 4) c[i]++;
                    c[4]++;
                }
                c[5] += p.passed;
                if (p.failed > 0) anyFail = true;
                if (p.passed > 0) anyPass = true;
                if (p.tested() > 0 || p.skipped > 0) any = true;
            }
            String st = anyFail ? "FAIL" : anyPass ? "PASS" : any ? "SKIP" : "NA";
            b.append("<tr><td><b>").append(esc(cat[0])).append("</b></td>");
            for (int i = 0; i < 6; i++) { b.append("<td class=\"c\">").append(i < 4 && c[i] == 0 ? "-" : String.valueOf(c[i])).append("</td>"); tot[i] += c[i]; }
            b.append("<td class=\"c\">").append(statusChip(st)).append("</td></tr>");
        }
        b.append("<tr class=\"tot\"><td>TOTAL</td>");
        for (int i = 0; i < 6; i++) b.append("<td class=\"c\">").append(tot[i]).append("</td>");
        b.append("<td></td></tr></table>");

        // ---- contents ----
        b.append("<h2>Contents</h2><table class=\"grid toc\">");
        b.append("<tr class=\"cat\"><td colspan=\"3\"><a href=\"#sec-findings\">1. Confirmed findings (").append(findings.size()).append(")</a></td></tr>");
        for (VaptModel.Finding f : findings) {
            b.append("<tr><td class=\"id\"><a href=\"#").append(f.anchor()).append("\">").append(f.id).append("</a></td><td><a href=\"#").append(f.anchor()).append("\">")
                    .append(esc(f.title)).append(" &middot; ").append(f.apis.size()).append(f.apis.size() == 1 ? " API, " : " APIs, ").append(f.rows.size())
                    .append(f.rows.size() == 1 ? " test" : " tests").append("</a></td><td class=\"c st\">").append(sevChip(f.severity)).append("</td></tr>");
        }
        b.append("<tr class=\"cat\"><td colspan=\"3\"><a href=\"#sec-points\">2. VAPT points</a></td></tr>");
        for (VaptModel.Point p : orderedPoints(pts)) {
            b.append("<tr><td class=\"id\"><a href=\"#").append(p.anchor()).append("\">").append(p.id).append("</a></td><td><a href=\"#")
                    .append(p.anchor()).append("\">").append(esc(p.title)).append("</a></td><td class=\"c st\">").append(statusChip(p.status)).append("</td></tr>");
        }
        b.append("<tr class=\"cat\"><td colspan=\"3\"><a href=\"#sec-closed\">3. Closed tests - evidence</a></td></tr>");
        b.append("<tr class=\"cat\"><td colspan=\"3\"><a href=\"#sec-functional\">4. Functional validation - QA, not VAPT (").append(functional.size()).append(")</a></td></tr>");
        b.append("<tr class=\"cat\"><td colspan=\"3\"><a href=\"#sec-na\">5. Not applicable / inconclusive (").append(na + inc).append(")</a></td></tr>");
        b.append("</table>");

        // ---- 1. confirmed findings ----
        b.append("<h1 class=\"cath\" id=\"sec-findings\">1. Confirmed findings</h1>");
        if (findings.isEmpty()) b.append("<p class=\"note ok\"><b>No confirmed VAPT finding in this run.</b></p>");
        for (VaptModel.Finding f : findings) {
            VaptModel.Point p = f.point;
            b.append("<section class=\"pt\"><h2 id=\"").append(f.anchor()).append("\">").append(f.id).append(" &middot; ").append(esc(f.title))
                    .append(" <span class=\"r\">").append(sevChip(f.severity)).append(" <a class=\"up\" href=\"#top\">&uarr; contents</a></span></h2>");
            b.append("<table class=\"kv\">");
            kv(b, "VAPT point", p.id + " - " + p.title);
            kv(b, "APIs affected (" + f.apis.size() + ")", String.join("\n", f.apis));
            kv(b, "Standard", p.owasp.isEmpty() ? "-" : p.owasp);
            kv(b, "How it affects the bank", p.impact);
            kv(b, "Fix needed", p.fix);
            StringBuilder ids = new StringBuilder();
            for (Map<String, String> r : f.rows) ids.append(ids.length() == 0 ? "" : ", ").append(r.getOrDefault("TC_ID", ""));
            kv(b, "Tests (" + f.rows.size() + ")", ids.toString());
            b.append("</table>");
            for (Map<String, String> r : f.rows) {
                b.append("<div class=\"ev\"><div class=\"evh\">").append(statusChip("FAIL")).append(" ").append(sevChip(VaptModel.rowSeverity(r, p.key))).append(" <b>")
                        .append(esc(r.getOrDefault("TC_ID", ""))).append("</b> &middot; ").append(esc(shortApi(r))).append(" &middot; ")
                        .append(esc(r.getOrDefault("NegTitle", "").isEmpty() ? "positive test" : r.get("NegTitle"))).append("</div>");
                stepsToReproduce(b, r, meta);
                expectedActual(b, r, p.expected);
                if ("INTERNAL_LEAK".equals(p.key) && !r.getOrDefault("Leak_Evidence", "").isEmpty()) {
                    b.append("<table class=\"kv\">").append(kvRow("Leaked detail", r.get("Leak_Evidence"))).append("</table>");
                }
                evidence(b, r);
                b.append("</div>");
            }
            b.append("</section>");
        }

        // ---- 2. VAPT points ----
        b.append("<h1 class=\"cath\" id=\"sec-points\">2. VAPT points</h1>");
        for (String[] cat : VaptModel.CATEGORIES) {
            b.append("<h2 class=\"cat2\">").append(esc(cat[0])).append("</h2>");
            for (VaptModel.Point p : orderedPoints(pts)) {
                if (!p.category.equals(cat[0])) continue;
                b.append("<section class=\"pt\"><h2 id=\"").append(p.anchor()).append("\">").append(p.id).append(" &middot; ").append(esc(p.title))
                        .append(" <span class=\"r\">").append(statusChip(p.status)).append(" ").append("FAIL".equals(p.status) ? sevChip(p.severity) : "")
                        .append(" <a class=\"up\" href=\"#top\">&uarr; contents</a></span></h2>");
                b.append("<table class=\"kv\">");
                kv(b, "Standard", p.owasp.isEmpty() ? "-" : p.owasp);
                kv(b, "What is tested", p.what);
                kv(b, "Expected result", p.expected + (p.why.isEmpty() ? "" : " - " + cap(p.why)));
                kv(b, "How it affects the bank", p.impact);
                kv(b, "Result", p.endpointsTested.isEmpty() && p.otherRows.isEmpty() ? "Not tested in this run - no API needed this check."
                        : p.endpointsTested.size() + " API(s) tested: " + p.passed + " handled correctly, " + p.failed + " failed"
                        + (p.notApplicable > 0 ? ", " + p.notApplicable + " not applicable" : "") + (p.skipped > 0 ? ", " + p.skipped + " inconclusive / skipped" : ""));
                if ("PASS".equals(p.status)) kv(b, "Closure", "Closed: every tested API handled this check correctly. The evidence of each closed test is in section 3.");
                kv(b, "Fix needed", p.fix);
                b.append("</table>");
                if (p.failed > 0) {
                    b.append("<p class=\"sm\"><b>Findings:</b> ");
                    for (VaptModel.Finding f : findings) {
                        boolean mine = false;
                        for (Map<String, String> r : f.rows) if (p.failedRows.contains(r)) { mine = true; break; }
                        if (mine) b.append("<a class=\"tag v\" href=\"#").append(f.anchor()).append("\">").append(f.id).append(" ").append(esc(f.title)).append("</a> ");
                    }
                    b.append("</p>");
                }
                b.append("</section>");
            }
        }

        // ---- 3. closed tests: evidence of every passed test ----
        b.append("<h1 class=\"cath\" id=\"sec-closed\">3. Closed tests - evidence</h1>");
        boolean full = ConfigManager.getBool("vapt.pdf.closed.evidence", true);
        int max = ConfigManager.getInt("vapt.pdf.closed.max.chars", 2500);
        b.append("<p class=\"sm muted\">Every passed (closed) test with its closure reason and the request / response it was closed on"
                + (full ? "" : " (evidence switched off: vapt.pdf.closed.evidence=false)") + ". Texts longer than " + max
                + " characters are cut here; the full response is in responses/&lt;test&gt;.html of the run folder. Leak and server-error checks run on every request and are not repeated here.</p>");
        for (VaptModel.Point p : pts) {
            if (p.passedRows.isEmpty() || "SERVER_ERROR".equals(p.key) || "INTERNAL_LEAK".equals(p.key)) continue;
            b.append("<h2>").append(p.id).append(" &middot; ").append(esc(p.title)).append(" <span class=\"muted sm\">(").append(p.passedRows.size()).append(" closed)</span></h2>");
            for (Map<String, String> r : p.passedRows) {
                b.append("<div class=\"ev ok\"><div class=\"evh\">").append(statusChip("PASS")).append(" <b>").append(esc(r.getOrDefault("TC_ID", "")))
                        .append("</b> &middot; ").append(esc(shortApi(r))).append(" &middot; HTTP ").append(esc(r.getOrDefault("HTTP", "-"))).append("</div>");
                b.append("<table class=\"kv\">").append(kvRow("Closure reason", r.getOrDefault("Error", ""))).append(kvRow("Test data", r.getOrDefault("TestData", "")))
                        .append("</table>");
                if (full) {
                    String body = PiiMasker.mask(r.getOrDefault("RequestBody", ""));
                    step(b, "2", "Request (plain)", r.getOrDefault("Method", "") + " " + r.getOrDefault("Endpoint", "") + (body.isBlank() ? "" : "\n\n" + body), max);
                    step(b, "5", "Response (decrypted, HTTP " + esc(r.getOrDefault("HTTP", "-")) + ")", PiiMasker.mask(r.getOrDefault("ActualResponse", "")), max);
                }
                b.append("</div>");
            }
        }

        // ---- 4. functional validation ----
        b.append("<h1 class=\"cath\" id=\"sec-functional\">4. Functional validation (QA - not VAPT)</h1>");
        if (functional.isEmpty()) b.append("<p class=\"note ok\"><b>No functional validation issue found by the negatives.</b></p>");
        else {
            b.append("<p class=\"sm\">Business validation gaps with no security impact (dates, out-of-range flags on report / dashboard filters, "
                    + "server errors without leaked details). For the QA / development team; not counted as VAPT and no VAPT severity.</p>");
            b.append("<table class=\"grid\"><tr><th>Test</th><th>API</th><th>Check</th><th>HTTP</th><th>Why</th></tr>");
            for (Map<String, String> r : functional) {
                b.append("<tr><td class=\"id\">").append(esc(r.getOrDefault("TC_ID", ""))).append("</td><td>").append(esc(shortApi(r))).append("</td><td>")
                        .append(esc(r.getOrDefault("TestData", ""))).append("</td><td class=\"c\">").append(esc(r.getOrDefault("HTTP", ""))).append("</td><td>")
                        .append(esc(r.getOrDefault("Error", ""))).append("</td></tr>");
            }
            b.append("</table>");
        }

        // ---- 5. not applicable / inconclusive ----
        b.append("<h1 class=\"cath\" id=\"sec-na\">5. Not applicable / inconclusive</h1>");
        if (na + inc == 0) b.append("<p class=\"muted\">None.</p>");
        else {
            b.append("<table class=\"grid\"><tr><th>Test</th><th>VAPT point</th><th>API</th><th>Status</th><th>Reason</th></tr>");
            for (VaptModel.Point p : pts) {
                for (Map<String, String> r : p.otherRows) {
                    b.append("<tr><td class=\"id\">").append(esc(r.getOrDefault("TC_ID", ""))).append("</td><td>").append(p.id).append("</td><td>").append(esc(shortApi(r)))
                            .append("</td><td class=\"c\">").append("NA".equals(r.get("VAPT_Class")) ? statusChip("NA") : statusChip("SKIP")).append("</td><td>")
                            .append(esc(r.getOrDefault("Error", ""))).append("</td></tr>");
                }
            }
            b.append("</table>");
        }
        b.append("<p class=\"foot\">Generated ").append(now()).append(" &middot; Tested by ").append(esc(meta.testedBy))
                .append(" &middot; Standards: OWASP API Security Top 10 (2023), OWASP Top 10, CWE, RFC 9110. A negative test passes when the API handles the bad request safely.</p></body></html>");
        return b.toString();
    }

    /** Failed points first, then passed, then the rest; VAPT number order inside each group. */
    private static List<VaptModel.Point> orderedPoints(List<VaptModel.Point> pts) {
        List<VaptModel.Point> out = new ArrayList<>();
        for (String st : new String[]{"FAIL", "PASS", "SKIP", "NA"}) for (VaptModel.Point p : pts) if (st.equals(p.status)) out.add(p);
        return out;
    }

    private static void stepsToReproduce(StringBuilder b, Map<String, String> r, ExcelUtils.ReportMeta meta) {
        boolean neg = "Y".equals(r.get("Negative"));
        boolean enc = !r.getOrDefault("WireRequest", "").isEmpty();
        b.append("<h3>Steps to reproduce</h3><ol class=\"steps\">");
        b.append("<li>Log in to <span class=\"mono\">").append(esc(meta.baseUrl)).append("</span> with the test user and keep the session (cookies and the CSRF / bearer token headers).</li>");
        b.append("<li>Send <b class=\"mono\">").append(esc(r.getOrDefault("Method", ""))).append(" ").append(esc(r.getOrDefault("Endpoint", ""))).append("</b> with header <span class=\"mono\">Content-Type: application/json</span> and the session headers.</li>");
        String td = r.getOrDefault("TestData", "");
        if (neg && !td.isBlank() && !"NA".equals(td)) b.append("<li><b>Change made to the valid request:</b> ").append(esc(cut(td, 300))).append("</li>");
        b.append("<li>Use the request body in the evidence below").append(enc ? " (this API is encrypted: the plain request is encrypted before sending &ndash; see \"Request sent\"; to replay it follow docs/Burp_Replay_Guide.md)" : "").append(".</li>");
        b.append("<li>Check the response: expected <b>").append(esc(r.getOrDefault("Expected_HTTP", "-"))).append("</b>, received <b>HTTP ")
                .append(esc(r.getOrDefault("HTTP", "-"))).append("</b>.</li></ol>");
    }

    // ════════════════════════════════════════════════════════════════════
    //  FAILURE REPORT
    // ════════════════════════════════════════════════════════════════════
    private static String failureHtml(List<Map<String, String>> rows, ExcelUtils.ReportMeta meta) {
        List<Map<String, String>> fails = new ArrayList<>();
        for (Map<String, String> r : rows) if ("FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) fails.add(r);
        fails.sort((a, c) -> {
            int x = SEV.indexOf(a.getOrDefault("Severity", "")), y = SEV.indexOf(c.getOrDefault("Severity", ""));
            return Integer.compare(x < 0 ? 99 : x, y < 0 ? 99 : y);
        });
        StringBuilder b = new StringBuilder(160 * 1024);
        head(b, "Failure Report", meta);
        b.append("<h1 id=\"top\">Failure Report</h1><div class=\"sub\">").append(esc(name(meta))).append("</div>");
        projectBox(b, rows, meta);

        // pivot: severity x test type
        int[][] pv = new int[SEV.size() + 1][2];
        for (Map<String, String> r : fails) {
            int i = SEV.indexOf(r.getOrDefault("Severity", ""));
            pv[i < 0 ? SEV.size() : i]["Y".equals(r.get("Negative")) ? 1 : 0]++;
        }
        b.append("<h2>Summary by severity</h2><table class=\"grid\"><tr><th>Severity</th><th>Positive tests</th><th>Negative tests</th><th>Open (failed)</th></tr>");
        int tp = 0, tn = 0;
        for (int i = 0; i <= SEV.size(); i++) {
            if (pv[i][0] + pv[i][1] == 0 && i >= 4) continue;
            tp += pv[i][0]; tn += pv[i][1];
            b.append("<tr><td>").append(i < SEV.size() ? sevChip(SEV.get(i)) : "Other").append("</td><td class=\"c\">").append(pv[i][0]).append("</td><td class=\"c\">")
                    .append(pv[i][1]).append("</td><td class=\"c\"><b>").append(pv[i][0] + pv[i][1]).append("</b></td></tr>");
        }
        b.append("<tr class=\"tot\"><td>TOTAL</td><td class=\"c\">").append(tp).append("</td><td class=\"c\">").append(tn).append("</td><td class=\"c\">")
                .append(tp + tn).append("</td></tr></table>");

        if (fails.isEmpty()) {
            b.append("<p class=\"note ok\"><b>No failed tests in this run.</b></p></body></html>");
            return b.toString();
        }
        b.append("<h2>Contents</h2><table class=\"grid toc\"><tr><th>#</th><th>Test</th><th>API</th><th>Severity</th><th>Status</th></tr>");
        int i = 0;
        for (Map<String, String> r : fails) {
            i++;
            b.append("<tr><td class=\"c\">").append(i).append("</td><td class=\"id\"><a href=\"#f").append(i).append("\">").append(esc(r.getOrDefault("TC_ID", "")))
                    .append("</a></td><td><a href=\"#f").append(i).append("\">").append(esc(shortApi(r))).append("</a></td><td class=\"c\">")
                    .append(sevChip(r.getOrDefault("Severity", ""))).append("</td><td class=\"c\">").append(statusChip("FAIL")).append("</td></tr>");
        }
        b.append("</table>");

        i = 0;
        for (Map<String, String> r : fails) {
            i++;
            boolean neg = "Y".equals(r.get("Negative"));
            boolean enc = !r.getOrDefault("WireRequest", "").isEmpty();
            String url = r.getOrDefault("Endpoint", "");
            b.append("<section class=\"pt\"><h2 id=\"f").append(i).append("\">").append(i).append(". ").append(esc(r.getOrDefault("TC_ID", ""))).append(" &middot; ")
                    .append(esc(shortApi(r))).append(" <span class=\"r\">").append(statusChip("FAIL")).append(" <a class=\"up\" href=\"#top\">&uarr; contents</a></span></h2>");
            b.append("<div class=\"line\">").append(sevChip(r.getOrDefault("Severity", ""))).append(" <span class=\"tag\">").append(neg ? "Negative test" : "Positive test").append("</span> ")
                    .append(r.getOrDefault("Module", "").isEmpty() ? "" : "<span class=\"tag\">" + esc(r.get("Module")) + "</span> ")
                    .append(r.getOrDefault("VAPT_Ref", "").isEmpty() ? "" : "<span class=\"tag v\">" + esc(r.get("VAPT_Ref")) + "</span>").append("</div>");

            b.append("<h3>Steps to reproduce</h3><ol class=\"steps\">");
            b.append("<li>Log in to <span class=\"mono\">").append(esc(meta.baseUrl)).append("</span> with the test user and keep the session (cookies and the CSRF / bearer token headers).</li>");
            b.append("<li>Send <b class=\"mono\">").append(esc(r.getOrDefault("Method", ""))).append(" ").append(esc(url)).append("</b> with header <span class=\"mono\">Content-Type: application/json</span> and the session headers.</li>");
            String td = r.getOrDefault("TestData", "");
            if (neg && !td.isBlank() && !"NA".equals(td)) b.append("<li><b>Change made to the valid request:</b> ").append(esc(cut(td, 300))).append("</li>");
            b.append("<li>Use the request body in the evidence below").append(enc ? " (this API is encrypted: the plain request is encrypted before sending &ndash; see \"Request sent\"; to replay it follow docs/Burp_Replay_Guide.md)" : "").append(".</li>");
            b.append("<li>Check the response: expected <b>").append(esc(r.getOrDefault("Expected_HTTP", "-"))).append("</b>, received <b>HTTP ")
                    .append(esc(r.getOrDefault("HTTP", "-"))).append("</b>.</li></ol>");

            expectedActual(b, r, "");
            String act = r.getOrDefault("Action_Needed", "");
            if (!act.isBlank()) b.append("<table class=\"kv\">").append(kvRow("Fix needed", act)).append("</table>");
            evidence(b, r);
            b.append("</section>");
        }
        b.append("<p class=\"foot\">Generated ").append(now()).append(" &middot; Tested by ").append(esc(meta.testedBy)).append("</p></body></html>");
        return b.toString();
    }

    // ── shared pieces ────────────────────────────────────────────────────
    private static void expectedActual(StringBuilder b, Map<String, String> r, String fallbackExpected) {
        b.append("<table class=\"kv\">");
        kv(b, "Expected", r.getOrDefault("ExpectedResult", fallbackExpected));
        kv(b, "Actual", "HTTP " + r.getOrDefault("HTTP", "-") + " | " + r.getOrDefault("ActualResult", "-"));
        String why = r.getOrDefault("Failure_Reason", "");
        if (!why.isBlank() && "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) kv(b, "Why it failed", why);
        b.append("</table>");
    }

    /** test data -> plain request -> request as sent (encrypted) -> response as received (encrypted) -> decrypted response */
    private static void evidence(StringBuilder b, Map<String, String> r) {
        boolean enc = !r.getOrDefault("WireRequest", "").isEmpty();
        String td = r.getOrDefault("TestData", "");
        String src = r.getOrDefault("Data_Source", "");
        String req = r.getOrDefault("Method", "") + " " + r.getOrDefault("Endpoint", "");
        String body = PiiMasker.mask(r.getOrDefault("RequestBody", ""));
        b.append("<div class=\"evid\">");
        step(b, "1", "Test data", (td.isBlank() || "NA".equals(td) ? "" : td + "\n") + src, PLAIN_MAX);
        step(b, "2", "Request (plain, before encryption)", req + (body.isBlank() ? "" : "\n\n" + body), PLAIN_MAX);
        step(b, "3", "Request sent to the server" + (enc ? " (encrypted)" : ""), enc ? r.get("WireRequest") : "Not encrypted - the request above was sent as plain JSON.", WIRE_MAX);
        step(b, "4", "Response received" + (enc ? " (encrypted)" : ""), enc && !r.getOrDefault("WireResponse", "").isEmpty() ? r.get("WireResponse")
                : "Not encrypted - the response below is exactly what was received.", WIRE_MAX);
        step(b, "5", "Response after decryption (HTTP " + esc(r.getOrDefault("HTTP", "-")) + ")", PiiMasker.mask(r.getOrDefault("ActualResponse", "")), RESP_MAX);
        b.append("</div>");
    }

    private static void step(StringBuilder b, String n, String title, String text, int max) {
        b.append("<div class=\"stp\"><span class=\"n\">").append(n).append("</span> <b>").append(title).append("</b><pre>")
                .append(esc(cut(text == null || text.isBlank() ? "-" : text, max))).append("</pre></div>");
    }

    private static void projectBox(StringBuilder b, List<Map<String, String>> rows, ExcelUtils.ReportMeta meta) {
        long pass = rows.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
        long fail = rows.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
        long skip = rows.stream().filter(r -> "SKIP".equalsIgnoreCase(r.getOrDefault("Result", "")) || "SKIPPED".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
        b.append("<table class=\"kv head\">");
        kv(b, "Project", name(meta) + "  (" + meta.env + ")");
        kv(b, "Base URL", meta.baseUrl);
        kv(b, "Tested by", meta.testedBy);
        kv(b, "Run", (meta.startedAt > 0 ? at(meta.startedAt) : "-") + "  \u2192  " + (meta.finishedAt > 0 ? at(meta.finishedAt) : "-"));
        kv(b, "Tests", rows.size() + " run: " + pass + " passed, " + fail + " failed, " + skip + " skipped");
        b.append("</table>");
    }

    private static void head(StringBuilder b, String title, ExcelUtils.ReportMeta meta) {
        String runHead = css(title + "  |  " + name(meta) + "  |  " + (meta.startedAt > 0 ? at(meta.startedAt) : now()));
        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>").append(esc(title + " - " + name(meta))).append("</title><style>")
                .append("@page{size:A4;margin:17mm 12mm 16mm 12mm;")
                .append("@top-left{content:\"").append(runHead).append("\";font:9px Arial,sans-serif;color:#5d6d7e}")
                .append("@bottom-right{content:\"Page \" counter(page) \" of \" counter(pages);font:9px Arial,sans-serif;color:#5d6d7e}}")
                .append("*{box-sizing:border-box}")
                .append("body{font:11.5px/1.5 'Segoe UI',Arial,sans-serif;color:#1c2833;margin:0}")
                .append("h1{font-size:25px;margin:0;color:#1f3864}.sub{font-size:15px;color:#5d6d7e;margin:2px 0 8px}")
                .append("h1.cath{font-size:17px;margin:22px 0 4px;padding:6px 10px;background:#1f3864;color:#fff;border-radius:4px;page-break-before:always;page-break-after:avoid}")
                .append("h2.cat2{font-size:15px;margin:18px 0 4px;color:#fff;background:#2e75b6;border:0;padding:4px 10px;border-radius:4px}")
                .append("h2{font-size:14px;margin:16px 0 6px;color:#1f3864;border-bottom:2px solid #1565c0;padding-bottom:3px;page-break-after:avoid}")
                .append("h3{font-size:11.5px;margin:10px 0 4px;color:#2e75b6;text-transform:uppercase;letter-spacing:.4px}")
                .append("a{color:#1565c0;text-decoration:none}.r{float:right}a.up{font-size:10px;font-weight:400;margin-left:8px}")
                .append("table{border-collapse:collapse;width:100%}.grid th{background:#1f3864;color:#fff;text-align:left;padding:5px 6px;font-size:10.5px}")
                .append(".grid td{border-bottom:1px solid #d5d8dc;padding:4px 6px;vertical-align:middle}.grid tr:nth-child(even) td{background:#f7f9fb}")
                .append(".grid tr.tot td{background:#d6e4f0;font-weight:700}.grid tr.cat td{background:#e8eef6;font-weight:700;color:#1f3864}")
                .append(".toc td.id{width:80px;font-weight:700}.toc td.st{width:70px}")
                .append(".kv td{padding:3px 8px;border-bottom:1px solid #eaeded;vertical-align:top}.kv td:first-child{width:140px;color:#5d6d7e;font-weight:600}")
                .append(".head{background:#f2f6fb;border:1px solid #d6e4f0;margin:6px 0 8px}")
                .append(".c{text-align:center}.sm{font-size:10.5px}.mono{font-family:Consolas,'Courier New',monospace;word-break:break-all}")
                .append(".chip{display:inline-block;padding:1px 9px;border-radius:10px;color:#fff;font-weight:700;font-size:10px}")
                .append(".tag{display:inline-block;padding:0 7px;border-radius:4px;background:#eaeded;font-size:10px;margin:1px 2px}.tag.ok{background:#d5f5e3;color:#1e8449}.tag.v{background:#e8daef;color:#6c3483}")
                .append(".note{background:#fef9e7;border-left:4px solid #d68910;padding:6px 10px}.note.ok{background:#d5f5e3;border-color:#1e8449}")
                .append(".muted{color:#7f8c8d}.line{margin:4px 0 6px}.foot{margin-top:18px;color:#7f8c8d;font-size:10px;border-top:1px solid #d5d8dc;padding-top:6px}")
                .append(".steps{margin:4px 0 6px 18px;padding:0}.steps li{margin-bottom:3px}")
                .append(".ev{border:1px solid #e6b0aa;border-left:5px solid #c0392b;border-radius:5px;padding:6px 10px;margin:8px 0}.ev.ok{border-color:#abebc6;border-left-color:#1e8449}")
                .append(".evh{margin-bottom:4px}.evid{margin-top:4px}.stp{margin:5px 0}.stp .n{display:inline-block;width:17px;height:17px;border-radius:9px;background:#1565c0;color:#fff;text-align:center;font-size:10px;line-height:17px;font-weight:700}")
                .append("pre{background:#1c2833;color:#eaf2f8;font:9.5px/1.4 Consolas,'Courier New',monospace;padding:6px 9px;border-radius:4px;white-space:pre-wrap;word-break:break-all;margin:2px 0 0}")
                .append("tr{page-break-inside:avoid}.stp{page-break-inside:avoid}")
                .append("</style></head><body>");
    }

    private static void kv(StringBuilder b, String k, String v) {
        b.append(kvRow(k, v));
    }

    private static String kvRow(String k, String v) {
        return "<tr><td>" + esc(k) + "</td><td>" + esc(v == null || v.isBlank() ? "-" : v) + "</td></tr>";
    }

    private static String statusChip(String s) {
        String c = "PASS".equals(s) ? "#1e8449" : "FAIL".equals(s) ? "#c0392b" : "SKIP".equals(s) ? "#d68910" : "#7f8c8d";
        return "<span class=\"chip\" style=\"background:" + c + "\">" + esc("NA".equals(s) ? "N/A" : s) + "</span>";
    }

    private static String sevChip(String s) {
        if (s == null || s.isEmpty() || "\u2013".equals(s)) return "<span class=\"muted\">-</span>";
        String c = "Critical".equals(s) ? "#7b0000" : "High".equals(s) ? "#c0392b" : "Medium".equals(s) ? "#d68910" : "Low".equals(s) ? "#2e75b6" : "#7f8c8d";
        return "<span class=\"chip\" style=\"background:" + c + "\">" + esc(s) + "</span>";
    }

    private static String shortApi(Map<String, String> r) {
        return r.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "").trim();
    }

    private static String name(ExcelUtils.ReportMeta m) {
        return m.displayName == null || m.displayName.isEmpty() ? m.project : m.displayName;
    }

    private static String at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(WHEN);
    }

    private static String now() {
        return java.time.LocalDateTime.now().format(WHEN);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n... (" + s.length() + " characters, cut here)" : s;
    }

    /** text for a CSS string (page header) */
    private static String css(String s) {
        return s.replace("\\", "").replace("\"", "").replace("\n", " ");
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: if (c >= 32 || c == '\n' || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }
}
