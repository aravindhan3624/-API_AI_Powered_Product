package com.api.product.core.importer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.Extract;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Re-expresses a legacy project (Excel test data + payload folder + config.properties) as a normalized model,
 * so existing projects run through the model runner with no per-project Java.
 * Columns: TC_ID, API_Name, Method, Endpoint, Headers, Payload, ExpectedStatus, Extract, Set, Run, Validate, Module, Collection_Ref.
 * Anything the model cannot express yet (Groovy validations, jsonlist: extractions, payload negatives) is reported as a warning.
 */
public final class ExcelProjectImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern CFG = Pattern.compile("\\$\\{CFG:([^}]+)}");
    private static final Pattern SIMPLE_PATH = Pattern.compile("^[$A-Za-z0-9_.\\[\\]'\"\\-]+$");
    private static final Pattern SECRETISH = Pattern.compile("(?i).*(pass|secret|token|pwd|apikey|api_key).*");

    private final ApiModel model = new ApiModel();
    private final Properties cfg = new Properties();
    private final String project;
    private final Path payloadDir;
    private final Map<String, String> cfgVars = new LinkedHashMap<>();
    private int groovy;
    private int groupNegatives;
    private int unsupportedExtract;

    private ExcelProjectImporter(String project, Path payloadDir) {
        this.project = project;
        this.payloadDir = payloadDir;
    }

    public static ApiModel importProject(String project, Path config, Path xlsx, Path payloadDir) throws Exception {
        ExcelProjectImporter imp = new ExcelProjectImporter(project, payloadDir);
        try (InputStream in = Files.newInputStream(config)) {
            imp.cfg.load(in);
        }
        return imp.build(xlsx);
    }

    private String prop(String key) {
        String v = cfg.getProperty(project + "." + key);
        return v != null ? v : cfg.getProperty(key);
    }

    private ApiModel build(Path xlsx) throws Exception {
        model.api.name = project;
        model.api.version = "legacy-excel";
        model.api.source.type = "excel";
        model.api.source.ref = xlsx.getFileName().toString();

        Environment env = new Environment();
        env.name = prop("env") == null ? "DEFAULT" : prop("env");
        env.baseUrl = prop("base.url") == null ? "" : prop("base.url");
        String prefix = project + ".var.";
        for (String k : cfg.stringPropertyNames()) {
            if (!k.startsWith(prefix)) continue;
            String name = k.substring(prefix.length());
            String value = cfg.getProperty(k);
            if (SECRETISH.matcher(name).matches() && !value.isBlank() && !value.startsWith("${ENV:")) {
                String envName = project + "_" + name.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
                model.api.source.warnings.add("Variable '" + name + "' looks like a secret: model uses ${ENV:" + envName + "}; set that environment variable.");
                value = "${ENV:" + envName + "}";
            }
            env.variables.put(name, value);
        }
        for (String t : new String[] { "MOBILE:tester.mobile", "EMAIL:tester.email", "TESTER_NAME:tester.name" }) {
            String[] kv = t.split(":");
            String v = prop(kv[1]);
            if (v != null && !v.isBlank()) env.variables.put(kv[0], v);
        }
        model.environments.add(env);

        String envelope = prop("crypto.envelope");
        if ("true".equalsIgnoreCase(envelope)) {
            ApiModel.Envelope e = new ApiModel.Envelope();
            e.type = "hybrid-rsa-aes-gcm";
            e.serverPublicKey = String.valueOf(prop("crypto.server.public.key"));
            String skip = prop("crypto.skip.paths");
            if (skip != null) for (String p : skip.split(",")) if (!p.isBlank()) e.skipPaths.add(p.trim());
            model.envelope = e;
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            Sheet sh = wb.getSheet("API_TestData") != null ? wb.getSheet("API_TestData") : wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Map<String, Integer> col = new LinkedHashMap<>();
            Row head = sh.getRow(0);
            for (Cell c : head) col.put(fmt.formatCellValue(c).trim(), c.getColumnIndex());
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row r = sh.getRow(i);
                if (r == null) continue;
                String tc = cell(fmt, r, col, "TC_ID");
                if (tc.isBlank()) continue;
                addRow(fmt, r, col, tc);
            }
        }
        cfgVars.forEach(env.variables::putIfAbsent);
        if (groovy > 0) model.api.source.warnings.add(groovy + " Groovy/complex Validate rule(s) are not supported yet and were skipped.");
        if (unsupportedExtract > 0) model.api.source.warnings.add(unsupportedExtract + " complex Extract rule(s) (jsonlist:, rows.find{}) were skipped.");
        if (groupNegatives > 0) model.api.source.warnings.add(groupNegatives + " payload group negative/invalid variant(s) not imported (generate negatives separately).");
        return model;
    }

    private void addRow(DataFormatter fmt, Row r, Map<String, Integer> col, String tcId) throws Exception {
        Endpoint ep = new Endpoint();
        ep.id = tcId;
        ep.name = cell(fmt, r, col, "API_Name");
        ep.module = cell(fmt, r, col, "Module").isBlank() ? "General" : cell(fmt, r, col, "Module");
        ep.method = cell(fmt, r, col, "Method").toUpperCase();
        ep.path = cfgSub(cell(fmt, r, col, "Endpoint"));
        ep.idempotent = "GET".equals(ep.method);

        for (String h : cell(fmt, r, col, "Headers").split(";")) {
            int c = h.indexOf(':');
            if (c <= 0) continue;
            String k = h.substring(0, c).trim(), v = cfgSub(h.substring(c + 1).trim());
            if (k.equalsIgnoreCase("Content-Type")) ep.contentType = v;
            else ep.headers.put(k, v);
        }

        String payload = cell(fmt, r, col, "Payload");
        if (!payload.isBlank() && !"NA".equalsIgnoreCase(payload)) {
            String t0 = payload.trim();
            boolean raw = t0.startsWith("{") || t0.startsWith("[") || t0.startsWith("<");
            loadPayload(ep, raw ? t0 : t0.split("\\|")[0].trim());
        }

        for (String pair : cell(fmt, r, col, "Extract").split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || "NA".equalsIgnoreCase(pair.trim())) continue;
            String var = pair.substring(0, eq).trim(), path = pair.substring(eq + 1).trim();
            if ("OTP".equalsIgnoreCase(var)) continue; // OTP is always entered by a person
            if (!SIMPLE_PATH.matcher(path).matches() && !path.toLowerCase().startsWith("jsonlist:") && !path.contains(".find{")) {
                unsupportedExtract++;
                continue;
            }
            Extract x = new Extract();
            x.var = var;
            x.jsonPath = path;
            ep.extract.add(x);
        }
        String set = cell(fmt, r, col, "Set");
        if (!set.isBlank() && !"NA".equalsIgnoreCase(set)) {
            for (String pair : set.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) ep.set.put(pair.substring(0, eq).trim(), cfgSub(pair.substring(eq + 1).trim()));
            }
        }
        model.endpoints.add(ep);

        TestCase tc = new TestCase();
        tc.id = tcId;
        tc.endpointId = ep.id;
        tc.category = "POSITIVE";
        tc.title = ep.name;
        tc.origin = "imported";
        String status = cell(fmt, r, col, "ExpectedStatus");
        for (String s : status.split("[,/ ]+")) {
            try {
                tc.expected.status.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // no status
            }
        }
        String validate = cell(fmt, r, col, "Validate");
        if (!validate.isBlank() && !"NA".equalsIgnoreCase(validate)) {
            for (String pair : validate.split(";")) {
                int eq = pair.indexOf('=');
                int close = pair.indexOf("}.");
                if (pair.contains(".find{") && close > 0) eq = pair.indexOf('=', close);
                String path = eq > 0 ? pair.substring(0, eq).trim() : "";
                if (eq > 0 && (SIMPLE_PATH.matcher(path).matches() || path.contains(".find{"))) {
                    Assertion a = new Assertion();
                    a.type = "jsonPathEquals";
                    a.path = path;
                    a.expected = new TextNode(cfgSub(pair.substring(eq + 1).trim()));
                    tc.expected.assertions.add(a);
                } else if (!pair.isBlank()) {
                    groovy++;
                }
            }
        }
        String run = cell(fmt, r, col, "Run");
        if (run.equalsIgnoreCase("N")) {
            tc.lifecycle = "DRAFT";
            tc.tags.add("run-flag-N");
        } else {
            tc.lifecycle = "ACTIVE";
        }
        String ref = cell(fmt, r, col, "Collection_Ref");
        if (!ref.isBlank()) tc.tags.add(ref);
        model.testCases.add(tc);
    }

    private void loadPayload(Endpoint ep, String file) throws Exception {
        String text;
        String tf = file.trim();
        if (tf.startsWith("{") || tf.startsWith("[") || tf.startsWith("<")) {
            text = cfgSub(tf); // raw payload typed in the Excel cell
        } else {
            Path p;
            try {
                p = payloadDir.resolve(tf);
            } catch (java.nio.file.InvalidPathException bad) {
                model.api.source.warnings.add(ep.id + ": payload reference is not a file name: " + tf);
                return;
            }
            if (!Files.exists(p)) {
                model.api.source.warnings.add(ep.id + ": payload file not found: " + tf);
                return;
            }
            text = cfgSub(Files.readString(p));
        }
        if (tf.endsWith(".xml") || text.trim().startsWith("<")) {
            ep.requestBody = new TextNode(text.trim());
            ep.contentType = ep.contentType == null ? "application/xml" : ep.contentType;
            return;
        }
        JsonNode root;
        try {
            root = M.readTree(text);
        } catch (Exception bad) {
            ep.requestBody = new TextNode(text);
            model.api.source.warnings.add(ep.id + ": payload " + file + " is not valid JSON; sent as text.");
            return;
        }
        if (root.isArray() && root.size() > 0) {
            ep.requestBody = root.get(0);
        } else if (root.isObject() && root.size() > 0 && allGroupKeys(root)) {
            JsonNode first = null;
            for (String k : new String[] { "valid", "positive" }) if (root.has(k)) first = root.get(k);
            if (first == null) {
                groupNegatives++;
                return;
            }
            ep.requestBody = first.isArray() ? first.get(0) : first;
            if (root.has("negative") || root.has("invalid")) groupNegatives++;
        } else {
            ep.requestBody = root;
        }
        if (ep.contentType == null) ep.contentType = "application/json";
    }

    private static boolean allGroupKeys(JsonNode root) {
        java.util.Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String k = it.next().toLowerCase();
            if (!(k.equals("valid") || k.equals("positive") || k.equals("negative") || k.equals("invalid"))) return false;
        }
        return true;
    }

    /** ${CFG:name} -> ${cfg.name} plus a variable holding the configured value. */
    private String cfgSub(String s) {
        if (s == null) return "";
        Matcher m = CFG.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String v = prop(name);
            if (v != null) cfgVars.put("cfg." + name, v);
            m.appendReplacement(sb, Matcher.quoteReplacement("${cfg." + name + "}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cell(DataFormatter fmt, Row r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) return "";
        Cell c = r.getCell(i);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }
}
