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
    }
}
