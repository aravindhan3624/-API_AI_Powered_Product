package com.api.automation.utils;

import java.util.Arrays;
import java.util.function.Supplier;

import com.api.automation.config.ConfigManager;

import io.restassured.response.Response;

public class RetryUtil {

    /**
     * Retries only on HTTP 5xx and only for methods listed in retry.methods (default GET).
     * Banking POSTs (OTP generation, account creation...) are never re-sent automatically.
     * The last response is always returned so the test can report it.
     */
    public static Response execute(String method, boolean neverRetry, Supplier<Response> request) {

        boolean retryable = !neverRetry && Arrays.stream(ConfigManager.get("retry.methods", "GET").split(","))
                .map(String::trim)
                .anyMatch(m -> m.equalsIgnoreCase(method));

        int attempts = retryable ? 1 + Math.max(0, ConfigManager.getInt("retry.count", 2)) : 1;
        long waitMs = ConfigManager.getInt("retry.wait.ms", 1500);

        Response res = null;
        for (int i = 1; i <= attempts; i++) {
            res = request.get();
            if (res.getStatusCode() < 500 || i == attempts) {
                return res;
            }
            System.out.println("⚠ HTTP " + res.getStatusCode() + " - retry " + i + "/" + (attempts - 1));
            try {
                Thread.sleep(waitMs * i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return res;
            }
        }
        return res;
    }

    /** Kept for backward compatibility. */
    public static Response executeWithRetry(Supplier<Response> request, int maxRetry) {
        Response res = null;
        for (int i = 0; i < Math.max(1, maxRetry); i++) {
            res = request.get();
            if (res.getStatusCode() < 500) return res;
        }
        return res;
    }
}
