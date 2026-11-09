package com.api.automation.utils;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;

public class ExcelUtils {

    // ── Colours ─────────────────────────────────────────────────────────────
    private static final String C_DARK_BLUE  = "1F3864";
    private static final String C_MID_BLUE   = "2E75B6";
    private static final String C_LIGHT_BLUE = "D6E4F0";
    private static final String C_HDR_BG     = "1565C0"; // deep blue — white text clearly visible
    private static final String C_GREEN      = "1E8449";
    private static final String C_GREEN_LT   = "D5F5E3";
    private static final String C_RED        = "C0392B";
    private static final String C_RED_LT     = "FADBD8";
    private static final String C_AMBER      = "D68910";
    private static final String C_AMBER_LT   = "FDEBD0";
    private static final String C_WHITE      = "FFFFFF";
    private static final String C_STRIPE     = "F2F3F4";
    private static final String C_BORDER     = "AEB6BF";
    private static final String C_DARK_TXT   = "1C2833";
    private static final String C_PURPLE     = "6C3483"; // negative tests
    private static final String C_PURPLE_LT  = "E8DAEF";
    private static final String C_CRIT       = "7B0000"; // critical severity
    private static final String C_CRIT_LT    = "F5B7B1";
    private static final String C_GREY       = "7F8C8D"; // not executable
    // ✅ Postman-style HTTP method colors
    private static final String C_GET    = "49CC90"; // GET - Green
    private static final String C_POST   = "FCA130"; // POST - Orange
    private static final String C_PUT    = "61AFFE"; // PUT - Blue
    private static final String C_PATCH  = "9012FE"; // PATCH - Purple
    private static final String C_DELETE = "F93E3E"; // DELETE - Red
     private static final String C_ORANGE  = "FFA500";


    public static String extractProjectName(String baseUrl) {
        try {
            String clean = baseUrl.replace("https://", "")
                    .replace("http://", "");

            String domain = clean.split("/")[0];
            String[] parts = domain.split("\\.");

            if (parts.length >= 2) {
                return parts[parts.length - 2];
            }

            return domain;

        } catch (Exception e) {
            return "PROJECT";
        }
    }


    private static String generateReportName(String baseUrl) {

        String project = extractProjectName(baseUrl).toUpperCase();

        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH));

        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH-mm", Locale.ENGLISH));

        return project + "_"+ "Report_" + date + "_" + time + ".xlsx";
    }
    private static final String RESPONSE_KEY = "ActualResponse";
    // ── Column keys ───────────────────────────────
    private static final String[] HEADERS = {
            "TC_ID",
            "Module",
            "API_Name",
            "Method",
            "Endpoint",
            "RequestBody",
            "TestData",
            "Format",
            "Data_Source",
            "ExpectedResult",
            "ActualResponse",
            "ResponseFile",
            "ActualResult",
            "Expected_HTTP",
            "HTTP",
            "Result",
            "Severity",
            "Defect_Status",
            "Failure_Category",
            "Failure_Reason",
            "Action_Needed",
            "VAPT_Ref",
            "AI_Verdict",
            "Error",
            "Executed_At",
            "Exec Time"
    };

    private static final String[] HEADER_LABELS = {
            "TC ID",
            "Module",
            "API Name",
            "Method",
            "Endpoint",
            "Request Body",
            "Test Data",
            "Format (request \u2192 response)",
            "Data source (traceability)",
            "Expected Result",
            "Actual Response",
            "Full Response",
            "Actual Result",
            "Expected HTTP",
            "Actual HTTP",
            "Result",
            "Severity",
            "Status",
            "Failure Category",
            "Why it failed (plain English)",
            "What is needed / who",
            "VAPT / OWASP reference",
            "AI Verdict",
            "Error / Notes",
            "Executed at (date · time)",
            "Exec Time"
    };

    private static final int[] COL_WIDTHS = {
            10,  // TC ID
            20,  // Module
            30,  // API Name
            12,  // Method
            35,  // Endpoint
            45,  // Request Body
            35,  // Test Data
            16,  // Format
            44,  // Data source
            40,  // Expected Result
            80,  // Actual Response
            16,  // Full Response (link)
            40,  // Actual Result
            18,  // Expected HTTP
            10,  // Actual HTTP
            12,  // Result
            11,  // Severity
            18,  // Status
            24,  // Failure Category
            55,  // Why it failed
            45,  // What is needed
            34,  // VAPT / OWASP
            25,  // AI Verdict
            35,  // Error / Notes
            20,  // Executed at
            18   // Exec Time
    };

    /** Run information printed on every sheet (project, env, overall time...). */
    public static class ReportMeta {
        public String project = "";
        public String displayName = "";
        public String env = "QA";
        public String baseUrl = "";
        public long startedAt;
        public long finishedAt;
        public String aiStatus = "";
        public String testedBy = "";
        public int sheetRows, sheetRunN, sheetModuleSkipped;   // test-data rows: all / Run=N / outside the selected modules
        public String vaptPdfName = "", failurePdfName = "";   // PDF reports written next to the Excel
        public String runner = "";      // CKYC_Positive_Negative_Runner (suites/testng-CKYC.xml)

        long overallMs() {
            return startedAt > 0 && finishedAt >= startedAt ? finishedAt - startedAt : 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  READ
    // ════════════════════════════════════════════════════════════════════════
    public static List<Map<String, String>> readData(String path) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        FileInputStream fis = new FileInputStream(path);
        Workbook wb = new XSSFWorkbook(fis);
        Sheet sheet = wb.getSheetAt(0);
        Row header = sheet.getRow(0);
        DataFormatter formatter = new DataFormatter();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            Map<String, String> map = new HashMap<>();
            for (int j = 0; j < header.getLastCellNum(); j++) {
                String key   = formatter.formatCellValue(header.getCell(j)).trim();
                String value = (row != null && row.getCell(j) != null)
                        ? formatter.formatCellValue(row.getCell(j)).trim() : "";
                map.put(key, value);
            }
            map.put("__SHEET", sheet.getSheetName());       // where this test came from (Data source in the report)
            map.put("__ROW", String.valueOf(i + 1));
            list.add(map);
        }

        wb.close();
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  WRITE
    // ════════════════════════════════════════════════════════════════════════
    public static void writeReport(String paths, List<Map<String, String>> data) {
        ReportMeta meta = new ReportMeta();
        meta.baseUrl = data.isEmpty() ? "" : data.get(0).getOrDefault("Base URL", "");
        writeReport(paths, data, meta);
    }

    public static void writeReport(String paths, List<Map<String, String>> data, ReportMeta meta) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {

            // Debug: print all keys from first result so you can verify RESPONSE_KEY
            if (!data.isEmpty()) {
                System.out.println("[ExcelUtils] Keys in result map: " + data.get(0).keySet());
            }

            StyleKit sk = new StyleKit(wb);

            long passed  = data.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOrDefault("Result",""))).count();
            long failed  = data.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result",""))).count();
            long skipped = data.stream().filter(r -> isSkip(r.getOrDefault("Result",""))).count();
            int  total   = (int) wsize(data);   // planned tests: a "Not executable" row counts as its N checks
            double passRate = (passed + failed) > 0 ? (passed * 100.0 / (passed + failed)) : 0;   // of executed
            double avgMs    = data.stream()
                    .filter(r -> !isSkip(r.getOrDefault("Result","")))
                    .mapToDouble(r -> parseMs(r.getOrDefault("Exec Time","0")))
                    .average().orElse(0);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH));
            String baseUrl = meta.baseUrl == null || meta.baseUrl.isEmpty()
                    ? (data.isEmpty() ? "" : data.get(0).getOrDefault("Base URL",""))
                    : meta.baseUrl;
            meta.baseUrl = baseUrl;

            for (Map<String, String> r : data) {
                String[] d = defect(r);
                try {
                    r.put("Severity", d[0]);
                    r.put("Defect_Status", d[1]);
                } catch (UnsupportedOperationException ignore) { }
            }
            // order: Summary | API Results | Positive Results | Negative Results | VAPT Findings | Bug Report | Failure Analysis
            lastGroups = buildApiGroups(data);
            buildSummarySheet(wb, sk, total, passed, failed, skipped, passRate, avgMs, timestamp, meta, data);
            buildApiResultsSheet(wb, sk, lastGroups, meta, timestamp, false);
            buildDetailSheet(wb, sk, data, timestamp, meta, "Positive Results", r -> !isNeg(r),
                    "\u2714  POSITIVE RESULTS  -  every API with its valid test data and result", C_DARK_BLUE);
            buildApiResultsSheet(wb, sk, lastGroups, meta, timestamp, true);
            buildVaptSheets(wb, sk, data, meta, timestamp);
            buildBugReport(wb, sk, data, meta, timestamp);
            buildFailureAnalysis(wb, sk, data, meta);

            String[] tabs = {C_MID_BLUE, C_DARK_BLUE, C_DARK_BLUE, C_PURPLE, C_CRIT, C_CRIT, C_RED};   // Summary, API Results, Positive, Negative, VAPT Findings, Bug Report, Failure Analysis
            for (int i = 0; i < tabs.length; i++) setTabColor(wb, i, tabs[i]);
            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            File file = new File(paths);

            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                wb.write(fos);
            }

        } catch (Exception e) {
            System.err.println("[ExcelUtils] writeReport failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SHEET 1 — Summary Dashboard
    // ════════════════════════════════════════════════════════════════════════
    private static void buildSummarySheet(XSSFWorkbook wb, StyleKit sk,
                                          int total, long passed, long failed, long skipped,
                                          double passRate, double avgMs, String timestamp, ReportMeta meta,
                                          List<Map<String, String>> data) {

        String baseUrl = meta.baseUrl;
        XSSFSheet sh = wb.createSheet("Summary");

        sh.setColumnWidth(0, chars(44));
        for (int i = 1; i <= 10; i++) {
            sh.setColumnWidth(i, chars(17));
        }

        sh.createRow(0).setHeightInPoints(10);

        // 🔥 TITLE (FULL WIDTH MERGE)
        Row r2 = sh.createRow(1);
        r2.setHeightInPoints(45);
        mergedCell(sh, r2, 0, 10,
                "🧪 API Test Execution Report" + (meta.displayName.isEmpty() ? "" : "  —  " + meta.displayName),
                sk.banner(C_DARK_BLUE, 20));

        // 🔥 SUBTITLE
        Row r3 = sh.createRow(2);
        r3.setHeightInPoints(22);
        mergedCell(sh, r3, 0, 10,
                "Generated: " + timestamp + "   |   Tested by: " + meta.testedBy + "   |   Runner: " + meta.runner + "   |   Base URL: " + baseUrl + "   |   Env: " + meta.env
                        + (meta.aiStatus.isEmpty() ? "" : "   |   " + meta.aiStatus),
                sk.subtitle());

        sh.createRow(3).setHeightInPoints(12);

        List<Map<String, String>> pos = new ArrayList<>(), neg = new ArrayList<>();
        for (Map<String, String> r : data) (isNeg(r) ? neg : pos).add(r);
        List<com.api.automation.report.VaptModel.Point> vpts = com.api.automation.report.VaptModel.build(data);
        long vaptTested = vpts.stream().filter(x -> !"NA".equals(x.status)).count();
        long vaptOpen = vpts.stream().filter(x -> "FAIL".equals(x.status)).count();
        long critHigh = data.stream().filter(r -> r.getOrDefault("Defect_Status", "").startsWith("Open")
                && ("Critical".equals(r.get("Severity")) || "High".equals(r.get("Severity")))).count();

        // KPI tiles
        Row r5 = sh.createRow(4);
        String[] lbls = {"Total Tests", "Positive", "Negative", "Passed \u2714", "Failed \u2716", "Skipped \u26A0",
                "Not executable", "Pass Rate", "VAPT points open", "Critical + High", "Overall Time"};
        String[] lclrs = {C_MID_BLUE, C_DARK_BLUE, C_PURPLE, C_GREEN, C_RED, C_AMBER, C_GREY, C_MID_BLUE, C_RED, C_CRIT, C_DARK_BLUE};
        long notExec = data.stream().filter(r -> "N/A".equals(r.get("Result"))).mapToLong(ExcelUtils::w).sum();
        String[] vals = {String.valueOf(total), String.valueOf(wsize(pos)), String.valueOf(wsize(neg)),
                String.valueOf(passed), String.valueOf(failed), String.valueOf(skipped), String.valueOf(notExec),
                String.format("%.1f%%", passRate), vaptOpen + " of " + vaptTested, String.valueOf(critHigh),
                formatDuration(meta.overallMs())};
        String[] vbgs = {C_LIGHT_BLUE, C_LIGHT_BLUE, C_PURPLE_LT, C_GREEN_LT, C_RED_LT, C_AMBER_LT, C_STRIPE, C_LIGHT_BLUE, C_RED_LT, C_CRIT_LT, C_LIGHT_BLUE};
        Row r6 = sh.createRow(5);
        r6.setHeightInPoints(30);
        for (int i = 0; i < lbls.length; i++) {
            Cell c = r5.createCell(i);
            c.setCellValue(lbls[i]);
            c.setCellStyle(sk.kpiLabel(lclrs[i]));
            Cell v = r6.createCell(i);
            v.setCellValue(vals[i]);
            v.setCellStyle(sk.kpiValue(vbgs[i], lclrs[i]));
        }
        int rowNum = 7;

        // 1. overall - positive vs negative, with the executed count
        List<Object[]> t1 = new ArrayList<>();
        t1.add(overallRow8("Positive  (valid data)", pos));
        t1.add(overallRow8("Negative  (invalid / security)", neg));
        t1.add(overallRow8("TOTAL", data));
        rowNum = pivot(sh, sk, rowNum, "1.  TESTS  -  every positive and negative request, counted one by one",
                new String[]{"Test type", "Total", "Executed (Pass + Fail)", "Passed", "Failed", "Skipped", "Not executable", "Pass %"}, t1, true);

        // 1. APIs - the result of each API (positive + all its negatives), module by module
        List<ApiGroup> grp = lastGroups;
        Map<String, List<ApiGroup>> gm = new LinkedHashMap<>();
        for (ApiGroup g : grp) gm.computeIfAbsent(g.module.isEmpty() ? "(no module)" : g.module, x -> new ArrayList<>()).add(g);
        List<Object[]> ta = new ArrayList<>();
        for (Map.Entry<String, List<ApiGroup>> e : gm.entrySet()) ta.add(apiRow(e.getKey(), e.getValue()));
        ta.add(apiRow("TOTAL", grp));
        rowNum = pivot(sh, sk, rowNum, "2.  APIs  -  one result per API: PASS only if its positive test and ALL its negative tests pass",
                new String[]{"Module", "APIs", "Passed", "Failed", "Skipped", "Not executable", "Pass %"}, ta, true);

        // 2. defects by severity and status (whole run)
        rowNum = pivot(sh, sk, rowNum, "3.  DEFECTS  -  by severity and status",
                STATUS_HDR, severityTable(data), true);

        // 4. NEGATIVE checks by type (a negative PASSES when the API rejects the bad input)
        Map<String, List<Map<String, String>>> byCheck = new LinkedHashMap<>();
        for (Map<String, String> r : neg) {
            String k = r.getOrDefault("NegTitle", "");
            byCheck.computeIfAbsent(k.isEmpty() ? "(other)" : k, x -> new ArrayList<>()).add(r);
        }
        List<Object[]> t3 = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> e3 : byCheck.entrySet()) {
            List<Map<String, String>> rs = e3.getValue();
            long p = count(rs, "PASS"), f = count(rs, "FAIL"), s = wsize(rs) - p - f;
            t3.add(new Object[]{e3.getKey(), wsize(rs), p, f, s, pct(p, f), highestOpen(rs)});
        }
        t3.addAll(overallRowList7("TOTAL", neg));
        rowNum = pivot(sh, sk, rowNum, "4.  NEGATIVE TESTS  -  by check  (passed = API rejected it, failed = API accepted it)",
                new String[]{"Negative check", "Total", "Passed", "Failed", "Skipped / N/A", "Pass %", "Highest open severity"}, t3, true);

        // 1b. where the numbers come from (formulas in words)
        long cPass = count(data, "PASS"), cFail = count(data, "FAIL");
        long cSkip = data.stream().filter(r -> isSkip(r.getOrDefault("Result", ""))).count();
        long cNotExec = wsize(data) - cPass - cFail - cSkip;
        int runY = meta.sheetRows - meta.sheetRunN - meta.sheetModuleSkipped;
        Object[][] how = {
                {"APIs in the test-data Excel", (long) meta.sheetRows, "Every row of the test-data sheet (one row = one API call)."},
                {"  not selected (Run = N)", (long) meta.sheetRunN, "Switched off in the sheet. Not executed and not counted anywhere below."},
                {"  outside the chosen module(s)", (long) meta.sheetModuleSkipped, "Left out by -Dmodule=... Not executed and not counted below."},
                {"APIs selected to run (Run = Y)", (long) runY, "= APIs in the sheet - Run=N - outside the chosen modules."},
                {"Positive tests", wsize(pos), "One per selected API (an API with several payloads makes several tests)."},
                {"Negative tests (planned)", wsize(neg), "Built from the positive ones: data, date and security checks (TCxxx_01, _02 ...)."},
                {"TOTAL tests", (long) total, "= Positive + Negative."},
                {"Executed (request sent)", cPass + cFail, "= Passed + Failed."},
                {"Passed", cPass, "Positive: the API answered correctly.  Negative: the API rejected the bad input."},
                {"Failed", cFail, "Positive: wrong answer or error.  Negative: the API accepted the bad input, crashed or leaked details."},
                {"Skipped", cSkip, "Positive test with no data from an earlier step, so it was not sent."},
                {"Not executable", cNotExec, "Negatives not run because their positive API was unusable (404 / 403 / skipped). One row stands for all the checks it replaces."},
                {"Check:  Passed + Failed + Skipped + Not executable", cPass + cFail + cSkip + cNotExec, "Must equal TOTAL tests (" + total + ")."},
                {"Pass %", null, String.format("= Passed / (Passed + Failed) = %d / %d = %.1f%%", cPass, cPass + cFail, (cPass + cFail) > 0 ? cPass * 100.0 / (cPass + cFail) : 0.0)}
        };
        Row hh = sh.createRow(rowNum++);
        hh.setHeightInPoints(24);
        Cell h0 = hh.createCell(0); h0.setCellValue("How the counts are made"); h0.setCellStyle(sk.tableHeader());
        Cell h1 = hh.createCell(1); h1.setCellValue("Count"); h1.setCellStyle(sk.tableHeader());
        put(hh, 2, "How it is calculated", sk.tableHeader());
        for (int cc = 3; cc <= 10; cc++) put(hh, cc, "", sk.tableHeader());
        int hi = 0;
        XSSFCellStyle nwWhite = wb.createCellStyle();
        nwWhite.cloneStyleFrom(sk.dataLeft(C_WHITE));
        nwWhite.setWrapText(false);
        XSSFCellStyle nwStripe = wb.createCellStyle();
        nwStripe.cloneStyleFrom(sk.dataLeft(C_STRIPE));
        nwStripe.setWrapText(false);
        for (Object[] hw : how) {
            if ("  outside the chosen module(s)".equals(hw[0]) && meta.sheetModuleSkipped == 0) continue;
            Row hr = sh.createRow(rowNum++);
            hr.setHeightInPoints(20);
            String bg = (hi++ % 2 == 1) ? C_STRIPE : C_WHITE;
            Cell a = hr.createCell(0); a.setCellValue((String) hw[0]); a.setCellStyle(sk.dataLeft(bg));
            Cell b2 = hr.createCell(1);
            if (hw[1] != null) b2.setCellValue(((Number) hw[1]).doubleValue());
            else b2.setCellValue(String.format("%.1f%%", (cPass + cFail) > 0 ? cPass * 100.0 / (cPass + cFail) : 0.0));
            b2.setCellStyle(sk.dataCenter(bg));
            XSSFCellStyle nw = bg.equals(C_STRIPE) ? nwStripe : nwWhite;
            put(hr, 2, (String) hw[2], nw);
            for (int cc = 3; cc <= 10; cc++) put(hr, cc, "", nw);
        }
        sh.createRow(rowNum++).setHeightInPoints(8);

        if (meta.vaptPdfName != null && !meta.vaptPdfName.isEmpty()) {
            Row pdfRow = sh.createRow(rowNum++);
            mergedCell(sh, pdfRow, 0, 10, "Detailed reports in this folder (PDF):   VAPT report  \u2192  " + meta.vaptPdfName + "     |     Failure report (steps to reproduce, request / response)  \u2192  " + meta.failurePdfName, sk.subtitle());
        }
        // legend
        Row lg = sh.createRow(rowNum++);
        mergedCell(sh, lg, 0, 10, "Status:  Closed = passed  |  Open - Bug = API behaves wrongly (developer)  |  Open - Environment = endpoint not deployed / DB / server down  |  "
                + "Open - Test Data = no customer in that stage today  |  Blocked = an earlier step had no data  |  Not Applicable = not executable (endpoint not usable)", sk.subtitle());
        Row lg2 = sh.createRow(rowNum++);
        mergedCell(sh, lg2, 0, 10, "Severity (negatives = proven impact):  Critical = auth bypass with data, login with wrong password  |  High = proven SQL injection / XSS, "
                + "other user's records (IDOR), CSRF or data change accepted  |  Medium = internal error details leaked  |  Low = missing validation with no impact, "
                + "no size limit, encryption / integrity not enforced  |  Info = functional (QA, not VAPT) or not run", sk.subtitle());

        // 🔥 OVERALL TIME TAKEN (end of report)
        double apiMs = data.stream().mapToDouble(r -> parseMs(r.getOrDefault("Exec Time", "0"))).sum();
        sh.createRow(rowNum++).setHeightInPoints(10);
        Row timeHdr = sh.createRow(rowNum++);
        mergedCell(sh, timeHdr, 0, 10, "OVERALL TIME TAKEN", sk.sectionHeader());

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH);
        String[][] timeRows = {
                {"Execution Started", meta.startedAt > 0 ? toLocal(meta.startedAt).format(f) : "-"},
                {"Execution Finished", meta.finishedAt > 0 ? toLocal(meta.finishedAt).format(f) : timestamp},
                {"Total API Response Time", formatMs(apiMs)},
                {"Overall Time Taken", formatDuration(meta.overallMs())}
        };
        for (String[] tr : timeRows) {
            Row row = sh.createRow(rowNum++);
            row.setHeightInPoints(20);
            Cell k = row.createCell(0);
            k.setCellValue(tr[0]);
            k.setCellStyle(sk.tableHeader());
            Row cur = row;
            mergedCell(sh, cur, 1, 2, tr[1], sk.kpiValueSmall());
        }
        Row last = sh.getRow(rowNum - 1);
        last.getCell(1).setCellStyle(sk.footerCell());
        last.getCell(2).setCellStyle(sk.footerCell());
    }
    // ════════════════════════════════════════════════════════════════════════
    //  SHEET 2/3/4 — Detailed / Filtered
    // ════════════════════════════════════════════════════════════════════════
    private static void buildDetailSheet(XSSFWorkbook wb, StyleKit sk,
                                         List<Map<String, String>> allData,
                                         String timestamp, ReportMeta meta,
                                         String sheetName, java.util.function.Predicate<Map<String, String>> filter,
                                         String title, String bannerBg) {

        String baseUrl = meta.baseUrl;
        XSSFSheet sh = wb.createSheet(sheetName);
        sh.setColumnWidth(0, chars(3));
        for (int i = 0; i < COL_WIDTHS.length; i++)
            sh.setColumnWidth(1 + i, chars(COL_WIDTHS[i]));

        int lastCol = HEADERS.length;

        sh.createRow(0).setHeightInPoints(8);

        // Banner row
        Row r2 = sh.createRow(1); r2.setHeightInPoints(40);
        mergedCell(sh, r2, 1, lastCol, title, sk.banner(bannerBg, 14));

        // Subtitle row — Date + BaseURL + Env (3 things, no separate column)
        Row r3 = sh.createRow(2); r3.setHeightInPoints(20);
        mergedCell(sh, r3, 1, lastCol,
                "Generated: " + timestamp + "    |    Tested by: " + meta.testedBy + "    |    Runner: " + meta.runner + "    |    Base URL: " + baseUrl + "    |    Env: " + meta.env
                        + "    |    Suite: " + (meta.displayName.isEmpty() ? "REST API Validation" : meta.displayName),
                sk.subtitle());

        sh.createRow(3).setHeightInPoints(8);

        // Column header row — uses HEADER_LABELS (readable names, not key names)
        Row r5 = sh.createRow(4); r5.setHeightInPoints(32);
        for (int i = 0; i < HEADER_LABELS.length; i++) {
            Cell c = r5.createCell(1 + i);
            c.setCellValue(HEADER_LABELS[i]);
            c.setCellStyle(sk.tableHeader()); // deep blue bg + white bold text
        }
        sh.createFreezePane(4, 5);

        // Filter
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> r : allData)
            if (filter.test(r)) rows.add(r);

        // Data rows
        int rowNum = 5;
        for (int idx = 0; idx < rows.size(); idx++) {
            Map<String, String> rd = rows.get(idx);
            Row row = sh.createRow(rowNum++);
            row.setHeightInPoints(44);
            boolean stripe = (idx % 2 == 1);
            String bg = stripe ? C_STRIPE : C_WHITE;

            for (int col = 0; col < HEADERS.length; col++) {
                String key = HEADERS[col];
                String val = rd.getOrDefault(key, "");
                boolean negRow = isNeg(rd);
                boolean firstOfGroup = idx == 0 || !baseTc(rows.get(idx - 1)).equals(baseTc(rd));
                if (negRow && firstOfGroup && "API_Name".equals(key)) val = val.replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "");
                if (negRow && (!firstOfGroup && ("Module".equals(key) || "API_Name".equals(key) || "Method".equals(key)
                        || "Endpoint".equals(key)) || "RequestBody".equals(key))) {
                    Cell c0 = row.createCell(1 + col);
                    c0.setCellValue("\u2013");
                    c0.setCellStyle(sk.dataCenter(C_STRIPE));
                    continue;
                }
                Cell cell  = row.createCell(1 + col);

                if ("Method".equals(key)) {
                    cell.setCellValue(val);
                    cell.setCellStyle(sk.methodBadge(methodColor(val)));

                } else if ("Result".equals(key)) {
                    cell.setCellValue(val);
                    if ("PASS".equalsIgnoreCase(val))      cell.setCellStyle(sk.resultBadge(C_GREEN));
                    else if ("FAIL".equalsIgnoreCase(val)) cell.setCellStyle(sk.resultBadge(C_RED));
                    else if ("N/A".equalsIgnoreCase(val))  cell.setCellStyle(sk.resultBadge(C_GREY));
                    else                                   cell.setCellStyle(sk.resultBadge(C_AMBER));

                } else if ("ActualResult".equals(key)) {

                    cell.setCellValue(val);

                    String res = rd.getOrDefault("Result","");

                    if ("PASS".equalsIgnoreCase(res)) {
                        cell.setCellStyle(sk.resultBadge(C_GREEN));
                    } else if ("FAIL".equalsIgnoreCase(res)) {
                        cell.setCellStyle(sk.resultBadge(C_RED));
                    } else {
                        cell.setCellStyle(sk.resultBadge(C_AMBER));
                    }
                }

                else if ("Exec Time".equals(key)) {
                    cell.setCellValue(formatMs(parseMs(val)));
                    cell.setCellStyle(sk.dataCenter(bg));

                } else if (key.equals(RESPONSE_KEY) || "RequestBody".equals(key)) {

                    cell.setCellValue(fitCell(val));
                    cell.setCellStyle(sk.responseCell(stripe));

                } else if ("ResponseFile".equals(key)) {

                    if (!val.isEmpty()) {
                        cell.setCellValue("Open ↗");
                        org.apache.poi.common.usermodel.HyperlinkType ht = org.apache.poi.common.usermodel.HyperlinkType.FILE;
                        XSSFHyperlink link = wb.getCreationHelper().createHyperlink(ht);
                        link.setAddress(val.replace(" ", "%20"));
                        cell.setHyperlink(link);
                        cell.setCellStyle(sk.linkCell(bg));
                    } else {
                        cell.setCellValue("");
                        cell.setCellStyle(sk.dataCenter(bg));
                    }

                } else if ("HTTP".equals(key)) {

                    cell.setCellValue(val.isEmpty() || "0".equals(val) ? "\u2013" : val + ("MISMATCH".equals(rd.get("HTTP_Check")) ? "  \u26A0" : ""));
                    String chk = rd.getOrDefault("HTTP_Check", "");
                    cell.setCellStyle("OK".equals(chk) ? sk.statusCell(C_GREEN_LT, C_GREEN)
                            : "MISMATCH".equals(chk) ? sk.statusCell(C_AMBER_LT, C_AMBER) : sk.dataCenter(bg));

                } else if ("Severity".equals(key) || "Defect_Status".equals(key)) {

                    cell.setCellValue(val);
                    String[] sc = "Severity".equals(key) ? severityColors(val) : statusColors(val);
                    cell.setCellStyle(sc == null ? sk.dataCenter(bg) : sk.statusCell(sc[0], sc[1]));

                } else if ("Failure_Category".equals(key)) {

                    cell.setCellValue(val);
                    if (val.isEmpty()) cell.setCellStyle(sk.dataCenter(bg));
                    else if (val.startsWith("NOT NEEDED")) cell.setCellStyle(sk.statusCell(C_LIGHT_BLUE, C_DARK_BLUE));
                    else if ("SKIP".equalsIgnoreCase(rd.getOrDefault("Result", ""))) cell.setCellStyle(sk.statusCell(C_AMBER_LT, C_AMBER));
                    else cell.setCellStyle(sk.statusCell(C_RED_LT, C_RED));

                } else if ("Failure_Reason".equals(key) || "Action_Needed".equals(key)) {

                    cell.setCellValue(val);
                    cell.setCellStyle(sk.dataLeft(bg));

                } else if ("AI_Verdict".equals(key)) {

                    cell.setCellValue(fitCell(val));
                    if (val.startsWith("PASS"))      cell.setCellStyle(sk.statusCell(C_GREEN_LT, C_GREEN));
                    else if (val.startsWith("FAIL")) cell.setCellStyle(sk.statusCell(C_RED_LT, C_RED));
                    else                             cell.setCellStyle(sk.dataCenter(bg));

                }

                else if ("Result".equals(key)) {

                    cell.setCellValue(val);

                    if ("PASS".equalsIgnoreCase(val)) {
                        cell.setCellStyle(sk.resultBadge(C_GREEN));
                    } else if ("FAIL".equalsIgnoreCase(val)) {
                        cell.setCellStyle(sk.resultBadge(C_RED));
                    } else if ("SKIPPED".equalsIgnoreCase(val)) {
                        cell.setCellStyle(sk.resultBadge(C_ORANGE));
                    }
                }

                else {
                    cell.setCellValue(fitCell(val));
                    cell.setCellStyle(sk.dataCenter(bg));
                }
            }
        }

        // Footer
        sh.createRow(rowNum).setHeightInPoints(4);
        Row footer = sh.createRow(rowNum + 1); footer.setHeightInPoints(24);
        long p = rows.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOrDefault("Result",""))).count();
        long f = rows.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result",""))).count();
        long s = rows.stream().filter(r -> isSkip(r.getOrDefault("Result",""))).count();
        double avg = rows.stream().filter(r -> !isSkip(r.getOrDefault("Result","")))
                .mapToDouble(r -> parseMs(r.getOrDefault("Exec Time","0"))).average().orElse(0);
        mergedCell(sh, footer, 1, lastCol,
                String.format("Total: %d    |    ✔ PASS: %d    |    ✗ FAIL: %d    |    ⚠ SKIP: %d    |    Pass Rate: %.1f%%    |    Avg Exec Time: %s",
                        rows.size(), p, f, s, rows.isEmpty() ? 0 : p * 100.0 / rows.size(), formatMs(avg)),
                sk.footerCell());

        // Overall time taken (end of report)
        Row time = sh.createRow(rowNum + 2); time.setHeightInPoints(24);
        mergedCell(sh, time, 1, lastCol,
                "⏱  Overall Time Taken: " + formatDuration(meta.overallMs())
                        + (meta.startedAt > 0
                        ? "    |    Started: " + toLocal(meta.startedAt).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH))
                        + "    |    Finished: " + toLocal(meta.finishedAt).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH))
                        : ""),
                sk.subtitle());
    }


    // ════════════════════════════════════════════════════════════════════════
    //  BUG REPORT - one clear, ready-to-log bug per failed test (Jira style)
    // ════════════════════════════════════════════════════════════════════════
    private static final List<String> SEV_ORDER = java.util.Arrays.asList("Critical", "High", "Medium", "Low", "Info", "\u2013");

    /** "Get Customer Details accepts very large payload" / "Get Table Master fails - SERVER ERROR (HTTP 500)" */
    static String bugTitle(Map<String, String> r) {
        String api = r.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "").replaceAll("^\\d+ [^>]*> ", "").trim();
        String cat = r.getOrDefault("Failure_Category", "");
        String http = r.getOrDefault("HTTP", "");
        if (isNeg(r)) {
            String check = r.getOrDefault("NegTitle", "invalid input");
            if ("STATE_CHECK".equals(r.get("NegCategory"))) return api + " stops working after invalid requests (session / data changed)";
            if (cat.startsWith("SERVER ERROR")) return api + " crashes (HTTP " + http + ") on '" + check + "'";
            if (cat.startsWith("INTERNAL ERROR")) return api + " exposes internal error details on '" + check + "'";
            return api + " accepts '" + check + "' instead of rejecting it";
        }
        return api + " fails - " + (cat.isEmpty() ? "unexpected result" : cat.toLowerCase()) + (http.isEmpty() || "0".equals(http) ? "" : " (HTTP " + http + ")");
    }

    /** Clear, self-contained description: steps, expected, actual, impact, fix. */
    static String bugDescription(Map<String, String> r, String testedBy) {
        String method = r.getOrDefault("Method", "");
        String ep = r.getOrDefault("Endpoint", "");
        String body = r.getOrDefault("RequestBody", "");
        if (body.length() > 600) body = body.substring(0, 600) + " ...";
        StringBuilder b = new StringBuilder();
        b.append("Summary: ").append(bugTitle(r)).append("\n\n");
        b.append("Steps to reproduce:\n");
        b.append("  1. Log in to ").append(r.getOrDefault("Base URL", "the application")).append(testedBy.isEmpty() ? "" : " (tested by " + testedBy + ")").append("\n");
        b.append("  2. Send ").append(method).append(" ").append(ep).append("\n");
        if (isNeg(r)) b.append("  3. Invalid input used - ").append(r.getOrDefault("TestData", "")).append("\n");
        if (!body.isBlank() && !"NA".equals(body)) b.append("  Request body: ").append(body.replaceAll("\\s+", " ")).append("\n");
        b.append("\nExpected: ").append(r.getOrDefault("ExpectedResult", "")).append("\n");
        b.append("Actual:   ").append(r.getOrDefault("ActualResult", "")).append("\n");
        String why = r.getOrDefault("Failure_Reason", "");
        if (!why.isBlank()) b.append("\nImpact / root cause: ").append(why).append("\n");
        String fix = r.getOrDefault("Action_Needed", "");
        if (!fix.isBlank()) b.append("Fix needed: ").append(fix).append("\n");
        b.append("\nHTTP: expected ").append(r.getOrDefault("Expected_HTTP", "")).append(", got ").append(r.getOrDefault("HTTP", ""));
        String vapt = r.getOrDefault("VAPT_Ref", "");
        if (!vapt.isBlank()) b.append("\nVAPT reference: ").append(vapt);
        b.append("\nSeverity: ").append(r.getOrDefault("Severity", "")).append("   |   Test case: ").append(r.getOrDefault("TC_ID", ""));
        return b.toString();
    }

    private static void buildBugReport(XSSFWorkbook wb, StyleKit sk, List<Map<String, String>> data, ReportMeta meta, String timestamp) {
        XSSFSheet sh = wb.createSheet("Bug Report");
        String[] labels = {"Bug ID", "Severity", "Status", "Type", "Module", "Bug title", "Bug description (steps · expected · actual · fix)",
                "TC ID", "HTTP", "Tested by", "Tested on", "Full Response", "VAPT / OWASP reference"};
        int[] widths = {10, 10, 17, 10, 20, 46, 110, 11, 7, 16, 18, 13, 34};
        for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, chars(widths[i]));

        List<Map<String, String>> bugs = new ArrayList<>();
        for (Map<String, String> r : data) if ("FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) bugs.add(r);
        bugs.sort(java.util.Comparator.comparingInt((Map<String, String> r) -> {
                    int i = SEV_ORDER.indexOf(r.getOrDefault("Severity", ""));
                    return i < 0 ? 99 : i;
                }).thenComparing(r -> r.getOrDefault("Defect_Status", ""))
                .thenComparing(r -> r.getOrDefault("TC_ID", "")));

        Row t0 = sh.createRow(0); t0.setHeightInPoints(34);
        mergedCell(sh, t0, 0, labels.length - 1, "Bug Report  -  every failed test as a ready-to-log defect (most severe first)", sk.banner(C_CRIT, 15));
        Row t1 = sh.createRow(1); t1.setHeightInPoints(20);
        long bug = bugs.stream().filter(r -> "Open - Bug".equals(r.get("Defect_Status"))).count();
        mergedCell(sh, t1, 0, labels.length - 1, "Tested by: " + meta.testedBy + "    |    " + timestamp + "    |    Open - Bug: " + bug
                + "    |    Open - Environment / Test Data: " + (bugs.size() - bug)
                + "    |    Copy the title + description straight into Jira", sk.subtitle());
        Row h = sh.createRow(2); h.setHeightInPoints(30);
        for (int i = 0; i < labels.length; i++) {
            Cell c = h.createCell(i); c.setCellValue(labels[i]); c.setCellStyle(sk.tableHeader());
        }
        String day = timestamp.length() >= 11 ? timestamp.substring(0, 11) : timestamp;
        int rn = 3, n = 1;
        for (Map<String, String> r : bugs) {
            Row row = sh.createRow(rn++);
            String desc = bugDescription(r, meta.testedBy);
            row.setHeightInPoints(Math.min(400, 15f * (desc.split("\n").length + 1)));
            String[] vals = {String.format("BUG-%03d", n++), r.getOrDefault("Severity", ""), r.getOrDefault("Defect_Status", ""),
                    isNeg(r) ? "Negative" : "Positive", r.getOrDefault("Module", ""), bugTitle(r), desc,
                    r.getOrDefault("TC_ID", ""), r.getOrDefault("HTTP", ""), meta.testedBy, day, r.getOrDefault("ResponseFile", ""),
                    r.getOrDefault("VAPT_Ref", "")};
            for (int i = 0; i < vals.length; i++) {
                Cell c = row.createCell(i);
                String v = vals[i];
                String[] sc = i == 1 ? severityColors(v) : i == 2 ? statusColors(v) : null;
                if (sc != null) {
                    c.setCellValue(v);
                    c.setCellStyle(sk.statusCell(sc[0], sc[1]));
                } else if (i == 11 && !v.isEmpty()) {
                    c.setCellValue("Open \u2197");
                    XSSFHyperlink link = wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.FILE);
                    link.setAddress(v.replace(" ", "%20"));
                    c.setHyperlink(link);
                    c.setCellStyle(sk.linkCell(C_WHITE));
                } else {
                    c.setCellValue(fitCell(v));
                    c.setCellStyle(i == 5 || i == 6 ? sk.dataLeft(C_WHITE) : sk.dataCenter(C_WHITE));
                }
            }
        }
        if (bugs.isEmpty()) {
            mergedCell(sh, sh.createRow(rn), 0, labels.length - 1, "No bugs - every executed test passed.", sk.statusCell(C_GREEN_LT, C_GREEN));
        } else {
            sh.setAutoFilter(new CellRangeAddress(2, rn - 1, 0, labels.length - 1));
        }
        sh.createFreezePane(2, 3);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  API COVERAGE - one row per API, one column per check (VAPT matrix)
    // ════════════════════════════════════════════════════════════════════════
    private static final String[][] COV = {
            {"TAMPERED_TOKEN", "Tampered token"}, {"TAMPERED_CSRF", "Wrong CSRF"}, {"NO_SESSION", "No session"},
            {"OTHER_USER", "Other user (BOLA)"}, {"TAMPERED_ENCRYPTION", "Tampered encryption"}, {"WRONG_METHOD", "Wrong method"},
            {"LARGE_PAYLOAD", "1 MB body"}, {"INJECTION", "SQL injection"}, {"XSS_SCRIPT", "Script / XSS"},
            {"NOT_EXISTING", "Not-existing id"}, {"WRONG_TYPE", "Wrong type"}, {"MISSING_FIELD", "Missing field"},
            {"EMPTY_VALUE", "Empty"}, {"SPACES_ONLY", "Spaces only"}, {"BOUNDARY", "Too long"}, {"SPECIAL_CHARS", "Special chars"},
            {"UNICODE", "Unicode"}, {"INVALID_CODE", "Invalid code"}, {"DATE_INVALID", "Bad date"}, {"STATE_CHECK", "Still works after"}};

    private static void buildCoverage(XSSFWorkbook wb, StyleKit sk, List<Map<String, String>> data, ReportMeta meta, String timestamp) {
        XSSFSheet sh = wb.createSheet("API Coverage");
        int fixed = 4;
        sh.setColumnWidth(0, chars(10));
        sh.setColumnWidth(1, chars(20));
        sh.setColumnWidth(2, chars(38));
        sh.setColumnWidth(3, chars(13));
        for (int i = 0; i < COV.length; i++) sh.setColumnWidth(fixed + i, chars(11));

        // group rows by API (TC047, TC047_05, TC047_NA -> TC047)
        Map<String, List<Map<String, String>>> byApi = new LinkedHashMap<>();
        for (Map<String, String> r : data) {
            String base = r.getOrDefault("TC_ID", "").replaceAll("_(\\d+|NA|RL)$", "");
            byApi.computeIfAbsent(base, x -> new ArrayList<>()).add(r);
        }

        Row t0 = sh.createRow(0); t0.setHeightInPoints(34);
        mergedCell(sh, t0, 0, fixed + COV.length - 1, "API Coverage  -  every API x every check   (\u2713 correct  \u26A0 rejected, wrong code  \u2717 bug  N/A not executable  \u2013 not applicable)", sk.banner(C_DARK_BLUE, 14));
        Row t1 = sh.createRow(1); t1.setHeightInPoints(20);
        mergedCell(sh, t1, 0, fixed + COV.length - 1, "Tested by: " + meta.testedBy + "    |    " + timestamp
                + "    |    Expected HTTP per check: docs/API_Test_Rulebook.md", sk.subtitle());
        Row h = sh.createRow(2); h.setHeightInPoints(42);
        String[] fh = {"TC ID", "Module", "API", "Positive test"};
        for (int i = 0; i < fixed; i++) { Cell c = h.createCell(i); c.setCellValue(fh[i]); c.setCellStyle(sk.tableHeader()); }
        for (int i = 0; i < COV.length; i++) { Cell c = h.createCell(fixed + i); c.setCellValue(COV[i][1]); c.setCellStyle(sk.tableHeader()); }

        int rn = 3;
        for (Map.Entry<String, List<Map<String, String>>> e : byApi.entrySet()) {
            List<Map<String, String>> rs = e.getValue();
            Map<String, String> pos = rs.stream().filter(r -> !isNeg(r)).findFirst().orElse(null);
            if (pos == null) continue;
            boolean na = rs.stream().anyMatch(r -> "N/A".equals(r.get("Result")));
            Row row = sh.createRow(rn++);
            String[] v = {e.getKey(), pos.getOrDefault("Module", ""), pos.getOrDefault("API_Name", "").replaceAll("^\\d+ [^>]*> ", ""),
                    pos.getOrDefault("Result", "") + (pos.getOrDefault("HTTP", "").isEmpty() ? "" : " \u00B7 " + pos.get("HTTP"))};
            for (int i = 0; i < fixed; i++) {
                Cell c = row.createCell(i);
                c.setCellValue(v[i]);
                if (i == 3) {
                    String res = pos.getOrDefault("Result", "");
                    c.setCellStyle("PASS".equals(res) ? sk.statusCell(C_GREEN_LT, C_GREEN) : "FAIL".equals(res) ? sk.statusCell(C_RED_LT, C_RED) : sk.statusCell(C_AMBER_LT, C_AMBER));
                } else c.setCellStyle(i == 2 ? sk.dataLeft(C_WHITE) : sk.dataCenter(C_WHITE));
            }
            for (int i = 0; i < COV.length; i++) {
                String cat = COV[i][0];
                List<Map<String, String>> cs = new ArrayList<>();
                for (Map<String, String> r : rs) if (cat.equals(r.get("NegCategory"))) cs.add(r);
                String mark; String[] col;
                if (cs.isEmpty()) {
                    mark = na && !"STATE_CHECK".equals(cat) ? "N/A" : "\u2013";
                    col = new String[]{C_STRIPE, C_DARK_TXT};
                } else if (cs.stream().anyMatch(r -> "FAIL".equals(r.get("Result")))) {
                    mark = "\u2717 " + cs.stream().filter(r -> "FAIL".equals(r.get("Result"))).map(r -> r.getOrDefault("HTTP", "")).findFirst().orElse("");
                    col = new String[]{C_RED_LT, C_RED};
                } else if (cs.stream().anyMatch(r -> "MISMATCH".equals(r.get("HTTP_Check")))) {
                    mark = "\u26A0 " + cs.stream().filter(r -> "MISMATCH".equals(r.get("HTTP_Check"))).map(r -> r.getOrDefault("HTTP", "")).findFirst().orElse("");
                    col = new String[]{C_AMBER_LT, C_AMBER};
                } else if (cs.stream().allMatch(r -> "PASS".equals(r.get("Result")))) {
                    mark = "\u2713 " + cs.get(0).getOrDefault("HTTP", "");
                    col = new String[]{C_GREEN_LT, C_GREEN};
                } else {
                    mark = "skip";
                    col = new String[]{C_AMBER_LT, C_AMBER};
                }
                Cell c = row.createCell(fixed + i);
                c.setCellValue(mark.trim());
                c.setCellStyle(sk.statusCell(col[0], col[1]));
            }
        }
        sh.setAutoFilter(new CellRangeAddress(2, Math.max(2, rn - 1), 0, fixed + COV.length - 1));
        sh.createFreezePane(fixed, 3);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  API RESULTS  -  one parent row per API, its positive and negative tests as collapsible child rows
    //  An API passes only if its positive test AND every negative test pass.
    // ════════════════════════════════════════════════════════════════════════
    static final class ApiGroup {
        String tc = "", module = "", api = "", method = "", endpoint = "";
        String result = "NOT EXEC", severity = "–", reason = "";
        final List<Map<String, String>> pos = new ArrayList<>();
        final List<Map<String, String>> neg = new ArrayList<>();
        final List<Map<String, String>> other = new ArrayList<>();
        long negPass, negFail, negSkip, negNa;
        String posResult = "", negResult = "", negReason = "";
        int no;

        long negPlanned() { return negPass + negFail + negSkip + negNa; }
    }

    private static volatile List<ApiGroup> lastGroups = new ArrayList<>();

    private static String groupTc(Map<String, String> r) {
        return r.getOrDefault("TC_ID", "").replaceAll("_(\\d+|NA|RL)$", "");
    }

    static List<ApiGroup> buildApiGroups(List<Map<String, String>> data) {
        Map<String, ApiGroup> by = new LinkedHashMap<>();
        for (Map<String, String> r : data) {
            String tc = groupTc(r);
            ApiGroup g = by.computeIfAbsent(tc, k -> new ApiGroup());
            g.tc = tc;
            String id = r.getOrDefault("TC_ID", "");
            if (id.endsWith("_RL")) { g.other.add(r); continue; }
            if (isNeg(r)) {
                g.neg.add(r);
            } else {
                g.pos.add(r);
                if (g.api.isEmpty()) {
                    g.module = r.getOrDefault("Module", "");
                    g.api = r.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "").trim();
                    g.method = r.getOrDefault("Method", "");
                    g.endpoint = r.getOrDefault("Endpoint", "");
                }
            }
        }
        for (ApiGroup g : by.values()) {
            if (g.api.isEmpty() && !g.neg.isEmpty()) {          // negative-only run: no positive row in the report
                Map<String, String> f = g.neg.get(0);
                g.module = f.getOrDefault("Module", "");
                g.api = f.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "").trim();
                g.method = f.getOrDefault("Method", "");
                g.endpoint = f.getOrDefault("Endpoint", "");
            }
            for (Map<String, String> r : g.neg) {
                String res = r.getOrDefault("Result", "");
                if ("PASS".equalsIgnoreCase(res)) g.negPass++;
                else if ("FAIL".equalsIgnoreCase(res)) g.negFail++;
                else if ("N/A".equalsIgnoreCase(res)) g.negNa += w(r);
                else g.negSkip++;
            }
            boolean pf = false, pp = false, ps = false;
            for (Map<String, String> r : g.pos) {
                String res = r.getOrDefault("Result", "");
                if ("FAIL".equalsIgnoreCase(res)) pf = true;
                else if ("PASS".equalsIgnoreCase(res)) pp = true;
                else if (isSkip(res)) ps = true;
            }
            g.negResult = g.negFail > 0 ? "FAIL" : g.negPass > 0 ? "PASS" : g.negNa > 0 ? "NOT EXEC" : g.negSkip > 0 ? "SKIP" : "";
            g.posResult = pf ? "FAIL" : pp ? "PASS" : ps ? "SKIP" : g.pos.isEmpty() ? "" : "N/A";
            if ("FAIL".equals(g.posResult) || g.negFail > 0) g.result = "FAIL";
            else if ("PASS".equals(g.posResult)) g.result = "PASS";
            else if ("SKIP".equals(g.posResult)) g.result = "SKIP";
            else if (g.pos.isEmpty() && g.negPass > 0) g.result = "PASS";
            else g.result = "NOT EXEC";

            StringBuilder why = new StringBuilder();
            if ("FAIL".equals(g.posResult)) {
                Map<String, String> f = g.pos.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).findFirst().orElse(g.pos.get(0));
                why.append("Positive test failed: ").append(f.getOrDefault("Failure_Category", "")).append(" - ")
                        .append(cutText(f.getOrDefault("Failure_Reason", f.getOrDefault("Error", "")), 160));
            }
            if (g.negFail > 0) {
                if (why.length() > 0) why.append("  |  ");
                why.append(g.negFail).append(" negative test(s) failed: ");
                int n = 0;
                for (Map<String, String> r : g.neg) {
                    if (!"FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) continue;
                    if (n++ >= 3) { why.append(" ..."); break; }
                    if (n > 1) why.append(", ");
                    why.append(r.getOrDefault("TC_ID", "")).append(" (").append(r.getOrDefault("NegTitle", "")).append(")");
                }
            }
            if (why.length() == 0 && ("SKIP".equals(g.result) || "NOT EXEC".equals(g.result)) && !g.pos.isEmpty()) {
                Map<String, String> f = g.pos.get(0);
                why.append(cutText(f.getOrDefault("Failure_Reason", f.getOrDefault("Error", "")), 200));
            }
            g.reason = why.toString();
            if (g.negFail > 0) {
                StringBuilder nr = new StringBuilder(g.negFail + " negative test(s) failed: ");
                int k = 0;
                for (Map<String, String> r : g.neg) {
                    if (!"FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) continue;
                    if (k++ >= 3) { nr.append(" ..."); break; }
                    if (k > 1) nr.append(", ");
                    nr.append(r.getOrDefault("TC_ID", "")).append(" (").append(r.getOrDefault("NegTitle", "")).append(")");
                }
                g.negReason = nr.toString();
            } else if (g.negNa > 0 && g.negPass == 0 && !g.neg.isEmpty()) {
                g.negReason = cutText(g.neg.get(0).getOrDefault("Failure_Reason", ""), 200);
            }
            String hi = "–";
            for (Map<String, String> r : g.pos) hi = worse(hi, r);
            for (Map<String, String> r : g.neg) hi = worse(hi, r);
            g.severity = hi;
        }
        int seq = 0;
        for (ApiGroup g : by.values()) g.no = ++seq;
        return new ArrayList<>(by.values());
    }

    /** API001, API002 ... (grows past 999 on its own: API1000, API10000) */
    static String apiId(int no) { return String.format("API%03d", no); }

    /** TC001-01 = test 1 of API001 (grows on its own) */
    static String childId(int api, int seq) { return String.format("TC%03d-%02d", api, seq); }

    private static String worse(String cur, Map<String, String> r) {
        if (!"FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) return cur;
        String s = r.getOrDefault("Severity", "");
        int a = SEV_ORDER.indexOf(s), b = SEV_ORDER.indexOf(cur);
        if (a < 0 || a >= SEV_ORDER.size() - 1) return cur;
        return (b < 0 || b >= SEV_ORDER.size() - 1 || a < b) ? s : cur;
    }

    private static String cutText(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static final String[] AR_HDR = {"ID", "API / Test", "Type", "Result", "Severity", "HTTP got", "HTTP expected", "Module", "Method", "Endpoint",
            "What was sent / changed", "Expected", "Actual", "Why it failed", "Request", "Response", "Full response", "Executed at", "Original test ID"};
    private static final int[] AR_W = {13, 46, 11, 12, 11, 9, 17, 20, 10, 46, 42, 40, 40, 50, 45, 60, 14, 20, 16};

    private static void buildApiResultsSheet(XSSFWorkbook wb, StyleKit sk, List<ApiGroup> groups, ReportMeta meta, String timestamp, boolean negOnly) {
        XSSFSheet sh = wb.createSheet(negOnly ? "Negative Results" : "API Results");
        sh.setRowSumsBelow(false);
        for (int i = 0; i < AR_W.length; i++) sh.setColumnWidth(i, chars(AR_W[i]));
        int last = AR_HDR.length - 1;
        Row t = sh.createRow(0);
        t.setHeightInPoints(36);
        mergedCell(sh, t, 0, last, negOnly ? "\u2716  NEGATIVE RESULTS  -  one row per API: PASS only if ALL its negative tests pass; click  +  to see each negative test"
                : "\uD83D\uDCCB  API RESULTS  -  one row per API: PASS only if the positive test AND all negative tests pass; click  +  to see them", sk.banner(negOnly ? C_PURPLE : C_DARK_BLUE, 14));
        Row st = sh.createRow(1);
        st.setHeightInPoints(20);
        mergedCell(sh, st, 0, last, "Generated: " + timestamp + "   |   Tested by: " + meta.testedBy + "   |   Base URL: " + meta.baseUrl + "   |   Env: " + meta.env
                + "   |   An API PASSES only if its positive test and every negative test pass; one failed negative fails the API.", sk.subtitle());
        Row hr = sh.createRow(3);
        hr.setHeightInPoints(28);
        for (int i = 0; i < AR_HDR.length; i++) {
            Cell c = hr.createCell(i);
            c.setCellValue(AR_HDR[i]);
            c.setCellStyle(sk.tableHeader());
        }
        sh.createFreezePane(2, 4);
        sh.setAutoFilter(new CellRangeAddress(3, 3, 0, last));

        int rn = 4;
        List<int[]> ranges = new ArrayList<>();
        for (ApiGroup g : groups) {
            if (negOnly && g.neg.isEmpty()) continue;
            String pres = negOnly ? g.negResult : g.result;
            // ---- parent row (the API) ----
            Row p = sh.createRow(rn++);
            p.setHeightInPoints(30);
            String bg = C_LIGHT_BLUE;
            put(p, 0, apiId(g.no), sk.statusCell(bg, C_DARK_BLUE));
            put(p, 1, g.api, sk.dataLeft(bg));
            put(p, 2, "API", sk.dataCenter(bg));
            put(p, 3, pres, sk.resultBadge("PASS".equals(pres) ? C_GREEN : "FAIL".equals(pres) ? C_RED : "SKIP".equals(pres) ? C_AMBER : C_GREY));
            String[] sc = severityColors(g.severity);
            put(p, 4, "–".equals(g.severity) ? "" : g.severity, sc == null ? sk.dataCenter(bg) : sk.statusCell(sc[0], sc[1]));
            put(p, 5, "", sk.dataCenter(bg));
            put(p, 6, "", sk.dataCenter(bg));
            put(p, 7, g.module, sk.dataLeft(bg));
            put(p, 8, g.method, sk.methodBadge(methodColor(g.method)));
            put(p, 9, g.endpoint, sk.dataLeft(bg));
            String sum = (negOnly || g.posResult.isEmpty() ? "" : "Positive " + g.posResult + "   ·   ")
                    + "Negatives " + g.negPass + " of " + (g.negPass + g.negFail + g.negSkip) + " passed"
                    + (g.negNa > 0 ? ", " + g.negNa + " not executable" : "");
            put(p, 10, sum, sk.dataLeft(bg));
            put(p, 11, "", sk.dataLeft(bg));
            put(p, 12, "", sk.dataLeft(bg));
            put(p, 13, negOnly ? g.negReason : g.reason, sk.dataLeft(bg));
            for (int c = 14; c <= last; c++) put(p, c, "", sk.dataLeft(bg));
            put(p, last, g.tc, sk.dataCenter(bg));

            // ---- child rows: positive first, then the negatives ----
            List<Map<String, String>> kids = new ArrayList<>();
            if (!negOnly) kids.addAll(g.pos);
            kids.addAll(g.neg);
            if (!negOnly) kids.addAll(g.other);
            int first = rn;
            int idx = 0;
            List<Map<String, String>> full = new ArrayList<>(g.pos);
            full.addAll(g.neg);
            full.addAll(g.other);
            for (Map<String, String> r : kids) {
                Row row = sh.createRow(rn++);
                row.setHeightInPoints(40);
                String cbg = (idx++ % 2 == 1) ? C_STRIPE : C_WHITE;
                boolean negative = isNeg(r);
                boolean relogin = r.getOrDefault("TC_ID", "").endsWith("_RL");
                String res = r.getOrDefault("Result", "");
                String title = relogin ? "Session restored (login again)"
                        : negative ? r.getOrDefault("NegTitle", "negative") : "Valid request";
                put(row, 0, childId(g.no, full.indexOf(r) + 1), sk.dataCenter(cbg));
                put(row, last, r.getOrDefault("TC_ID", ""), sk.dataCenter(cbg));
                put(row, 1, "      ↳  " + title, sk.dataLeft(cbg));
                put(row, 2, relogin ? "Re-login" : negative ? "Negative" : "Positive", sk.dataCenter(cbg));
                put(row, 3, "N/A".equalsIgnoreCase(res) ? "NOT EXEC" : res, sk.resultBadge("PASS".equalsIgnoreCase(res) ? C_GREEN : "FAIL".equalsIgnoreCase(res) ? C_RED
                        : "N/A".equalsIgnoreCase(res) ? C_GREY : C_AMBER));
                String sev = "FAIL".equalsIgnoreCase(res) ? r.getOrDefault("Severity", "") : "";
                String[] csc = severityColors(sev);
                put(row, 4, sev, csc == null ? sk.dataCenter(cbg) : sk.statusCell(csc[0], csc[1]));
                String http = r.getOrDefault("HTTP", "");
                put(row, 5, "0".equals(http) ? "" : http, sk.dataCenter(cbg));
                String chk = r.getOrDefault("HTTP_Check", "");
                put(row, 6, r.getOrDefault("Expected_HTTP", ""), "MISMATCH".equals(chk) ? sk.statusCell(C_AMBER_LT, C_AMBER) : sk.dataCenter(cbg));
                put(row, 7, "", sk.dataCenter(cbg));
                put(row, 8, "", sk.dataCenter(cbg));
                put(row, 9, "", sk.dataCenter(cbg));
                String td = r.getOrDefault("TestData", "");
                put(row, 10, "NA".equals(td) ? "" : fitCell(td), sk.dataLeft(cbg));
                put(row, 11, fitCell(r.getOrDefault("ExpectedResult", "")), sk.dataLeft(cbg));
                put(row, 12, fitCell(r.getOrDefault("ActualResult", "")), sk.dataLeft(cbg));
                boolean bad = "FAIL".equalsIgnoreCase(res) || isSkip(res) || "N/A".equalsIgnoreCase(res);
                put(row, 13, bad ? fitCell(r.getOrDefault("Failure_Reason", "")) : "", sk.dataLeft(cbg));
                put(row, 14, fitCell(r.getOrDefault("RequestBody", "")), sk.responseCell(idx % 2 == 0));
                put(row, 15, fitCell(r.getOrDefault(RESPONSE_KEY, "")), sk.responseCell(idx % 2 == 0));
                String link = r.getOrDefault("ResponseFile", "");
                Cell lc = row.createCell(16);
                if (!link.isEmpty()) {
                    lc.setCellValue("Open ↗");
                    XSSFHyperlink hl = wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.FILE);
                    hl.setAddress(link.replace(" ", "%20"));
                    lc.setHyperlink(hl);
                    lc.setCellStyle(sk.linkCell(cbg));
                } else {
                    lc.setCellValue("");
                    lc.setCellStyle(sk.dataCenter(cbg));
                }
                put(row, 17, r.getOrDefault("Executed_At", ""), sk.dataCenter(cbg));
            }
            if (rn - 1 >= first) ranges.add(new int[]{first, rn - 1});
        }
        for (int[] g : ranges) {
            sh.groupRow(g[0], g[1]);
        }
        for (int[] g : ranges) {
            sh.setRowGroupCollapsed(g[0], true);     // children closed: only the API rows show, + opens them
        }
    }

    private static void put(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  VAPT FINDINGS  -  one parent row per VAPT point, every API tested as a collapsible child.
    //  The full VAPT detail (impact, expected vs actual, evidence) is in the VAPT PDF next to this file.
    // ════════════════════════════════════════════════════════════════════════
    private static void buildVaptSheets(XSSFWorkbook wb, StyleKit sk, List<Map<String, String>> data, ReportMeta meta, String timestamp) {
        List<com.api.automation.report.VaptModel.Point> pts = com.api.automation.report.VaptModel.build(data);
        XSSFSheet fnd = wb.createSheet("VAPT Findings");
        int[] fw = {11, 11, 42, 11, 11, 52, 34, 18, 44, 46, 44};
        String[] fh = {"VAPT ID", "Test", "VAPT point / API", "Result", "Severity", "Standard / Affected URL", "Expected", "Actual HTTP", "Actual result", "Why it failed / how it affects", "Fix needed"};
        for (int i = 0; i < fw.length; i++) fnd.setColumnWidth(i, chars(fw[i]));
        fnd.setRowSumsBelow(false);
        int last = fh.length - 1;
        Row ft = fnd.createRow(0);
        ft.setHeightInPoints(34);
        mergedCell(fnd, ft, 0, last, "🔒  VAPT FINDINGS  -  one row per VAPT point (FAIL if any API failed it); click  +  to see every API tested", sk.banner(C_CRIT, 14));
        Row fs = fnd.createRow(1);
        fs.setHeightInPoints(20);
        mergedCell(fnd, fs, 0, last, "Run: " + timestamp + "   |   Tested by: " + meta.testedBy + "   |   Base URL: " + meta.baseUrl + "   |   Env: " + meta.env, sk.subtitle());
        Row pr0 = fnd.createRow(2);
        pr0.setHeightInPoints(20);
        String pdfLine = (meta.vaptPdfName == null || meta.vaptPdfName.isEmpty())
                ? "Full VAPT details (impact, expected vs actual, evidence) are in the VAPT PDF report."
                : "Full VAPT details are in the PDF reports in this folder:   VAPT report  →  " + meta.vaptPdfName + "     |     Failures only  →  " + meta.failurePdfName;
        mergedCell(fnd, pr0, 0, last, pdfLine, sk.footerCell());
        Row fhr = fnd.createRow(3);
        fhr.setHeightInPoints(28);
        for (int i = 0; i < fh.length; i++) {
            Cell c = fhr.createCell(i);
            c.setCellValue(fh[i]);
            c.setCellStyle(sk.tableHeader());
        }
        fnd.createFreezePane(2, 4);

        int fr = 4;
        List<int[]> vranges = new ArrayList<>();
        for (com.api.automation.report.VaptModel.Point p : pts) {
            if ("NA".equals(p.status)) continue;
            Row pr = fnd.createRow(fr++);
            pr.setHeightInPoints(30);
            String pbg = C_LIGHT_BLUE;
            put(pr, 0, p.id, sk.statusCell(pbg, C_DARK_BLUE));
            put(pr, 1, "", sk.dataCenter(pbg));
            put(pr, 2, p.title, sk.dataLeft(pbg));
            put(pr, 3, p.status, sk.resultBadge("PASS".equals(p.status) ? C_GREEN : "FAIL".equals(p.status) ? C_RED : C_AMBER));
            String[] psc = severityColors(p.severity);
            put(pr, 4, "–".equals(p.severity) ? "" : p.severity, psc == null ? sk.dataCenter(pbg) : sk.statusCell(psc[0], psc[1]));
            put(pr, 5, p.owasp.isEmpty() ? "" : p.owasp, sk.dataLeft(pbg));
            put(pr, 6, p.expected, sk.dataLeft(pbg));
            put(pr, 7, httpMix(p), sk.dataCenter(pbg));
            put(pr, 8, p.endpointsTested.size() + " API(s) tested: " + p.passed + " passed, " + p.failed + " failed" + (p.skipped > 0 ? ", " + p.skipped + " skipped" : ""), sk.dataLeft(pbg));
            put(pr, 9, p.impact, sk.dataLeft(pbg));
            put(pr, 10, p.fix, sk.dataLeft(pbg));
            int firstChild = fr, n = 0;
            List<Map<String, String>> kids = new ArrayList<>(p.failedRows);
            kids.addAll(p.otherRows);
            kids.addAll(p.passedRows);
            for (Map<String, String> r : kids) {
                if (fr > 12000 || n++ >= 450) break;
                boolean bad = p.failedRows.contains(r);
                boolean other = !bad && p.otherRows.contains(r);
                Row row = fnd.createRow(fr++);
                row.setHeightInPoints(34);
                String bg = n % 2 == 0 ? C_STRIPE : C_WHITE;
                put(row, 0, "", sk.dataCenter(bg));
                put(row, 1, r.getOrDefault("TC_ID", ""), sk.dataCenter(bg));
                put(row, 2, "      ↳  " + r.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", ""), sk.dataLeft(bg));
                String shown = bad ? "FAIL" : other ? ("NA".equals(r.get("VAPT_Class")) ? "N/A" : "INCONCLUSIVE") : "PASS";
                put(row, 3, shown, sk.resultBadge(bad ? C_RED : other ? C_AMBER : C_GREEN));
                String sev = bad ? com.api.automation.report.VaptModel.rowSeverity(r, p.key) : "";
                String[] sc = severityColors(sev);
                put(row, 4, sev, sc == null ? sk.dataCenter(bg) : sk.statusCell(sc[0], sc[1]));
                put(row, 5, r.getOrDefault("Method", "") + "  " + r.getOrDefault("Endpoint", ""), sk.dataLeft(bg));
                put(row, 6, fitCell(r.getOrDefault("ExpectedResult", p.expected)), sk.dataLeft(bg));
                String http = r.getOrDefault("HTTP", "");
                put(row, 7, "0".equals(http) ? "" : http, sk.dataCenter(bg));
                put(row, 8, fitCell(r.getOrDefault("ActualResult", "")), sk.dataLeft(bg));
                String leak = r.getOrDefault("Leak_Evidence", "");
                String why = bad ? ("INTERNAL_LEAK".equals(p.key) && !leak.isEmpty() ? "Leaked: " + leak : r.getOrDefault("Failure_Reason", p.impact))
                        : other ? r.getOrDefault("Error", "") : "";
                put(row, 9, fitCell(why), sk.dataLeft(bg));
                put(row, 10, bad ? fitCell("INTERNAL_LEAK".equals(p.key) ? p.fix : r.getOrDefault("Action_Needed", p.fix)) : "", sk.dataLeft(bg));
            }
            if (fr - 1 >= firstChild) vranges.add(new int[]{firstChild, fr - 1});
        }
        for (int[] g : vranges) fnd.groupRow(g[0], g[1]);
        for (int[] g : vranges) fnd.setRowGroupCollapsed(g[0], true);
        if (fr == 4) {
            Row row = fnd.createRow(fr++);
            mergedCell(fnd, row, 0, last, "No VAPT point was tested in this run.", sk.subtitle());
        }

        // functional (QA) issues found by the negatives: listed separately, never counted as VAPT
        List<Map<String, String>> func = com.api.automation.report.VaptModel.functional(data);
        if (!func.isEmpty()) {
            fr++;
            Row t = fnd.createRow(fr++);
            t.setHeightInPoints(26);
            mergedCell(fnd, t, 0, last, "FUNCTIONAL VALIDATION (QA)  -  " + func.size() + " test(s): business validation gaps with no security impact, NOT counted as VAPT",
                    sk.banner(C_AMBER, 12));
            int k = 0;
            for (Map<String, String> r : func) {
                if (fr > 14000) break;
                Row row = fnd.createRow(fr++);
                row.setHeightInPoints(30);
                String bg = ++k % 2 == 0 ? C_STRIPE : C_WHITE;
                put(row, 0, "QA", sk.dataCenter(bg));
                put(row, 1, r.getOrDefault("TC_ID", ""), sk.dataCenter(bg));
                put(row, 2, r.getOrDefault("API_Name", "").replaceAll("\\s*\\|\\s*(NEG|CHECK).*$", "") + "  |  " + r.getOrDefault("NegTitle", ""), sk.dataLeft(bg));
                put(row, 3, "FUNCTIONAL", sk.resultBadge(C_AMBER));
                put(row, 4, "", sk.dataCenter(bg));
                put(row, 5, r.getOrDefault("Method", "") + "  " + r.getOrDefault("Endpoint", ""), sk.dataLeft(bg));
                put(row, 6, fitCell(r.getOrDefault("ExpectedResult", "")), sk.dataLeft(bg));
                put(row, 7, r.getOrDefault("HTTP", ""), sk.dataCenter(bg));
                put(row, 8, fitCell(r.getOrDefault("ActualResult", "")), sk.dataLeft(bg));
                put(row, 9, fitCell(r.getOrDefault("Error", "")), sk.dataLeft(bg));
                put(row, 10, "QA / developer: add the business validation (400 with a clear message)", sk.dataLeft(bg));
            }
        }
    }

    /** "401 x61, 200 x4" - the HTTP codes the APIs returned for this VAPT point */
    private static String httpMix(com.api.automation.report.VaptModel.Point p) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map<String, String> r : p.failedRows) m.merge(r.getOrDefault("HTTP", "?"), 1, Integer::sum);
        for (Map<String, String> r : p.passedRows) m.merge(r.getOrDefault("HTTP", "?"), 1, Integer::sum);
        m.remove("0");
        m.remove("");
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (Map.Entry<String, Integer> e : es) {
            if (k++ >= 4) { sb.append(" ..."); break; }
            sb.append(k > 1 ? ",  " : "").append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FAILURE ANALYSIS - every FAIL / SKIP with a plain-English reason
    // ════════════════════════════════════════════════════════════════════════
    private static void buildFailureAnalysis(XSSFWorkbook wb, StyleKit sk, List<Map<String, String>> data, ReportMeta meta) {
        XSSFSheet sh = wb.createSheet("Failure Analysis");
        String[] keys = {"TC_ID", "Type", "Module", "API_Name", "Result", "Severity", "Defect_Status", "HTTP", "Failure_Category",
                "Failure_Reason", "Action_Needed", "Error", "ResponseFile"};
        String[] labels = {"TC ID", "Type", "Module", "API Name", "Result", "Severity", "Status", "HTTP", "Failure Category",
                "Why it failed (plain English)", "What is needed / who", "Server / framework message", "Full Response"};
        int[] widths = {11, 10, 22, 38, 9, 10, 17, 7, 26, 70, 55, 55, 14};
        for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, chars(widths[i]));

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> r : data) {
            String res = r.getOrDefault("Result", "");
            if ("FAIL".equalsIgnoreCase(res) || isSkip(res)) rows.add(r);
        }

        Row t = sh.createRow(0); t.setHeightInPoints(34);
        mergedCell(sh, t, 0, keys.length - 1, "Failure Analysis  -  why each API failed or was skipped, and what is needed", sk.banner(C_RED, 15));
        Row s = sh.createRow(1); s.setHeightInPoints(20);
        long f = rows.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
        mergedCell(sh, s, 0, keys.length - 1, "FAIL: " + f + "    |    SKIP: " + (rows.size() - f)
                + "    |    Use the filter on 'Failure Category' to group similar problems", sk.subtitle());

        Row h = sh.createRow(2); h.setHeightInPoints(30);
        for (int i = 0; i < labels.length; i++) {
            Cell c = h.createCell(i); c.setCellValue(labels[i]); c.setCellStyle(sk.tableHeader());
        }
        int rn = 3;
        for (Map<String, String> r : rows) {
            Row row = sh.createRow(rn++);
            boolean skip = isSkip(r.getOrDefault("Result", ""));
            String bg = skip ? C_AMBER_LT : C_WHITE;
            for (int i = 0; i < keys.length; i++) {
                String val = "Type".equals(keys[i]) ? (isNeg(r) ? "Negative" : "Positive") : r.getOrDefault(keys[i], "");
                if ("API_Name".equals(keys[i]) && isNeg(r)) val = val.replace(" | NEG ", "  \u2192  ");
                Cell c = row.createCell(i);
                String[] sc = "Severity".equals(keys[i]) ? severityColors(val)
                        : "Defect_Status".equals(keys[i]) ? statusColors(val) : null;
                if (sc != null) {
                    c.setCellValue(val);
                    c.setCellStyle(sk.statusCell(sc[0], sc[1]));
                } else if ("ResponseFile".equals(keys[i]) && !val.isEmpty()) {
                    c.setCellValue("Open \u2197");
                    XSSFHyperlink link = wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.FILE);
                    link.setAddress(val.replace(" ", "%20"));
                    c.setHyperlink(link);
                    c.setCellStyle(sk.linkCell(bg));
                } else if ("Result".equals(keys[i])) {
                    c.setCellValue(val);
                    c.setCellStyle(sk.resultBadge(skip ? C_AMBER : C_RED));
                } else {
                    c.setCellValue(fitCell(val));
                    c.setCellStyle(i >= 8 ? sk.dataLeft(bg) : sk.dataCenter(bg));
                }
            }
        }
        if (rows.isEmpty()) {
            Row row = sh.createRow(rn);
            mergedCell(sh, row, 0, keys.length - 1, "No failures - every API passed.", sk.statusCell(C_GREEN_LT, C_GREEN));
        } else {
            sh.setAutoFilter(new CellRangeAddress(2, rn - 1, 0, keys.length - 1));
        }
        sh.createFreezePane(3, 3);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MODULE SUMMARY - pass / fail / skip per module
    // ════════════════════════════════════════════════════════════════════════
    private static void buildModuleSummary(XSSFWorkbook wb, StyleKit sk, List<Map<String, String>> data) {
        XSSFSheet sh = wb.createSheet("Module Summary");
        String[] labels = {"Module", "Total", "Passed", "Failed", "Skipped", "Pass %",
                "Positive (P/F/S)", "Negative (P/F)", "Main failure categories"};
        int[] widths = {34, 9, 9, 9, 9, 9, 16, 15, 80};
        for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, chars(widths[i]));
        Row t = sh.createRow(0); t.setHeightInPoints(34);
        mergedCell(sh, t, 0, labels.length - 1, "Module Summary", sk.banner(C_DARK_BLUE, 15));
        Row h = sh.createRow(1); h.setHeightInPoints(26);
        for (int i = 0; i < labels.length; i++) {
            Cell c = h.createCell(i); c.setCellValue(labels[i]); c.setCellStyle(sk.tableHeader());
        }
        Map<String, List<Map<String, String>>> byModule = new LinkedHashMap<>();
        for (Map<String, String> r : data) {
            String m = r.getOrDefault("Module", "");
            byModule.computeIfAbsent(m.isEmpty() ? "(no module)" : m, k -> new ArrayList<>()).add(r);
        }
        int rn = 2;
        for (Map.Entry<String, List<Map<String, String>>> e : byModule.entrySet()) {
            List<Map<String, String>> rs = e.getValue();
            long p = rs.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            long f = rs.stream().filter(r -> "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            long s = rs.stream().filter(r -> isSkip(r.getOrDefault("Result", ""))).count();
            Map<String, Long> cats = new LinkedHashMap<>();
            for (Map<String, String> r : rs) {
                String c = r.getOrDefault("Failure_Category", "");
                if (!c.isEmpty() && "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))) cats.merge(c, 1L, Long::sum);
            }
            StringBuilder cs = new StringBuilder();
            cats.forEach((k, v) -> cs.append(cs.length() == 0 ? "" : ",  ").append(k).append(" x").append(v));
            double rate = (p + f) == 0 ? 100 : p * 100.0 / (p + f);
            String bg = f == 0 ? C_GREEN_LT : rate >= 50 ? C_AMBER_LT : C_RED_LT;
            String fg = f == 0 ? C_GREEN : rate >= 50 ? C_AMBER : C_RED;
            Row row = sh.createRow(rn++);
            long pp = rs.stream().filter(r -> !"Y".equals(r.get("Negative")) && "PASS".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            long pf = rs.stream().filter(r -> !"Y".equals(r.get("Negative")) && "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            long ps = rs.stream().filter(r -> !"Y".equals(r.get("Negative")) && isSkip(r.getOrDefault("Result", ""))).count();
            long np = rs.stream().filter(r -> "Y".equals(r.get("Negative")) && "PASS".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            long nf = rs.stream().filter(r -> "Y".equals(r.get("Negative")) && "FAIL".equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
            Object[] vals = {e.getKey(), rs.size(), p, f, s, String.format("%.0f%%", rate),
                    pp + " / " + pf + " / " + ps, np + " / " + nf, cs.toString()};
            for (int i = 0; i < vals.length; i++) {
                Cell c = row.createCell(i);
                if (vals[i] instanceof Number) c.setCellValue(((Number) vals[i]).doubleValue());
                else c.setCellValue(String.valueOf(vals[i]));
                c.setCellStyle(i == 5 ? sk.statusCell(bg, fg) : (i == 0 || i == 8 ? sk.dataLeft(C_WHITE) : sk.dataCenter(C_WHITE)));
            }
        }
        sh.createFreezePane(1, 2);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  StyleKit
    // ════════════════════════════════════════════════════════════════════════
    private static class StyleKit {
        private final XSSFWorkbook wb;
        StyleKit(XSSFWorkbook wb) { this.wb = wb; }

        /*
         * Excel allows at most 64,000 cell styles per file. Every style is created ONCE per distinct look and
         * reused - a full run (1,700+ tests, 9 sheets) needs only a few hundred.
         */
        private final java.util.Map<String, XSSFCellStyle> cache = new java.util.HashMap<>();
        private final java.util.Map<String, XSSFFont> fonts = new java.util.HashMap<>();

        XSSFCellStyle banner(String bg, int size) {
            return cache.computeIfAbsent("banner" + "|" + bg + "|" + size, k -> make_banner(bg, size));
        }
        XSSFCellStyle subtitle() {
            return cache.computeIfAbsent("subtitle", k -> make_subtitle());
        }
        XSSFCellStyle tableHeader() {
            return cache.computeIfAbsent("tableHeader", k -> make_tableHeader());
        }
        XSSFCellStyle kpiLabel(String bg) {
            return cache.computeIfAbsent("kpiLabel" + "|" + bg, k -> make_kpiLabel(bg));
        }
        XSSFCellStyle kpiValue(String bg, String txtColor) {
            return cache.computeIfAbsent("kpiValue" + "|" + bg + "|" + txtColor, k -> make_kpiValue(bg, txtColor));
        }
        XSSFCellStyle dataLeft(String bg) {
            return cache.computeIfAbsent("dataLeft" + "|" + bg, k -> make_dataLeft(bg));
        }
        XSSFCellStyle linkCell(String bg) {
            return cache.computeIfAbsent("linkCell" + "|" + bg, k -> make_linkCell(bg));
        }
        XSSFCellStyle kpiValueSmall() {
            return cache.computeIfAbsent("kpiValueSmall", k -> make_kpiValueSmall());
        }
        XSSFCellStyle sectionHeader() {
            return cache.computeIfAbsent("sectionHeader", k -> make_sectionHeader());
        }
        XSSFCellStyle dataCenter(String bg) {
            return cache.computeIfAbsent("dataCenter" + "|" + bg, k -> make_dataCenter(bg));
        }
        XSSFCellStyle methodBadge(String bg) {
            return cache.computeIfAbsent("methodBadge" + "|" + bg, k -> make_methodBadge(bg));
        }
        XSSFCellStyle resultBadge(String bg) {
            return cache.computeIfAbsent("resultBadge" + "|" + bg, k -> make_resultBadge(bg));
        }
        XSSFCellStyle statusCell(String bg, String txtColor) {
            return cache.computeIfAbsent("statusCell" + "|" + bg + "|" + txtColor, k -> make_statusCell(bg, txtColor));
        }
        XSSFCellStyle responseCell(boolean stripe) {
            return cache.computeIfAbsent("responseCell" + "|" + stripe, k -> make_responseCell(stripe));
        }
        XSSFCellStyle footerCell() {
            return cache.computeIfAbsent("footerCell", k -> make_footerCell());
        }

        private XSSFFont font(String hex, int size, boolean bold, boolean italic) {
            return fonts.computeIfAbsent(hex + "|" + size + "|" + bold + "|" + italic, k -> makeFont(hex, size, bold, italic));
        }

        private XSSFFont makeFont(String hex, int size, boolean bold, boolean italic) {
            XSSFFont f = wb.createFont();
            f.setColor(new XSSFColor(hexToBytes(hex), null));
            f.setFontHeightInPoints((short) size);
            f.setBold(bold); f.setItalic(italic);
            f.setFontName("Arial");
            return f;
        }
        private void applyFill(XSSFCellStyle cs, String hex) {
            cs.setFillForegroundColor(new XSSFColor(hexToBytes(hex), null));
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        private void applyBorder(XSSFCellStyle cs, String hex) {
            XSSFColor c = new XSSFColor(hexToBytes(hex), null);
            cs.setBorderTop(BorderStyle.THIN);    cs.setTopBorderColor(c);
            cs.setBorderBottom(BorderStyle.THIN); cs.setBottomBorderColor(c);
            cs.setBorderLeft(BorderStyle.THIN);   cs.setLeftBorderColor(c);
            cs.setBorderRight(BorderStyle.THIN);  cs.setRightBorderColor(c);
        }

        private XSSFCellStyle make_banner(String bg, int size) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(C_WHITE, size, true, false));
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            return cs;
        }

        // Subtitle: darker blue so white text is clearly readable
        private XSSFCellStyle make_subtitle() {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, "1A3E6E");
            cs.setFont(font("C8DCF5", 9, false, false));
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            return cs;
        }

        // Table header: deep vivid blue + bold white — clearly readable
        private XSSFCellStyle make_tableHeader() {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, C_HDR_BG);
            cs.setFont(font(C_WHITE, 10, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            cs.setWrapText(true);
            applyBorder(cs, C_DARK_BLUE);
            return cs;
        }

        private XSSFCellStyle make_kpiLabel(String bg) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(C_WHITE, 9, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_kpiValue(String bg, String txtColor) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(txtColor, 18, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_dataLeft(String bg) {
            XSSFCellStyle cs = make_dataCenter(bg);
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.TOP);
            return cs;
        }

        private XSSFCellStyle make_linkCell(String bg) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            XSSFFont f = font(C_MID_BLUE, 9, true, false);
            f.setUnderline(org.apache.poi.ss.usermodel.Font.U_SINGLE);
            cs.setFont(f);
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_kpiValueSmall() {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, C_LIGHT_BLUE);
            cs.setFont(font(C_DARK_BLUE, 11, true, false));
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_sectionHeader() {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, C_LIGHT_BLUE);
            cs.setFont(font(C_DARK_BLUE, 10, true, false));
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            cs.setBorderBottom(BorderStyle.MEDIUM);
            cs.setBottomBorderColor(new XSSFColor(hexToBytes(C_MID_BLUE), null));
            return cs;
        }

        private XSSFCellStyle make_dataCenter(String bg) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(C_DARK_TXT, 9, false, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            cs.setWrapText(true);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_methodBadge(String bg) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(C_WHITE, 9, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_resultBadge(String bg) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(C_WHITE, 9, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_statusCell(String bg, String txtColor) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, bg);
            cs.setFont(font(txtColor, 9, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_responseCell(boolean stripe) {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, stripe ? C_STRIPE : C_WHITE);
            XSSFFont f = wb.createFont();
            f.setFontName("Courier New");
            f.setFontHeightInPoints((short) 8);
            f.setColor(new XSSFColor(hexToBytes("2E4057"), null));
            cs.setFont(f);
            cs.setAlignment(HorizontalAlignment.LEFT);
            cs.setVerticalAlignment(VerticalAlignment.TOP);
            cs.setWrapText(true);
            applyBorder(cs, C_BORDER);
            return cs;
        }

        private XSSFCellStyle make_footerCell() {
            XSSFCellStyle cs = wb.createCellStyle();
            applyFill(cs, C_DARK_BLUE);
            cs.setFont(font(C_WHITE, 10, true, false));
            cs.setAlignment(HorizontalAlignment.CENTER);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            return cs;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════
    private static void mergedCell(Sheet sh, Row row, int c1, int c2, String val, CellStyle cs) {
        Cell cell = row.createCell(c1);
        cell.setCellValue(val);
        cell.setCellStyle(cs);
        if (c2 > c1) sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), c1, c2));
        for (int i = c1 + 1; i <= c2; i++) { Cell x = row.createCell(i); x.setCellStyle(cs); }
    }

    private static void setTabColor(XSSFWorkbook wb, int idx, String hex) {
        ((XSSFSheet) wb.getSheetAt(idx)).setTabColor(new XSSFColor(hexToBytes(hex), null));
    }

    private static double parseMs(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        raw = raw.trim().toLowerCase();
        try {
            if (raw.endsWith("ms")) return Double.parseDouble(raw.replace("ms","").trim());
            if (raw.endsWith("s"))  return Double.parseDouble(raw.replace("s","").trim()) * 1000;
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) { return 0; }
    }

    private static String formatMs(double ms) {
        if (ms < 1000) return String.format("%.0f ms", ms);
        return String.format("%.2f s  (%.0f ms)", ms / 1000, ms);
    }

    /** 1h 02m 05s / 3m 12s / 42s */
    public static String formatDuration(long ms) {
        long totalSec = Math.round(ms / 1000.0);
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return s + "s";
    }

    // ── Severity / status ────────────────────────────────────────────────
    private static final String[] SEVERITIES = {"Critical", "High", "Medium", "Low", "Info", "\u2013"};
    private static final String[] STATUS_HDR = {"Severity", "Open - Bug", "Open - Environment", "Open - Test Data",
            "Blocked", "Not Applicable", "Closed", "Total", "Total Open"};
    private static final java.util.Set<String> ENV_CATS = java.util.Set.of("ENDPOINT NOT DEPLOYED", "DATABASE ERROR",
            "FEATURE DISABLED", "CONNECTIVITY / FRAMEWORK", "NO PERMISSION", "OTP NOT ENTERED");
    private static final java.util.Set<String> DATA_CATS = java.util.Set.of("NO DATA FROM PREVIOUS STEP", "DATA NOT FOUND",
            "RECORD IN WRONG STATE", "INPUT MISSING");
    private static final java.util.Set<String> CRITICAL_NEG = java.util.Set.of("NO_SESSION", "OTHER_USER",
            "TAMPERED_ENCRYPTION", "WRONG_PASSWORD", "VERIFY_LOGIN", "TAMPERED_TOKEN", "TAMPERED_CSRF", "XXE");
    private static final java.util.Set<String> HIGH_NEG = java.util.Set.of("INJECTION", "XSS_SCRIPT",
            "DOB_TODAY", "DOB_FUTURE", "DOB_INVALID", "DOB_TOO_OLD");
    private static final java.util.Set<String> LOW_NEG = java.util.Set.of("SPECIAL_CHARS", "SPACES_ONLY", "UNICODE", "BOUNDARY");

    static boolean isNeg(Map<String, String> r) {
        return "Y".equals(r.get("Negative"));
    }

    /** TC047_03 -> TC047 */
    private static String baseTc(Map<String, String> r) {
        return r.getOrDefault("TC_ID", "").replaceAll("_\\d+$", "");
    }

    /** {severity, status} for one result row. */
    static String[] defect(Map<String, String> r) {
        String res = r.getOrDefault("Result", "");
        String cat = r.getOrDefault("Failure_Category", "");
        if ("PASS".equalsIgnoreCase(res)) return new String[]{"\u2013", "Closed"};
        if ("N/A".equalsIgnoreCase(res)) return new String[]{"\u2013", "Not Applicable"};
        if (isSkip(res)) {
            return cat.startsWith("NOT NEEDED") || "NA".equals(r.get("VAPT_Class")) ? new String[]{"\u2013", "Not Applicable"} : new String[]{"Info", "Blocked"};
        }
        // severity of negatives = proven impact (negative.VaptRules); functional (QA) defects carry no VAPT severity
        if ("FUNCTIONAL".equals(r.get("VAPT_Class"))) return new String[]{"Info", "Open - Bug"};
        String proven = r.getOrDefault("VAPT_Severity", "");
        if (!proven.isEmpty()) return new String[]{proven, "Open - Bug"};
        if ("STATE_CHECK".equals(r.get("NegCategory"))) return new String[]{"High", "Open - Bug"};
        String status = ENV_CATS.contains(cat) ? "Open - Environment" : DATA_CATS.contains(cat) ? "Open - Test Data" : "Open - Bug";
        String sev;
        if (isNeg(r)) {
            String nc = r.getOrDefault("NegCategory", "");
            boolean reflectedOnly = "XSS_SCRIPT".equals(nc) && r.getOrDefault("HTTP", "").startsWith("4");
            if (CRITICAL_NEG.contains(nc)) sev = "Critical";
            else if (cat.startsWith("INTERNAL ERROR") || cat.startsWith("SERVER ERROR")) sev = "High";
            else if (reflectedOnly || LOW_NEG.contains(nc)) sev = "Low";
            else if (HIGH_NEG.contains(nc)) sev = "High";
            else sev = "Medium";
        } else {
            String api = r.getOrDefault("API_Name", "").toLowerCase();
            if (api.contains("login") || cat.startsWith("SESSION")) sev = "Critical";
            else if (cat.startsWith("SERVER ERROR") || "ENDPOINT NOT DEPLOYED".equals(cat) || "DATABASE ERROR".equals(cat)) sev = "High";
            else if ("FEATURE DISABLED".equals(cat)) sev = "Low";
            else sev = "Medium";
        }
        return new String[]{sev, status};
    }

    private static String[] severityColors(String s) {
        switch (s == null ? "" : s) {
            case "Critical": return new String[]{C_CRIT_LT, C_CRIT};
            case "High":     return new String[]{C_RED_LT, C_RED};
            case "Medium":   return new String[]{C_AMBER_LT, C_AMBER};
            case "Low":      return new String[]{C_LIGHT_BLUE, C_MID_BLUE};
            case "Info":     return new String[]{C_STRIPE, C_DARK_TXT};
            default:         return null;
        }
    }

    private static String[] statusColors(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.equals("Closed")) return new String[]{C_GREEN_LT, C_GREEN};
        if (s.equals("Open - Bug")) return new String[]{C_RED_LT, C_RED};
        if (s.startsWith("Open")) return new String[]{C_AMBER_LT, C_AMBER};
        return new String[]{C_STRIPE, C_DARK_TXT};
    }

    private static long count(List<Map<String, String>> rs, String result) {
        return rs.stream().filter(r -> result.equalsIgnoreCase(r.getOrDefault("Result", ""))).count();
    }

    private static long countStatus(List<Map<String, String>> rs, String status) {
        return rs.stream().filter(r -> status.equals(r.get("Defect_Status"))).mapToLong(ExcelUtils::w).sum();
    }

    /** Weight of a row: a "Not executable" row stands for the N planned checks it replaces. */
    static long w(Map<String, String> r) {
        if (!"N/A".equals(r.get("Result"))) return 1;
        try {
            return Math.max(1, Long.parseLong(r.getOrDefault("NA_Count", "1")));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static long wsize(List<Map<String, String>> rs) {
        return rs.stream().mapToLong(ExcelUtils::w).sum();
    }

    private static String pct(long p, long f) {
        return (p + f) == 0 ? "\u2013" : String.format("%.0f%%", p * 100.0 / (p + f));
    }

    private static String highestOpen(List<Map<String, String>> rs) {
        for (String s : SEVERITIES) {
            boolean any = rs.stream().anyMatch(r -> s.equals(r.get("Severity")) && r.getOrDefault("Defect_Status", "").startsWith("Open"));
            if (any) return s;
        }
        return "\u2013";
    }

    /** keeps the first n columns of every row */
    private static List<Object[]> trim(List<Object[]> rows, int n) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : rows) out.add(java.util.Arrays.copyOf(r, n));
        return out;
    }

    private static List<Object[]> overallRowList(String label, List<Map<String, String>> rs) {
        List<Object[]> l = new ArrayList<>();
        l.add(java.util.Arrays.copyOf(overallRow(label, rs), 7));
        return l;
    }

    private static List<Object[]> overallRowList7(String label, List<Map<String, String>> rs) {
        long p = count(rs, "PASS"), f = count(rs, "FAIL");
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{label, wsize(rs), p, f, wsize(rs) - p - f, pct(p, f), highestOpen(rs)});
        return l;
    }

    private static Object[] apiRow(String label, List<ApiGroup> gs) {
        long p = gs.stream().filter(g -> "PASS".equals(g.result)).count();
        long f = gs.stream().filter(g -> "FAIL".equals(g.result)).count();
        long sk = gs.stream().filter(g -> "SKIP".equals(g.result)).count();
        long ne = gs.stream().filter(g -> "NOT EXEC".equals(g.result)).count();
        return new Object[]{label, (long) gs.size(), p, f, sk, ne, pct(p, f)};
    }

    private static Object[] overallRow8(String label, List<Map<String, String>> rs) {
        long p = count(rs, "PASS"), f = count(rs, "FAIL");
        long s = rs.stream().filter(r -> isSkip(r.getOrDefault("Result", ""))).count();
        return new Object[]{label, wsize(rs), p + f, p, f, s, wsize(rs) - p - f - s, pct(p, f)};
    }

    private static Object[] overallRow(String label, List<Map<String, String>> rs) {
        long p = count(rs, "PASS"), f = count(rs, "FAIL");
        long s = rs.stream().filter(r -> isSkip(r.getOrDefault("Result", ""))).count();
        return new Object[]{label, wsize(rs), p, f, s, wsize(rs) - p - f - s, pct(p, f), countStatus(rs, "Open - Bug"),
                countStatus(rs, "Open - Environment"), countStatus(rs, "Open - Test Data"), countStatus(rs, "Closed")};
    }

    private static List<Object[]> severityTable(List<Map<String, String>> rs) {
        List<Object[]> out = new ArrayList<>();
        long[] tot = new long[8];
        for (String sev : SEVERITIES) {
            List<Map<String, String>> s = new ArrayList<>();
            for (Map<String, String> r : rs) if (sev.equals(r.get("Severity"))) s.add(r);
            long[] v = {countStatus(s, "Open - Bug"), countStatus(s, "Open - Environment"), countStatus(s, "Open - Test Data"),
                    countStatus(s, "Blocked"), countStatus(s, "Not Applicable"), countStatus(s, "Closed"), wsize(s), 0};
            v[7] = v[0] + v[1] + v[2];
            for (int i = 0; i < 8; i++) tot[i] += v[i];
            out.add(new Object[]{"\u2013".equals(sev) ? "No defect  (passed / not applicable)" : sev, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]});
        }
        out.add(new Object[]{"TOTAL", tot[0], tot[1], tot[2], tot[3], tot[4], tot[5], tot[6], tot[7]});
        return out;
    }

    /** A pivot-style table: title bar, header, rows (last row bold when hasTotal). Returns the next free row. */
    private static int pivot(XSSFSheet sh, StyleKit sk, int rowNum, String title, String[] hdr, List<Object[]> rows, boolean hasTotal) {
        Row tr = sh.createRow(rowNum++);
        tr.setHeightInPoints(22);
        mergedCell(sh, tr, 0, Math.max(hdr.length - 1, 10), title, sk.sectionHeader());
        Row h = sh.createRow(rowNum++);
        h.setHeightInPoints(30);
        for (int i = 0; i < hdr.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(hdr[i]);
            c.setCellStyle(sk.tableHeader());
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            Object[] vals = rows.get(ri);
            boolean total = hasTotal && ri == rows.size() - 1;
            Row row = sh.createRow(rowNum++);
            row.setHeightInPoints(20);
            for (int i = 0; i < vals.length; i++) {
                Cell c = row.createCell(i);
                Object v = vals[i];
                if (v instanceof Number) c.setCellValue(((Number) v).doubleValue());
                else c.setCellValue(String.valueOf(v));
                String sv = String.valueOf(v);
                String[] sc = severityColors(i == 0 ? sv : (hdr[i].startsWith("Highest") ? sv : ""));
                String hi = hdr[i];
                if (total) c.setCellStyle(sk.footerCell());
                else if (sc != null) c.setCellStyle(sk.statusCell(sc[0], sc[1]));
                else if (v instanceof Number && ((Number) v).longValue() > 0 && (hi.contains("Fail") || hi.equals("Wrong code") || hi.equals("Open - Bug") || hi.equals("Open Bugs") || hi.equals("Total Open")))
                    c.setCellStyle(sk.statusCell(C_RED_LT, C_RED));
                else if (v instanceof Number && ((Number) v).longValue() > 0 && (hi.contains("Pass") || hi.equals("Correct code") || hi.equals("Closed")))
                    c.setCellStyle(sk.statusCell(C_GREEN_LT, C_GREEN));
                else if (v instanceof Number && ((Number) v).longValue() > 0 && hi.startsWith("Open"))
                    c.setCellStyle(sk.statusCell(C_AMBER_LT, C_AMBER));
                else c.setCellStyle(i == 0 ? sk.dataLeft(C_WHITE) : sk.dataCenter(C_WHITE));
            }
        }
        sh.createRow(rowNum++).setHeightInPoints(10);
        return rowNum;
    }

    private static boolean isSkip(String result) {
        return "SKIP".equalsIgnoreCase(result) || "SKIPPED".equalsIgnoreCase(result);
    }

    /** Excel cell limit is 32,767 characters. */
    private static String fitCell(String val) {
        if (val == null) return "";
        return val.length() > 32000 ? val.substring(0, 32000) + "...(truncated - click Full Response for everything)" : val;
    }

    private static LocalDateTime toLocal(long epochMs) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault());
    }

    private static String methodColor(String m) {
        if (m == null) return C_MID_BLUE;
        switch (m.toUpperCase()) {
            case "GET":    return C_GET;
            case "POST":   return C_POST;
            case "PUT":    return C_PUT;
            case "DELETE": return C_DELETE;
            case "PATCH":  return C_PATCH;
            default:       return C_MID_BLUE;
        }
    }

    private static int chars(int n) { return n * 256; }

    private static byte[] hexToBytes(String hex) {
        return new byte[]{
                (byte) Integer.parseInt(hex.substring(0,2), 16),
                (byte) Integer.parseInt(hex.substring(2,4), 16),
                (byte) Integer.parseInt(hex.substring(4,6), 16)
        };
    }
}
