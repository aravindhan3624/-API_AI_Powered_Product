package com.api.product.core.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * File-based project storage: projects/NAME/model.json (+ history/ backups, history.jsonl audit log, docs/ for BRDs).
 * Every save keeps the previous model so nothing is lost.
 */
public final class ProjectStore {

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    private final Path reports;

    public ProjectStore(Path root, Path reports) {
        this.root = root;
        this.reports = reports;
    }

    public static ProjectStore defaults() {
        return new ProjectStore(Paths.get("projects"), Paths.get("reports"));
    }

    public Path dir(String project) {
        return root.resolve(safe(project));
    }

    public List<String> projects() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p.resolve("model.json")) && !n.startsWith("_")) out.add(n);
            }
        } catch (IOException ignored) {
            // empty list
        }
        Collections.sort(out);
        return out;
    }

    public boolean exists(String project) {
        return Files.isRegularFile(dir(project).resolve("model.json"));
    }

    public ApiModel load(String project) throws IOException {
        return M.readValue(dir(project).resolve("model.json").toFile(), ApiModel.class);
    }

    /** Saves the model; the previous file is kept as history/model-&lt;timestamp&gt;.json. */
    public synchronized void save(String project, ApiModel model, String actor, String note) throws IOException {
        Path d = dir(project);
        Files.createDirectories(d.resolve("history"));
        Path cur = d.resolve("model.json");
        if (Files.exists(cur)) {
            Files.copy(cur, d.resolve("history").resolve("model-" + System.currentTimeMillis() + ".json"));
            prune(d.resolve("history"), 30);
        }
        M.writeValue(cur.toFile(), model);
        ObjectNode e = M.createObjectNode();
        e.put("note", note);
        audit(project, actor, e);
    }

    public synchronized void audit(String project, String actor, ObjectNode event) {
        try {
            Files.createDirectories(dir(project));
            event.put("ts", Instant.now().toString());
            event.put("actor", actor != null ? actor : Actor.current() != null ? Actor.current() : System.getProperty("user.name", "user"));
            // tamper evidence: every entry carries the hash of the previous one, so a changed or removed line breaks the chain
            event.put("prev", lastHash(project));
            event.put("hash", sha256(event.toString()));
            Files.writeString(dir(project).resolve("history.jsonl"), event.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // audit must never break the action
        }
    }

    private String lastHash(String project) {
        Path f = dir(project).resolve("history.jsonl");
        if (!Files.exists(f)) return "";
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).isBlank()) continue;
                return M.readTree(lines.get(i)).path("hash").asText("");
            }
        } catch (IOException ignored) {
            // treat as a fresh chain
        }
        return "";
    }

    static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks the hash chain of a project's history. Entries from before the chain existed are counted as legacy and not verified. */
    public ObjectNode verifyAudit(String project) {
        ObjectNode r = M.createObjectNode();
        int legacy = 0, chained = 0, broken = 0;
        String prev = "";
        int brokenAt = -1, line = 0;
        Path f = dir(project).resolve("history.jsonl");
        try {
            if (Files.exists(f)) {
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    line++;
                    if (l.isBlank()) continue;
                    ObjectNode e = (ObjectNode) M.readTree(l);
                    if (!e.has("hash")) {
                        legacy++;
                        continue;
                    }
                    String hash = e.get("hash").asText();
                    String storedPrev = e.path("prev").asText("");
                    e.remove("hash");
                    boolean ok = sha256(e.toString()).equals(hash) && (chained == 0 || storedPrev.equals(prev));
                    if (ok) {
                        chained++;
                    } else if (broken++ == 0) {
                        brokenAt = line;
                    }
                    prev = hash;
                }
            }
        } catch (IOException e) {
            r.put("error", e.getMessage());
        }
        r.put("chained", chained);
        r.put("legacy", legacy);
        r.put("broken", broken);
        r.put("firstBrokenLine", brokenAt);
        r.put("ok", broken == 0);
        return r;
    }

    public List<ObjectNode> history(String project, int max) {
        List<ObjectNode> out = new ArrayList<>();
        Path f = dir(project).resolve("history.jsonl");
        if (!Files.exists(f)) return out;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - max); i < lines.size(); i++) out.add((ObjectNode) M.readTree(lines.get(i)));
        } catch (IOException ignored) {
            // return what we have
        }
        Collections.reverse(out);
        return out;
    }

    /** Results of the newest model run of this project, or an empty list. */
    public List<TestResult> latestResults(String project) {
        try {
            String name = load(project).api.name;
            Path runs = reports.resolve("model-runs");
            if (!Files.isDirectory(runs)) return new ArrayList<>();
            Path best = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(runs, name + "_RUN-*")) {
                for (Path p : ds) {
                    if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0) best = p;
                }
            }
            if (best == null) return new ArrayList<>();
            return M.readValue(best.resolve("results.json").toFile(), new TypeReference<List<TestResult>>() { });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void prune(Path dir, int keep) throws IOException {
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "model-*.json")) {
            ds.forEach(all::add);
        }
        Collections.sort(all);
        for (int i = 0; i < all.size() - keep; i++) Files.deleteIfExists(all.get(i));
    }

    public static String safe(String s) {
        return s == null ? "project" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
