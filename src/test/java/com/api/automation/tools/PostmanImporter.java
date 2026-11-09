package com.api.automation.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Converts a Postman collection (v2.1) into this framework's format:
 *
 *   src/test/resources/API_TestData/<PROJECT>/<PROJECT>_testdata.xlsx
 *   src/test/resources/payloads/<PROJECT>/TCxx_<name>.json
 *   config.properties  -> <PROJECT>.* block (base URL of every server, basic-auth users)
 *   runners/<PROJECT>_Runner.java + suites/testng-<PROJECT>.xml
 *
 * HOW TO RUN (IntelliJ): put the collection in <project>/postman/, right-click this class → Run.
 *   Optional program arguments:  <collection.json> <PROJECT_CODE>
 *
 * Smart conversions:
 *   - Every server gets a config key; endpoints become ${CFG:url.<alias>}/path
 *   - Login / getToken request → extracts ACCESS_TOKEN; later "Bearer eyJ..." → "Bearer ${ACCESS_TOKEN}"
 *   - Basic auth → "Authorization: Basic ${BASIC_AUTH:<user>}" (credentials in config.properties)
 *   - txnid / request-id headers → fresh ${NOW:...}/${RANDOM:n} on every run
 *   - "ts" timestamps in bodies → ${NOW:yyyy-MM-dd'T'HH:mm:ss}
 *   - Destructive calls (deactivate/delete/close...), encrypted Aadhaar PID replays and duplicates → Run = N
 */
public class PostmanImporter {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> SKIP_LABELS = Set.of("uat", "ppr", "www", "api", "dev", "sit", "qa", "test", "preprod", "prod");
    private static final Pattern TOKEN_STEP = Pattern.compile("(?i)(gettoken|createtoken|generatetoken|accesstoken|login|signin|authenticate|\\btoken\\b)");
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?i)(deactivat|delete|remove|close|block|cancel|purge|reset)");
    private static final Pattern TXN_HEADER = Pattern.compile("(?i)^(txnid|txn_id|transactionid|requestid|request-id|x-request-id|correlationid|x-correlation-id|traceid)$");
    private static final String TOKEN_EXTRACT =
            "ACCESS_TOKEN=token;ACCESS_TOKEN=Token;ACCESS_TOKEN=access_token;ACCESS_TOKEN=accessToken;"
                    + "ACCESS_TOKEN=data.token;ACCESS_TOKEN=data.access_token;ACCESS_TOKEN=result.token;ACCESS_TOKEN=jwt;"
                    + "ACCESS_TOKEN=$header.Authorization;ACCESS_TOKEN=$header.Token;ACCESS_TOKEN=$body";

    /** Requests that change data (create / update / workflow actions) are imported with Run = N. */
    private static final Pattern WRITE = Pattern.compile(
            "(?i)\\b(save|update|upsert|delete|toggle|approve|reject|revert|submit|set|reset|logout|release|lock|unlock|"
                    + "trigger|retry|cancel|create|clone|generate|change|upload|push|action|enroll|crud|add|deactivat\\w*|remove|close|purge)\\b");

    static class Req {
        String name, folder, method, url, origin, alias, path, body, description = "", notes = "";
        List<String[]> headers = new ArrayList<>();
        String basicUser, basicPass, bearer;
        List<String> setsVars = new ArrayList<>();
        boolean tokenStep;
    }

    private static Path environmentFile;

    /** Program arguments (all optional): <collection.json> <PROJECT_CODE> <postman_environment.json> */
    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        String project = null;
        if (args.length >= 1) {
            files.add(Paths.get(args[0]));
            if (args.length >= 2) project = args[1];
            if (args.length >= 3) environmentFile = Paths.get(args[2]);
        } else {
            File dir = new File("postman");
            File[] found = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
            if (found == null || found.length == 0) {
                System.out.println("Put your Postman collection(s) in " + dir.getAbsolutePath() + " and run again.");
                return;
            }
            for (File f : found) files.add(f.toPath());
        }
        for (Path f : files) {
            String code = project != null ? project
                    : f.getFileName().toString().replaceAll("(?i)\\.postman_collection\\.json$|\\.json$", "")
                    .replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            importCollection(f, code);
        }
    }

    public static void importCollection(Path collection, String project) throws Exception {
        JsonNode root = M.readTree(collection.toFile());
        List<Req> reqs = new ArrayList<>();
        walk(root.path("item"), "", root.path("auth"), reqs);
        System.out.println("\n=== Importing " + collection.getFileName() + " as project " + project + " (" + reqs.size() + " requests) ===");

        // ── Postman variables (collection + environment) → config <PROJECT>.var.<name> ──
        Map<String, String> vars = new LinkedHashMap<>();
        for (JsonNode v : root.path("variable")) vars.put(v.path("key").asText(), v.path("value").asText(""));
        if (environmentFile != null) {
            for (JsonNode v : M.readTree(environmentFile.toFile()).path("values")) {
                if (v.path("enabled").asBoolean(true)) vars.put(v.path("key").asText(), v.path("value").asText(""));
            }
            System.out.println("Environment: " + environmentFile.getFileName());
        }
        Set<String> runtimeVars = new HashSet<>();
        reqs.forEach(r -> runtimeVars.addAll(r.setsVars));

        // Collections with login scripts: the request that sets the Bearer variable is the token step
        boolean scripted = reqs.stream().anyMatch(r -> !r.setsVars.isEmpty());
        if (scripted) {
            Set<String> bearerVars = new HashSet<>();
            reqs.forEach(r -> { if (r.bearer != null) bearerVars.add(r.bearer.replaceAll("[${}]", "")); });
            reqs.forEach(r -> r.tokenStep = r.setsVars.stream().anyMatch(bearerVars::contains));
        }

        // ── servers → aliases ──
        Map<String, String> originAlias = new LinkedHashMap<>();
        Map<String, Integer> originCount = new LinkedHashMap<>();
        for (Req r : reqs) {
            if (r.origin.isEmpty()) continue;             // URL built from variables, e.g. {{api_base_url}}
            String alias = originAlias.computeIfAbsent(r.origin, o -> uniqueAlias(aliasFor(o, r.path), originAlias.values()));
            r.alias = alias;
            originCount.merge(r.origin, 1, Integer::sum);
        }
        String mainOrigin = originCount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(vars.entrySet().stream().filter(e -> e.getKey().toLowerCase().contains("url"))
                        .map(Map.Entry::getValue).findFirst().orElse(""));

        // ── token step first ──
        boolean hasToken = reqs.stream().anyMatch(r -> r.tokenStep);
        reqs.sort(Comparator.comparing(r -> r.tokenStep ? 0 : 1));

        Path payloadDir = Paths.get("src/test/resources/payloads", project);
        Path dataDir = Paths.get("src/test/resources/API_TestData", project);
        Files.createDirectories(payloadDir);
        Files.createDirectories(dataDir);

        List<String[]> rows = new ArrayList<>();
        Map<String, String> basicUsers = new LinkedHashMap<>();
        Map<String, String> seen = new HashMap<>();
        int n = 1;
        for (Req r : reqs) {
            String tc = String.format("TC%02d", n++);
            List<String> headerParts = new ArrayList<>();
            for (String[] h : r.headers) {
                String key = h[0], value = h[1];
                if (key.equalsIgnoreCase("Content-Type")) continue;
                if (hasToken && !r.tokenStep && value.matches("(?i)bearer\\s+ey[A-Za-z0-9_\\-]+\\..*")) {
                    value = "Bearer ${ACCESS_TOKEN}";
                } else if (value.toLowerCase().contains("get token")) {
                    if (r.tokenStep) {
                        r.notes += "Collection says header '" + key + "' comes from 'Create Token' (not in collection) - add it if login fails. ";
                        continue;
                    }
                    value = hasToken ? "${ACCESS_TOKEN}" : value;
                } else if (TXN_HEADER.matcher(key).matches()) {
                    value = dynamicTxn(value);
                }
                headerParts.add(key + ":" + value);
            }
            if (r.basicUser != null) {
                String a = r.basicUser.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
                basicUsers.put(a, r.basicUser + "\u0000" + (r.basicPass == null ? "" : r.basicPass));
                headerParts.add("Authorization:Basic ${BASIC_AUTH:" + a + "}");
            }
            if (r.bearer != null && !r.tokenStep
                    && headerParts.stream().noneMatch(h -> h.toLowerCase().startsWith("authorization:"))) {
                headerParts.add("Authorization:Bearer " + r.bearer);
            }

            // ── payload ──
            String payloadCell = "NA";
            boolean payloadOk = true;
            if (r.body != null && !r.body.isBlank()) {
                String body = r.body.replaceAll("(?s)/\\*.*?\\*/", "").trim()
                        .replaceAll("\\{\\{([^}]+)}}", "\\${$1}");          // Postman {{var}} → ${var}
                // OTP verification step: an empty "otp" field becomes ${OTP} → the OTP popup asks for it
                if ((r.name + r.path).toLowerCase().matches(".*(verify|validate)\\s*otp.*")) {
                    body = body.replaceAll("(\"otp\"\\s*:\\s*)\"\"", "$1\"\\${OTP}\"");
                }
                body = body.replaceAll("(\"ts\"\\s*:\\s*\")\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\")",
                        "$1\\${NOW:yyyy-MM-dd'T'HH:mm:ss}$2");
                try {
                    body = M.writerWithDefaultPrettyPrinter().writeValueAsString(M.readTree(body));
                } catch (Exception e) {
                    payloadOk = false;
                    r.notes += "Body is not valid JSON - please fix the payload file. ";
                }
                String file = tc + "_" + r.name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".json";
                if (file.length() > 70) file = file.substring(0, 66) + ".json";
                Files.writeString(payloadDir.resolve(file), body, StandardCharsets.UTF_8);
                payloadCell = file;

                if (body.contains("\"skey\"") && body.contains("\"hmac\"")) {
                    r.notes += "Contains an encrypted Aadhaar PID block (skey/hmac) captured earlier - UIDAI rejects replays; "
                            + "needs a fresh PID block from the RD-service / OTP client. ";
                }
            }

            String endpoint = r.origin.isEmpty() ? r.path : "${CFG:url." + r.alias + "}" + r.path;
            String dedupeKey = r.method + " " + endpoint + " " + (r.body == null ? "" :
                    r.body.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", "").replaceAll("\\s", ""));
            String dup = seen.putIfAbsent(dedupeKey, tc);
            if (dup != null) r.notes += "Duplicate of " + dup + " - skipped. ";

            boolean mock = r.path.contains("__NOT_WIRED__");
            if (mock) r.notes += "Not wired to any backend (mock-only in the app). ";
            boolean destructive = !mock && !r.tokenStep && !r.name.matches("(?i)^(get|list|fetch|search|poll)\\b.*")
                    && (WRITE.matcher(r.name).find() || DESTRUCTIVE.matcher(r.path).find());
            if (destructive) r.notes += "Changes data (create/update/workflow) - Run=N by default; set Run=Y after checking the test data. ";
            if (r.description.contains("Encrypted envelope")) r.notes += "Encrypted API (RSA-OAEP + AES-256-GCM envelope). ";

            String extract = "NA";
            if (!r.setsVars.isEmpty()) extract = extractFor(r.setsVars);
            else if (r.tokenStep) extract = TOKEN_EXTRACT;

            boolean run = payloadOk && !destructive && !mock && dup == null && !r.notes.contains("PID block");
            rows.add(new String[]{
                    tc,
                    apiName(r),
                    r.method,
                    endpoint,
                    String.join(";", headerParts),
                    payloadCell,
                    "200",
                    extract,
                    "",
                    run ? "Y" : "N",
                    (expectedFrom(r.description) + (r.notes.isBlank() ? "" : "  NOTE: " + r.notes.trim())).trim(),
                    ""
            });
        }

        Path xlsx = dataDir.resolve(project + "_testdata.xlsx");
        if (Files.exists(xlsx)) {
            Path bak = dataDir.resolve(project + "_testdata_backup_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx");
            Files.move(xlsx, bak);
            System.out.println("Existing test data backed up to " + bak);
        }
        writeExcel(xlsx, rows);

        vars.keySet().removeAll(runtimeVars);   // e.g. token / csrf_token come from the login response
        writeConfig(project, originAlias, mainOrigin, basicUsers, vars, collection.getFileName().toString());
        writeRunner(project);

        System.out.println("✔ Test data : " + xlsx.toAbsolutePath());
        System.out.println("✔ Payloads  : " + payloadDir.toAbsolutePath());
        rows.forEach(r -> System.out.println("   " + r[0] + "  Run=" + r[9] + "  " + r[2] + " " + r[1]
                + (r[10].isEmpty() ? "" : "   <-- " + r[10])));
    }

    // ════════════════════════════════════════════════════════════════════
    private static void walk(JsonNode items, String folder, JsonNode inheritedAuth, List<Req> out) {
        for (JsonNode it : items) {
            JsonNode auth = it.has("auth") ? it.path("auth") : inheritedAuth;
            if (it.has("item")) {
                walk(it.path("item"), folder.isEmpty() ? it.path("name").asText() : folder + "/" + it.path("name").asText(), auth, out);
                continue;
            }
            JsonNode rq = it.path("request");
            JsonNode rAuth = rq.has("auth") ? rq.path("auth") : auth;
            Req r = new Req();
            r.name = it.path("name").asText("request");
            r.folder = folder;
            r.method = rq.path("method").asText("GET").toUpperCase();
            r.url = rq.path("url").isTextual() ? rq.path("url").asText() : rq.path("url").path("raw").asText();
            r.url = r.url.replaceAll("\\{\\{([^}]+)}}", "\\${$1}");
            splitUrl(r);
            for (JsonNode h : rq.path("header")) {
                if (h.path("disabled").asBoolean(false)) continue;
                r.headers.add(new String[]{h.path("key").asText(), h.path("value").asText().replaceAll("\\{\\{([^}]+)}}", "\\${$1}")});
            }
            if (rq.path("body").path("mode").asText().equals("raw")) {
                r.body = rq.path("body").path("raw").asText();
            }
            if (rAuth != null && "basic".equals(rAuth.path("type").asText())) {
                for (JsonNode kv : rAuth.path("basic")) {
                    if (kv.path("key").asText().equals("username")) r.basicUser = kv.path("value").asText();
                    if (kv.path("key").asText().equals("password")) r.basicPass = kv.path("value").asText();
                }
            }
            if (rAuth != null && "bearer".equals(rAuth.path("type").asText())) {
                for (JsonNode kv : rAuth.path("bearer")) {
                    if (kv.path("key").asText().equals("token")) {
                        r.bearer = kv.path("value").asText().replaceAll("\\{\\{([^}]+)}}", "\\${$1}");
                    }
                }
            }
            JsonNode desc = rq.path("description");
            r.description = desc.isTextual() ? desc.asText() : desc.path("content").asText("");
            // Variables the request's test script stores, e.g. pm.collectionVariables.set('token', ...)
            for (JsonNode ev : it.path("event")) {
                if (!"test".equals(ev.path("listen").asText())) continue;
                StringBuilder script = new StringBuilder();
                ev.path("script").path("exec").forEach(l -> script.append(l.asText()).append('\n'));
                Matcher sm = Pattern.compile("\\.set\\(\\s*['\"]([A-Za-z0-9_]+)['\"]").matcher(script);
                while (sm.find()) if (!r.setsVars.contains(sm.group(1))) r.setsVars.add(sm.group(1));
            }
            r.tokenStep = TOKEN_STEP.matcher(r.name + " " + r.path.split("\\?")[0]).find()
                    && !r.name.toLowerCase().contains("otp");
            out.add(r);
        }
    }

    private static void splitUrl(Req r) {
        if (r.url.trim().startsWith("${")) {        // {{api_base_url}}{{api_subpath}}/auth/login
            r.origin = "";
            r.path = r.url.trim();
            return;
        }
        Matcher m = Pattern.compile("^(https?)://([^/:?#]+)(?::(\\d+))?(.*)$").matcher(r.url.trim());
        if (!m.matches()) {
            r.origin = "";
            r.path = r.url.startsWith("/") ? r.url : "/" + r.url;
            return;
        }
        String scheme = m.group(1), host = m.group(2), port = m.group(3);
        boolean defaultPort = port == null || (scheme.equals("https") && port.equals("443")) || (scheme.equals("http") && port.equals("80"));
        r.origin = scheme + "://" + host + (defaultPort ? "" : ":" + port);
        r.path = m.group(4).isEmpty() ? "/" : m.group(4);
    }

    private static String aliasFor(String origin, String path) {
        String host = origin.replaceAll("^https?://", "").replaceAll(":\\d+$", "");
        if (host.matches("[\\d.]+")) {
            String seg = path.replaceAll("^/", "").split("[/?]")[0];
            return seg.isEmpty() ? "ip" + host.replace('.', '_') : seg.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        }
        for (String label : host.split("\\.")) {
            if (!SKIP_LABELS.contains(label.toLowerCase())) return label.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        }
        return host.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static String uniqueAlias(String alias, Collection<String> used) {
        String a = alias;
        int i = 2;
        while (used.contains(a)) a = alias + i++;
        return a;
    }

    private static String apiName(Req r) {
        String name = r.name;
        if (name.matches("(?i)^https?://.*")) {
            String[] segs = r.path.split("\\?")[0].replaceAll("^/", "").split("/");
            name = String.join(" ", Arrays.copyOfRange(segs, Math.max(0, segs.length - 2), segs.length));
            name = r.alias + " " + name;
        }
        // "00 Auth > Login" for big, module-organised collections
        if (r.folder != null && r.folder.matches("^\\d{2}\\b.*")) {
            String module = r.folder.split("/")[0].replaceAll("\\s*\\(.*\\)", "").trim();
            name = module + " > " + name;
        }
        return name;
    }

    /** Extract rules for variables a Postman test script stores (token, csrf_token, otpSessionRef...). */
    private static String extractFor(List<String> vars) {
        List<String> rules = new ArrayList<>();
        for (String v : vars) {
            Set<String> names = new LinkedHashSet<>(List.of(v, camel(v)));
            if (v.equalsIgnoreCase("token")) names.addAll(List.of("Token", "accessToken", "jwtToken"));
            for (String n : names) {
                for (String prefix : List.of("responseList[0].", "data.responseList[0].", "data[0].", "", "data.")) {
                    rules.add(v + "=" + prefix + n);
                }
            }
        }
        return String.join(";", rules);
    }

    private static String camel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    /** "Response: {...}" part of the Postman description → Expected Result text. */
    private static String expectedFrom(String description) {
        if (description == null || description.isBlank()) return "";
        String d = description.split("⚠️")[0].replaceAll("\\s+", " ").trim();
        Matcher m = Pattern.compile("(?i)Response(?: \\(decrypted\\))?:\\s*(.{1,300})").matcher(d);
        String text = m.find() ? "Response: " + m.group(1) : d;
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /** 18-A321013000252-O-20260304102824-81498 → 18-A321013000252-O-${NOW:yyyyMMddHHmmss}-${RANDOM:5} */
    private static String dynamicTxn(String value) {
        String v = value.replaceFirst("(?<!\\d)\\d{14}(?!\\d)", Matcher.quoteReplacement("${NOW:yyyyMMddHHmmss}"));
        Matcher m = Pattern.compile("-(\\d{3,})$").matcher(v);
        if (m.find()) v = v.substring(0, m.start()) + "-${RANDOM:" + m.group(1).length() + "}";
        return v;
    }

    // ════════════════════════════════════════════════════════════════════
    private static final String[] COLUMNS = {"TC_ID", "API_Name", "Method", "Endpoint", "Headers", "Payload",
            "ExpectedStatus", "Extract", "Set", "Run", "ExpectedResult", "Validate"};
    private static final int[] WIDTHS = {8, 30, 9, 60, 60, 38, 15, 40, 25, 6, 60, 30};

    private static void writeExcel(Path file, List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("API_TestData");
            XSSFCellStyle hdr = wb.createCellStyle();
            hdr.setFillForegroundColor(new XSSFColor(new byte[]{0x15, 0x65, (byte) 0xC0}, null));
            hdr.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFFont f = wb.createFont();
            f.setBold(true);
            f.setColor(new XSSFColor(new byte[]{(byte) 255, (byte) 255, (byte) 255}, null));
            hdr.setFont(f);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);

            Row h = sh.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(COLUMNS[i]);
                c.setCellStyle(hdr);
                sh.setColumnWidth(i, WIDTHS[i] * 256);
            }
            int r = 1;
            for (String[] row : rows) {
                Row x = sh.createRow(r++);
                for (int i = 0; i < row.length; i++) {
                    Cell c = x.createCell(i);
                    c.setCellValue(row[i]);
                    c.setCellStyle(wrap);
                }
            }
            sh.createFreezePane(0, 1);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }
    }

    private static void writeConfig(String p, Map<String, String> originAlias, String mainOrigin,
                                    Map<String, String> basicUsers, Map<String, String> vars,
                                    String source) throws Exception {
        Path cfg = Paths.get("src/test/resources/config.properties");
        String text = Files.readString(cfg, StandardCharsets.UTF_8);
        if (text.contains("\n" + p + ".base.url=")) {
            System.out.println("config.properties already has " + p + ".* - not changed. Servers in this collection:");
            originAlias.forEach((o, a) -> System.out.println("   " + p + ".url." + a + "=" + o));
            return;
        }
        StringBuilder sb = new StringBuilder("\n# ---- " + p + " (imported from " + source + ") ----\n");
        sb.append(p).append(".display.name=").append(p).append('\n');
        sb.append(p).append(".base.url=").append(mainOrigin).append('\n');
        sb.append(p).append(".env=UAT\n");
        sb.append(p).append(".testdata.file=").append(p).append("_testdata.xlsx\n");
        sb.append("# Servers used by this project (endpoints use ${CFG:url.<name>})\n");
        originAlias.forEach((o, a) -> sb.append(p).append(".url.").append(a).append('=').append(o).append('\n'));
        if (!vars.isEmpty()) {
            sb.append("# Postman variables - used as ${name} in endpoints / payloads / headers. Change test data here.\n");
            vars.forEach((k, v) -> sb.append(p).append(".var.").append(k).append('=').append(v).append('\n'));
        }
        if (!basicUsers.isEmpty()) {
            sb.append("# Basic-auth users (you may write the password as ${ENV:VARIABLE_NAME} instead)\n");
            basicUsers.forEach((a, up) -> {
                String[] x = up.split("\u0000", 2);
                sb.append(p).append(".auth.").append(a).append(".username=").append(x[0]).append('\n');
                sb.append(p).append(".auth.").append(a).append(".password=").append(x.length > 1 ? x[1] : "").append('\n');
            });
        }
        Files.writeString(cfg, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("✔ config.properties: added " + p + ".* block");
    }

    private static void writeRunner(String p) throws Exception {
        Path runner = Paths.get("src/test/java/com/api/automation/runners", p + "_Runner.java");
        if (!Files.exists(runner)) {
            Files.writeString(runner, "package com.api.automation.runners;\n\n"
                    + "/** " + p + " - generated by PostmanImporter. */\n"
                    + "public class " + p + "_Runner extends BaseApiRunner {\n\n"
                    + "    @Override\n    protected String project() {\n        return \"" + p + "\";\n    }\n\n"
                    + "    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */\n"
                    + "    @Override\n    protected boolean screenRecording() {\n        return false;\n    }\n}\n", StandardCharsets.UTF_8);
            System.out.println("✔ Runner   : " + runner + "  (Build → Rebuild to compile it)");
        }
        Path suite = Paths.get("suites", "testng-" + p + ".xml");
        if (!Files.exists(suite)) {
            Files.createDirectories(suite.getParent());
            Files.writeString(suite, "<!DOCTYPE suite SYSTEM \"https://testng.org/testng-1.0.dtd\">\n\n"
                    + "<suite name=\"" + p + " API Automation\">\n\n"
                    + "    <listeners>\n        <listener class-name=\"io.qameta.allure.testng.AllureTestNg\"/>\n    </listeners>\n\n"
                    + "    <test name=\"" + p + " API Tests\">\n        <classes>\n"
                    + "            <class name=\"com.api.automation.runners." + p + "_Runner\"/>\n"
                    + "        </classes>\n    </test>\n\n</suite>\n", StandardCharsets.UTF_8);
        }
    }
}
