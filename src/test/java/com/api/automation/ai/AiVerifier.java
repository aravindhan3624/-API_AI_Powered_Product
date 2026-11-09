package com.api.automation.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.api.automation.config.ConfigManager;
import com.api.automation.utils.PiiMasker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Second opinion from an LLM: "does the ACTUAL response satisfy the EXPECTED result?"
 *
 *  ai.provider=ollama  -> free, unlimited, local (https://ollama.com, `ollama pull llama3`).
 *                         Data never leaves the PC - recommended for bank data.
 *  ai.provider=openai  -> any OpenAI-compatible endpoint (Groq, OpenRouter, LM Studio...).
 *                         Key read from the env variable named in ai.api.key.env.
 *                         PII is always masked before sending.
 *
 * The rule-based checks decide PASS/FAIL; AI is advisory unless ai.can.fail.test=true.
 */
public final class AiVerifier {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_RESPONSE_CHARS = 4000;

    private static HttpClient http;
    private static Boolean available;       // checked once per project
    private static String unavailableReason = "";

    private static final String SYSTEM_PROMPT =
            "You are a senior QA engineer testing banking KYC / OTP REST APIs. "
                    + "Decide whether the ACTUAL API response satisfies the EXPECTED RESULT. "
                    + "For a NEGATIVE test, PASS means the API correctly rejected the invalid input. "
                    + "Judge the business meaning (status codes, messages, returned details), not formatting. "
                    + "Reply with ONLY a JSON object: {\"verdict\":\"PASS\" or \"FAIL\",\"reason\":\"max 25 words\"}";

    private AiVerifier() {}

    /** Re-check availability when a new project starts. */
    public static synchronized void reset() {
        available = null;
        unavailableReason = "";
    }

    public static boolean enabled() {
        return ConfigManager.getBool("ai.enabled", false);
    }

    public static synchronized boolean isAvailable() {
        if (!enabled()) {
            unavailableReason = "AI disabled (ai.enabled=false)";
            return false;
        }
        if (available == null) {
            available = ping();
            System.out.println(available
                    ? "🤖 AI verifier ready: " + provider() + " / " + model()
                    : "🤖 AI verifier unavailable - " + unavailableReason);
        }
        return available;
    }

    public static String statusText() {
        if (!enabled()) return "AI disabled";
        return isAvailable() ? "AI: " + provider() + " / " + model() : "AI unavailable - " + unavailableReason;
    }

    public static AiVerdict verify(String apiName, String method, String endpoint, boolean negative,
                                   String expectedResult, String requestBody,
                                   int httpStatus, String responseBody) {
        if (!isAvailable()) return AiVerdict.na(unavailableReason);

        try {
            boolean local = "ollama".equalsIgnoreCase(provider());
            String req = local ? PiiMasker.maskAadhaarAndOtp(requestBody) : PiiMasker.maskStrict(requestBody);
            String res = local ? PiiMasker.maskAadhaarAndOtp(responseBody) : PiiMasker.maskStrict(responseBody);
            if (res != null && res.length() > MAX_RESPONSE_CHARS) res = res.substring(0, MAX_RESPONSE_CHARS) + "...(truncated)";

            String user = "API: " + apiName + " (" + method + " " + endpoint.split("\\?")[0] + ")\n"
                    + "TEST TYPE: " + (negative ? "NEGATIVE (invalid input, API must reject it)" : "POSITIVE") + "\n"
                    + "EXPECTED RESULT: " + expectedResult + "\n"
                    + "REQUEST BODY: " + (req == null || req.isBlank() ? "(none)" : req) + "\n"
                    + "HTTP STATUS: " + httpStatus + "\n"
                    + "ACTUAL RESPONSE: " + res;

            String content = local ? callOllama(user) : callOpenAi(user);
            return parse(content);

        } catch (Exception e) {
            return AiVerdict.na("AI call failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    private static String callOllama(String user) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", model());
        body.put("stream", false);
        body.put("format", "json");
        body.putObject("options").put("temperature", 0);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        msgs.addObject().put("role", "user").put("content", user);

        JsonNode res = post(baseUrl() + "/api/chat", body, null);
        return res.path("message").path("content").asText();
    }

    private static String callOpenAi(String user) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", model());
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        msgs.addObject().put("role", "user").put("content", user);

        JsonNode res = post(baseUrl() + "/chat/completions", body, apiKey());
        return res.path("choices").path(0).path("message").path("content").asText();
    }

    private static JsonNode post(String url, ObjectNode body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(ConfigManager.getInt("ai.timeout.seconds", 90)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)));
        if (key != null) b.header("Authorization", "Bearer " + key);

        HttpResponse<String> res = client().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " " + abbreviate(res.body()));
        }
        return M.readTree(res.body());
    }

    private static AiVerdict parse(String content) {
        try {
            String json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1);
            JsonNode n = M.readTree(json);
            String v = n.path("verdict").asText("").trim().toUpperCase();
            if (!v.equals("PASS") && !v.equals("FAIL")) return AiVerdict.na("Unclear AI answer: " + abbreviate(content));
            return new AiVerdict(v, n.path("reason").asText(""));
        } catch (Exception e) {
            return AiVerdict.na("Unclear AI answer: " + abbreviate(content));
        }
    }

    private static boolean ping() {
        try {
            boolean local = "ollama".equalsIgnoreCase(provider());
            if (!local && apiKey() == null) {
                unavailableReason = "set environment variable " + ConfigManager.get("ai.api.key.env", "AI_API_KEY");
                return false;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + (local ? "/api/tags" : "/models")))
                    .timeout(Duration.ofSeconds(3)).GET();
            if (!local) b.header("Authorization", "Bearer " + apiKey());
            HttpResponse<String> res = client().send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                unavailableReason = "HTTP " + res.statusCode() + " from " + baseUrl();
                return false;
            }
            if (local && !res.body().contains("\"" + model()) && !res.body().contains("\"" + model() + ":")) {
                unavailableReason = "model '" + model() + "' not installed - run: ollama pull " + model();
                return false;
            }
            return true;
        } catch (Exception e) {
            unavailableReason = ("ollama".equalsIgnoreCase(provider()) ? "Ollama not running at " : "cannot reach ")
                    + baseUrl();
            return false;
        }
    }

    private static synchronized HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }
        return http;
    }

    private static String provider() {
        return ConfigManager.get("ai.provider", "ollama");
    }

    private static String model() {
        return ConfigManager.get("ai.model", "llama3");
    }

    private static String baseUrl() {
        String u = ConfigManager.get("ai.url", "http://localhost:11434");
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String apiKey() {
        String v = System.getenv(ConfigManager.get("ai.api.key.env", "AI_API_KEY"));
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 150 ? s.substring(0, 150) + "..." : s;
    }
}
