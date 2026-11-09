package com.api.product.core.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Extract;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The per-project test-data workbook (&lt;Project&gt;_testdata.xlsx): the same column layout the legacy projects use, pre-filled from the
 * imported API. The customer fills in valid data and uploads it back; negative and security tests are then derived from it.
 * Secrets are never exported or imported as literals: they stay ${ENV:NAME} references.
 */
public final class TestDataSheet {

    public static final class Summary {
        public int rows;
        public int updatedEndpoints;
        public int newEndpoints;
        public int switchedOff;
        public int variablesSet;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> changes = new ArrayList<>();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final String[] HEAD = { "TC_ID", "Module", "API_Name", "Method", "Endpoint", "Headers", "Payload", "ExpectedStatus", "Extract", "Set", "Run", "Notes" };
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)\\??}");
    private static final Pattern SECRETISH = Pattern.compile("(?i).*(pass|secret|token|pwd|apikey|api_key|otp|key).*");
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}");
    private static final Set<String> BUILTIN = Set.of("NOW", "RANDOM", "UUID", "TXN_ID", "TIMESTAMP", "TODAY", "DATE", "ENV", "PAD", "OTP");

    private TestDataSheet() {}

    // ------------------------------------------------------------------ export

    public static byte[] export(String project, ApiModel m) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle note = wb.createCellStyle();
            note.setWrapText(true);

            Sheet readme = wb.createSheet("README");
            String[] lines = {
                    "Test data for project: " + project,
                    "",
                    "1. Sheet API_TestData has one row per API. Fill the Payload column with VALID data (JSON) that exists in your test environment.",
                    "2. Use ${variable} for values that change (for example ${token}) and define them in the Variables sheet.",
                    "3. Never type passwords, tokens or keys here. Put ${ENV:NAME} and set that environment variable on the machine that runs the tests.",
                    "4. Set Run to N for rows that must not run. Leave TC_ID unchanged; to add an API, add a row with a new TC_ID, Method and Endpoint.",
                    "5. ExpectedStatus: the HTTP code(s) a valid request returns, e.g. 200 or 200,201. Extract: values to keep for later steps, e.g. token=data.accessToken;userId=data.id",
                    "6. Upload this file back in Studio > Test Data. Negative, security and boundary tests are then generated from this valid data.",
                    "",
                    "Built-in values: ${NOW:yyyyMMddHHmmss}  ${DATE:-30:dd/MM/yyyy}  ${RANDOM:6}  ${UUID}  ${TXN_ID}  ${OTP} (a person enters the OTP)." };
            for (int i = 0; i < lines.length; i++) readme.createRow(i).createCell(0).setCellValue(lines[i]);
            readme.setColumnWidth(0, 150 * 256);

            Sheet sh = wb.createSheet("API_TestData");
            Row h = sh.createRow(0);
            for (int i = 0; i < HEAD.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEAD[i]);
                c.setCellStyle(head);
            }
            Set<String> used = new LinkedHashSet<>();
            int r = 1;
            for (TestCase t : m.testCases) {
                if (!"POSITIVE".equals(t.category)) continue;
                Endpoint ep = m.endpoint(t.endpointId);
                if (ep == null) continue;
                Row row = sh.createRow(r++);
                String body = ep.requestBody == null || ep.requestBody.isNull() ? "" : ep.requestBody.isTextual() ? ep.requestBody.asText() : ep.requestBody.toString();
                String headers = headersText(ep);
                String path = ep.path == null ? "" : ep.path;
                set(row, 0, t.id);
                set(row, 1, ep.module == null ? "General" : ep.module);
                set(row, 2, ep.name == null ? ep.id : ep.name);
                set(row, 3, ep.method);
                set(row, 4, path);
                set(row, 5, headers);
                set(row, 6, body);
                set(row, 7, t.expected.status.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
                set(row, 8, ep.extract.stream().map(x -> x.var + "=" + x.jsonPath).reduce((a, b) -> a + ";" + b).orElse(""));
                set(row, 9, ep.set.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ";" + b).orElse(""));
                set(row, 10, t.tags.contains("run-flag-N") ? "N" : "Y");
                String n = ep.authProfileRef == null ? "" : "auth profile: " + ep.authProfileRef;
                set(row, 11, n);
                for (String text : new String[] { body, headers, path }) {
                    Matcher mt = VAR.matcher(text);
                    while (mt.find()) if (!BUILTIN.contains(mt.group(1).split(":")[0])) used.add(mt.group(1));
                }
            }
            int[] widths = { 16, 18, 30, 9, 40, 40, 60, 14, 32, 28, 6, 30 };
            for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
            sh.createFreezePane(2, 1);
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, r - 1), 0, HEAD.length - 1));
            DataValidationHelper dvh = sh.getDataValidationHelper();
            DataValidation dv = dvh.createValidation(dvh.createExplicitListConstraint(new String[] { "Y", "N" }), new CellRangeAddressList(1, Math.max(1, r + 200), 10, 10));
            dv.setShowErrorBox(true);
            sh.addValidationData(dv);

            Sheet vars = wb.createSheet("Variables");
            Row vh = vars.createRow(0);
            String[] vheads = { "Name", "Value", "Note" };
            for (int i = 0; i < vheads.length; i++) {
                Cell c = vh.createCell(i);
                c.setCellValue(vheads[i]);
                c.setCellStyle(head);
            }
            Map<String, String> all = new LinkedHashMap<>();
            if (!m.environments.isEmpty()) all.putAll(m.environments.get(0).variables);
            int vr = 1;
            for (Map.Entry<String, String> e : all.entrySet()) {
                Row row = vars.createRow(vr++);
                String v = e.getValue() == null ? "" : e.getValue();
                boolean secret = SECRETISH.matcher(e.getKey()).matches() && !v.isBlank() && !v.startsWith("${");
                set(row, 0, e.getKey());
                set(row, 1, secret ? "${ENV:" + envName(project, e.getKey()) + "}" : v);
                set(row, 2, secret ? "secret: keep as an environment variable" : "");
            }
            for (String u : used) {
                if (all.containsKey(u)) continue;
                Row row = vars.createRow(vr++);
                boolean secret = SECRETISH.matcher(u).matches();
                set(row, 0, u);
                set(row, 1, secret ? "${ENV:" + envName(project, u) + "}" : "");
                set(row, 2, "used in API_TestData - " + (secret ? "secret: keep as an environment variable" : "fill in a value or let an earlier step extract it"));
            }
            vars.setColumnWidth(0, 28 * 256);
            vars.setColumnWidth(1, 50 * 256);
            vars.setColumnWidth(2, 60 * 256);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String headersText(Endpoint ep) {
        List<String> parts = new ArrayList<>();
        ep.headers.forEach((k, v) -> parts.add(k + ":" + (v != null && JWT.matcher(v.replace("Bearer ", "")).find() ? "Bearer ${token}" : v)));
        if (ep.contentType != null && ep.headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Content-Type"))) parts.add("Content-Type:" + ep.contentType);
        return String.join(";", parts);
    }

    private static void set(Row r, int col, String v) {
        String s = v == null ? "" : v;
        r.createCell(col).setCellValue(s.length() > 32000 ? s.substring(0, 32000) : s);
    }

    static String envName(String project, String name) {
        return (project + "_" + name).toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }

    // ------------------------------------------------------------------ import

    public static Summary apply(String project, ApiModel m, byte[] xlsx) throws IOException {
        Summary s = new Summary();
        XSSFWorkbook opened;
        try {
            opened = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        } catch (Exception bad) {
            throw new IllegalArgumentException("This file is not a valid .xlsx workbook. Download the template again, fill it in and save it as .xlsx.");
        }
        try (XSSFWorkbook wb = opened) {
            Sheet sh = wb.getSheet("API_TestData");
            if (sh == null) throw new IllegalArgumentException("The workbook has no sheet named API_TestData. Download the template again.");
            DataFormatter fmt = new DataFormatter();
            Map<String, Integer> col = new LinkedHashMap<>();
            Row head = sh.getRow(0);
            if (head == null) throw new IllegalArgumentException("API_TestData has no header row");
            for (Cell c : head) col.put(fmt.formatCellValue(c).trim(), c.getColumnIndex());
            if (!col.containsKey("TC_ID")) throw new IllegalArgumentException("Column TC_ID is missing in API_TestData");
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null) continue;
                String id = cell(fmt, row, col, "TC_ID");
                if (id.isBlank()) continue;
                s.rows++;
                TestCase t = null;
                for (TestCase x : m.testCases) if (id.equals(x.id) && "POSITIVE".equals(x.category)) t = x;
                Endpoint ep = t == null ? null : m.endpoint(t.endpointId);
                if (ep == null) {
                    String method = cell(fmt, row, col, "Method").toUpperCase();
                    String path = cell(fmt, row, col, "Endpoint");
                    if (method.isBlank() || path.isBlank()) {
                        s.warnings.add("Row " + (i + 1) + " (" + id + "): unknown TC_ID and no Method/Endpoint, skipped");
                        continue;
                    }
                    if (!method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")) {
                        s.warnings.add("Row " + (i + 1) + " (" + id + "): unsupported method " + method + ", skipped");
                        continue;
                    }
                    ep = new Endpoint();
                    ep.id = id;
                    ep.method = method;
                    ep.path = path;
                    ep.idempotent = "GET".equals(method);
                    m.endpoints.add(ep);
                    t = new TestCase();
                    t.id = id;
                    t.endpointId = id;
                    t.category = "POSITIVE";
                    t.lifecycle = "DRAFT";
                    t.origin = "testdata";
                    m.testCases.add(t);
                    s.newEndpoints++;
                    s.changes.add(id + ": new API " + method + " " + path);
                }
                boolean changed = false;
                String name = cell(fmt, row, col, "API_Name");
                if (!name.isBlank() && !name.equals(ep.name)) {
                    ep.name = name;
                    t.title = name;
                    changed = true;
                }
                String module = cell(fmt, row, col, "Module");
                if (!module.isBlank() && !module.equals(ep.module)) {
                    ep.module = module;
                    changed = true;
                }
                String path = cell(fmt, row, col, "Endpoint");
                if (!path.isBlank() && !path.equals(ep.path)) {
                    ep.path = path;
                    changed = true;
                }
                String method = cell(fmt, row, col, "Method").toUpperCase();
                if (method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") && !method.equals(ep.method)) {
                    ep.method = method;
                    changed = true;
                }
                String payload = cell(fmt, row, col, "Payload");
                if (!payload.isBlank() && !"NA".equalsIgnoreCase(payload)) {
                    JsonNode node;
                    String p = payload.trim();
                    if (p.startsWith("{") || p.startsWith("[")) {
                        try {
                            node = M.readTree(p);
                        } catch (Exception bad) {
                            s.warnings.add("Row " + (i + 1) + " (" + id + "): Payload is not valid JSON, kept the previous body");
                            node = null;
                        }
                    } else {
                        node = new TextNode(p);
                    }
                    if (node != null && !node.equals(ep.requestBody)) {
                        ep.requestBody = node;
                        if (ep.contentType == null) ep.contentType = p.startsWith("<") ? "application/xml" : "application/json";
                        changed = true;
                    }
                }
                String headers = cell(fmt, row, col, "Headers");
                if (!headers.isBlank() && !"NA".equalsIgnoreCase(headers)) {
                    Map<String, String> hm = new LinkedHashMap<>();
                    for (String h : headers.split(";")) {
                        int c = h.indexOf(':');
                        if (c > 0) hm.put(h.substring(0, c).trim(), h.substring(c + 1).trim());
                    }
                    String ct = hm.keySet().stream().filter(k -> k.equalsIgnoreCase("Content-Type")).findFirst().orElse(null);
                    if (ct != null) ep.contentType = hm.remove(ct);
                    if (!hm.equals(ep.headers)) {
                        ep.headers = hm;
                        changed = true;
                    }
                }
                String status = cell(fmt, row, col, "ExpectedStatus");
                if (!status.isBlank()) {
                    List<Integer> st = new ArrayList<>();
                    for (String x : status.split("[,/ ]+")) {
                        try {
                            st.add(Integer.parseInt(x.trim()));
                        } catch (NumberFormatException ignored) {
                            // skip non-numbers
                        }
                    }
                    if (!st.isEmpty() && !st.equals(t.expected.status)) {
                        t.expected.status = st;
                        changed = true;
                    }
                }
                String extract = cell(fmt, row, col, "Extract");
                if (col.containsKey("Extract")) {
                    List<Extract> ex = new ArrayList<>();
                    for (String pair : extract.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0 && !"NA".equalsIgnoreCase(pair.trim())) {
                            Extract x = new Extract();
                            x.var = pair.substring(0, eq).trim();
                            x.jsonPath = pair.substring(eq + 1).trim();
                            ex.add(x);
                        }
                    }
                    String before = M.valueToTree(ep.extract).toString();
                    if (!before.equals(M.valueToTree(ex).toString())) {
                        ep.extract = ex;
                        changed = true;
                    }
                }
                String setCell = cell(fmt, row, col, "Set");
                if (col.containsKey("Set")) {
                    Map<String, String> sm = new LinkedHashMap<>();
                    for (String pair : setCell.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0 && !"NA".equalsIgnoreCase(pair.trim())) sm.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                    if (!sm.equals(ep.set)) {
                        ep.set = sm;
                        changed = true;
                    }
                }
                String run = cell(fmt, row, col, "Run");
                if ("N".equalsIgnoreCase(run) && !t.tags.contains("run-flag-N")) {
                    t.tags.add("run-flag-N");
                    t.lifecycle = "DRAFT";
                    s.switchedOff++;
                    changed = true;
                } else if ("Y".equalsIgnoreCase(run) && t.tags.remove("run-flag-N")) {
                    changed = true;
                }
                if (changed) {
                    t.version++;
                    s.updatedEndpoints++;
                    if (s.changes.size() < 40 && !s.changes.contains(id + ": new API")) s.changes.add(id + ": updated");
                }
            }
            Sheet vs = wb.getSheet("Variables");
            if (vs != null && !m.environments.isEmpty()) {
                Map<String, String> env = m.environments.get(0).variables;
                for (int i = 1; i <= vs.getLastRowNum(); i++) {
                    Row row = vs.getRow(i);
                    if (row == null) continue;
                    String n = row.getCell(0) == null ? "" : fmt.formatCellValue(row.getCell(0)).trim();
                    String v = row.getCell(1) == null ? "" : fmt.formatCellValue(row.getCell(1)).trim();
                    if (n.isBlank() || v.isBlank()) continue;
                    if (SECRETISH.matcher(n).matches() && !v.startsWith("${")) {
                        v = "${ENV:" + envName(project, n) + "}";
                        s.warnings.add("Variable '" + n + "' looks like a secret: stored as " + v + ". Set that environment variable instead of typing the value.");
                    }
                    if (!v.equals(env.get(n))) {
                        env.put(n, v);
                        s.variablesSet++;
                    }
                }
            }
        }
        return s;
    }

    private static String cell(DataFormatter fmt, Row r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) return "";
        Cell c = r.getCell(i);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }
}
