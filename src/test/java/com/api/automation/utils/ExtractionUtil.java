package com.api.automation.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

public class ExtractionUtil {

    /**
     * Extract rule format (Excel "Extract" column):
     *   OTP=result[0].OTP;OTP=OTP;Client_Id=data.Client_Id
     *
     * The same key may be listed several times as fallbacks: the FIRST path that
     * returns a value wins. Values are returned, not stored (the engine decides).
     */
    public static Map<String, String> extract(String normalizedBody, String rule) {
        return extract(normalizedBody, rule, null);
    }

    /**
     * Special paths:
     *   $body            -> the whole response when it is plain text (e.g. a bare JWT token)
     *   $header.Name     -> a response header (e.g. $header.Authorization)
     */
    public static Map<String, String> extract(String normalizedBody, String rule,
                                              java.util.function.Function<String, String> headerLookup) {

        Map<String, String> map = new LinkedHashMap<>();

        if (rule == null || rule.trim().isEmpty() || rule.trim().equalsIgnoreCase("NA")) return map;

        JsonPath json = ResponseHandler.jsonOrNull(normalizedBody);

        for (String pair : rule.split(";")) {

            if (!pair.contains("=")) continue;

            String[] kv = pair.split("=", 2);           // path itself may contain '='
            String key = kv[0].trim();
            String path = kv[1].trim();

            if (key.isEmpty() || path.isEmpty() || map.containsKey(key)) continue;

            String value;
            if (path.equalsIgnoreCase("$body")) {
                String b = normalizedBody == null ? "" : normalizedBody.trim();
                value = (json == null && !b.isEmpty() && !b.equals("EMPTY") && !b.startsWith("<")) ? b : null;
            } else if (path.toLowerCase().startsWith("jsonlist:")) {
                // jsonlist:records.CUSTOMER_ID:10  ->  ["MN05100118","MN05100119",...]  (first N non-empty values)
                // jsonlist:path:max:offset  -> skip the first <offset> values (batches of 10, 20, ...)
                String[] parts = path.substring(9).split(":");
                int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 10;
                int offset = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                value = null;
                try {
                    java.util.List<Object> list = json == null ? null : json.getList(parts[0].trim());
                    if (list != null) {
                        java.util.List<String> vals = new java.util.ArrayList<>();
                        int seen = 0;
                        for (Object o : list) {
                            if (o == null || String.valueOf(o).isBlank()) continue;
                            if (seen++ < offset) continue;
                            if (vals.size() < max) vals.add(String.valueOf(o).trim());
                        }
                        if (!vals.isEmpty()) value = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vals);
                    }
                } catch (Exception e) {
                    value = null;
                }
            } else if (path.toLowerCase().startsWith("$header.")) {
                value = headerLookup == null ? null : headerLookup.apply(path.substring(8));
                if (value != null && value.toLowerCase().startsWith("bearer ")) value = value.substring(7).trim();
            } else {
                value = json == null ? null : ResponseAnalyzer.str(json, path);
            }
            if (value == null) {
                System.out.println("Extraction blank/null for: " + key + " via " + path);
                continue;
            }

            map.put(key, value);
            System.out.println("Extracted " + key + " = " + (key.toLowerCase().matches(".*(token|secret|password|jwt).*") ? "****" : value) + "  (via path: " + path + ")");
        }

        return map;
    }

    /** Backward compatible: extract and store everything in GlobalStore. */
    public static Map<String, String> extractValues(Response response, String rule) {
        Map<String, String> map = extract(ResponseHandler.normalize(response.asString()), rule);
        map.forEach(GlobalStore::put);
        return map;
    }
}
