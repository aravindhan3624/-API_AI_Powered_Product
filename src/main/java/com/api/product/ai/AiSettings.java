package com.api.product.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Where the optional language model lives and how gently it is used. The model is OFF until the user switches it on, so nothing ever
 * loads or calls it by itself. It can run on this PC or on another machine (set the address), and low-power mode keeps requests small
 * (short context, few CPU threads, one request at a time) so a modest PC stays responsive.
 */
public final class AiSettings {

    public boolean enabled = false;
    public String url = "http://localhost:11434";
    /** auto = the best installed model this PC can run; otherwise a model name such as qwen2.5:3b */
    public String model = "auto";
    public boolean lowPower = true;
    /** Let the model rephrase every assistant answer (costs one model call per chat message). */
    public boolean polish = false;
    public int timeoutSeconds = 180;
    /** How long to wait when connecting to the model host. 0 = automatic: short for this PC, longer for another machine. */
    public int connectTimeoutMs = 0;

    /** True when the address points to another machine (not localhost / 127.0.0.1 / ::1). */
    public static boolean isRemoteUrl(String u) {
        if (u == null) return false;
        String h = u.replaceFirst("(?i)^https?://", "").replaceAll("[/?#].*$", "").replaceAll("^.*@", "");
        if (h.startsWith("[")) h = h.replaceAll("^\\[(.*?)\\].*$", "$1");
        else h = h.replaceAll(":\\d+$", "");
        h = h.toLowerCase();
        return !(h.isEmpty() || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.endsWith(".localhost"));
    }

    /** Connect timeout actually used (milliseconds). */
    public int connectMs() {
        return connectTimeoutMs > 0 ? connectTimeoutMs : (isRemoteUrl(url) ? 4000 : 800);
    }

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static Path file() {
        String o = System.getProperty("ai.settings");
        return o != null && !o.isBlank() ? Paths.get(o) : Paths.get(System.getProperty("user.home"), ".api-quality-studio", "ai.json");
    }

    public static AiSettings load() {
        AiSettings s = new AiSettings();
        try {
            if (Files.exists(file())) s = M.readValue(file().toFile(), AiSettings.class);
        } catch (IOException ignored) {
            // unreadable settings: defaults (model off)
        }
        String p = System.getProperty("ai.url");
        if (p != null && !p.isBlank()) s.url = p;
        String m = System.getProperty("ai.model");
        if (m != null && !m.isBlank()) s.model = m;
        if (System.getProperty("ai.url") != null || System.getProperty("ai.model") != null) s.enabled = true;   // an explicit start option wins
        return s;
    }

    public void save() throws IOException {
        Files.createDirectories(file().toAbsolutePath().getParent());
        M.writeValue(file().toFile(), this);
    }

    /** Light checks for what the user typed. */
    public void validate() {
        if (url == null || !url.matches("(?i)^https?://[^\\s/]+.*")) throw new IllegalArgumentException("The model address must look like http://host:11434");
        url = url.replaceAll("/+$", "");
        if (model == null || model.isBlank()) model = "auto";
        if (timeoutSeconds < 20 || timeoutSeconds > 900) throw new IllegalArgumentException("Timeout must be between 20 and 900 seconds");
        if (connectTimeoutMs < 0 || connectTimeoutMs > 30000) throw new IllegalArgumentException("Connect timeout must be 0 (automatic) or up to 30000 ms");
    }
}
