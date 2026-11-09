package com.api.automation.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.restassured.path.json.JsonPath;

public class ResponseHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Some APIs return JSON wrapped in a string: "{\"response\":{...}}".
     * This returns the real JSON text so it can be validated / extracted.
     */
    public static String normalize(String responseBody) {

        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "EMPTY";
        }

        String res = responseBody.trim();

        // ================= XML / SOAP -> JSON (same Extract / Validate rules) =================
        if (res.startsWith("<") && !res.toLowerCase().startsWith("<!doctype html") && !res.toLowerCase().startsWith("<html")) {
            String json = XmlJson.toJson(res);
            if (json != null) return json;
        }

        // ================= ESCAPED JSON =================
        if (res.startsWith("\"") && res.endsWith("\"")) {
            try {
                String unwrapped = mapper.readValue(res, String.class).trim();
                if (unwrapped.startsWith("{") || unwrapped.startsWith("[")) {
                    System.out.println("⚠ Escaped JSON detected → normalized");
                    return unwrapped;
                }
                return unwrapped;
            } catch (Exception e) {
                return res.substring(1, res.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
        }

        return res;
    }

    public static String detectType(String res) {

        if (res == null || res.equals("EMPTY")) return "EMPTY";

        String lower = res.toLowerCase();

        if (res.startsWith("{") || res.startsWith("[")) return "JSON";
        if (lower.contains("<html") || lower.startsWith("<!doctype html")) return "HTML";
        if (res.startsWith("<")) return "XML";

        return "TEXT";
    }

    /** JsonPath over the normalized body, or null when the body is not JSON. */
    public static JsonPath jsonOrNull(String normalized) {
        if (!"JSON".equals(detectType(normalized))) return null;
        try {
            JsonPath jp = new JsonPath(normalized);
            jp.get("$");               // forces a parse so invalid JSON is caught here
            return jp;
        } catch (Exception e) {
            return null;
        }
    }
}
