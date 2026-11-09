package com.api.automation.utils;

public class ApiNameResolver {

    public static String resolve(String endpoint) {

        if (endpoint == null || endpoint.isBlank()) {
            return "API";
        }

        // ✅ 1. Remove query parameters
        String clean = endpoint.split("\\?")[0];

        // ✅ 2. Remove trailing slash if any
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        // ✅ 3. Take only last path segment
        String lastSegment =
                clean.substring(clean.lastIndexOf("/") + 1);

        // ✅ 4. Convert CamelCase to readable words
        return lastSegment
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1 $2")
                .trim();
    }
}