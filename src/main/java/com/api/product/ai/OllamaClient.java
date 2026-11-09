package com.api.product.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional local language model through Ollama (http://localhost:11434). No account, no API key, no internet once the
 * model is downloaded. The assistant never depends on it: it only rephrases answers the rule engine already computed.
 *
 * Model choice: -Dai.model=auto (default) picks the best installed model that this PC can run
 * (see {@link HardwareProfile}); -Dai.model=name forces one. -Dai.llm=off disables the model completely.
 */
public final class OllamaClient {

    private static final ObjectMapper M = new ObjectMapper();

    private volatile String base;
    private volatile String configured;
    private volatile AiSettings settings;
    private final HardwareProfile hw;
    /** One request at a time: a modest PC must stay responsive. */
    private final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(1);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private volatile long checkedAt;
    private volatile boolean up;
    private volatile String resolved;
    private volatile List<String> installed = new ArrayList<>();

    public OllamaClient(String base, String model) {
        this(base, model, HardwareProfile.detect());
    }

    public OllamaClient(String base, String model, HardwareProfile hw) {
        this.base = base;
        this.configured = model;
        this.hw = hw;
    }

    public static OllamaClient fromProperties() {
        AiSettings s = AiSettings.load();
        OllamaClient c = new OllamaClient(s.url, s.model);
        c.settings = s;
        return c;
    }

    public AiSettings settings() {
        return settings;
    }

    /** Applies new settings (address, model, switch) without restarting Studio. */
    public void apply(AiSettings s) {
        this.settings = s;
        this.base = s.url;
        this.configured = s.model;
        this.checkedAt = 0;
        this.up = false;
        this.resolved = null;
    }

    /** True when the user has switched the model on and nothing in the start options turned it off. */
    public boolean switchedOn() {
        return (settings == null || settings.enabled) && enabled();
    }

    public HardwareProfile hardware() {
        return hw;
    }

    /** The model that is (or would be) used: the resolved installed model, else the recommendation for this PC. */
    public String model() {
        available();
        return resolved != null ? resolved : ("auto".equalsIgnoreCase(configured) ? hw.recommendedModel() : configured);
    }

    public List<String> installedModels() {
        available();
        return installed;
    }

    public boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("ai.llm", "auto")) && (settings == null || settings.enabled);
    }

    /** True when Ollama answers and a suitable model is installed (re-checked every 30 s). */
    public boolean available() {
        if (!enabled()) return false;
        long now = System.currentTimeMillis();
        if (now - checkedAt < 30_000) return up;
        checkedAt = now;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/tags"))
                    .timeout(Duration.ofMillis(1200)).GET().build(), HttpResponse.BodyHandlers.ofString());
            List<String> names = new ArrayList<>();
            if (r.statusCode() == 200) {
                for (JsonNode m : M.readTree(r.body()).path("models")) names.add(m.path("name").asText());
            }
            installed = names;
            resolved = pick(names);
            up = resolved != null;
        } catch (Exception e) {
            installed = new ArrayList<>();
            resolved = null;
            up = false;
        }
        return up;
    }

    private String pick(List<String> names) {
        if (names.isEmpty()) return null;
        if (!"auto".equalsIgnoreCase(configured)) {
            for (String n : names) if (n.equalsIgnoreCase(configured) || n.startsWith(configured)) return n;
            return null;
        }
        String best = null;
        int bestTier = -1;
        for (String n : names) {
            int t = HardwareProfile.tierOf(n);
            if (t >= 0 && t <= hw.tier && t > bestTier) {
                best = n;
                bestTier = t;
            }
        }
        if (best != null) return best;
        // an unknown model family the user installed: use it unless it is clearly a large catalog model this PC cannot run
        for (String n : names) if (HardwareProfile.tierOf(n) < 0 && !n.toLowerCase().contains("embed")) return n;
        return null;
    }

    /** @return the model text, or null on any problem (callers then keep the rule-engine answer) */
    public String chat(String system, String user, int timeoutSeconds) {
        return chat(system, user, timeoutSeconds, 400, false);
    }

    /** JSON-only answer (Ollama format=json), used for test ideas. */
    public String chatJson(String system, String user, int timeoutSeconds, int maxTokens) {
        return chat(system, user, timeoutSeconds, maxTokens, true);
    }

    private String chat(String system, String user, int timeoutSeconds, int maxTokens, boolean json) {
        if (!available()) return null;
        boolean got = false;
        try {
            got = gate.tryAcquire(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!got) return null;
            ObjectNode body = M.createObjectNode();
            body.put("model", resolved);
            body.put("stream", false);
            if (json) body.put("format", "json");
            body.put("keep_alive", "2m");
            ObjectNode opt = body.putObject("options");
            opt.put("temperature", 0.1);
            opt.put("num_predict", maxTokens);
            if (settings == null || settings.lowPower) {
                opt.put("num_ctx", 2048);
                opt.put("num_thread", Math.max(1, Math.min(4, hw.cores / 2)));
            }
            ArrayNode msgs = body.putArray("messages");
            msgs.addObject().put("role", "system").put("content", system);
            msgs.addObject().put("role", "user").put("content", user);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            String t = M.readTree(r.body()).path("message").path("content").asText("");
            return t.isBlank() ? null : t.trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (got) gate.release();
        }
    }
}
