package com.api.automation.negative;

import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;

/**
 * What kind of API a request is, so the VAPT rules only apply where they mean something:
 *   GET_BY_ID    one record by its id (getUserById, details, view of one record)
 *   SEARCH_LIST  lists / search / reports / queues / dashboards (filters are optional)
 *   CREATE / UPDATE / DELETE   state-changing APIs
 *
 * Decided from the endpoint name; override per API in config.properties, e.g.
 *   api.kind.overrides=getPendingDvsView:SEARCH_LIST,getCustomer:GET_BY_ID
 */
public enum ApiKind {
    GET_BY_ID, SEARCH_LIST, CREATE, UPDATE, DELETE;

    private static final Pattern DELETE_P = Pattern.compile("(?i)(delete|remove|purge)");
    private static final Pattern UPDATE_P = Pattern.compile(
            "(?i)(update|edit|modify|approve|reject|confirm|assign|reset|change|archive|unlock|lock|activate|deactivate|toggle|mark|cancel)");
    private static final Pattern CREATE_P = Pattern.compile(
            "(?i)(save|create|add|insert|upload|push|submit|generate|send|initiate|register|import|process|verify|validate|compare|extract)");
    private static final Pattern LIST_P = Pattern.compile(
            "(?i)(list|search|pending|history|queue|report|dashboard|all|summary|count|stats|statistics|filter|lookup|recent|my)");
    private static final Pattern BY_ID_P = Pattern.compile("(?i)(by_?id|by_?no|detail|view|single|profile)");
    /** System-wide settings: changing userId here is not IDOR (no object belongs to another user). */
    private static final Pattern CONFIG_P = Pattern.compile("(?i)(config|setting|smtp|twofactor|2fa|parameter|preference)");

    public boolean writes() {
        return this == CREATE || this == UPDATE || this == DELETE;
    }

    public static ApiKind of(String method, String endpoint) {
        String name = lastSegment(endpoint);
        String ov = override(name);
        if (ov != null) return valueOf(ov);
        String m = method == null ? "" : method.trim().toUpperCase();
        if ("DELETE".equals(m) || DELETE_P.matcher(name).find()) return DELETE;
        if ("PUT".equals(m) || "PATCH".equals(m) || UPDATE_P.matcher(name).find()) return UPDATE;
        boolean read = name.matches("(?i)^(get|fetch|find|load|read|view|search|list|me|check|download).*");
        if (!read && CREATE_P.matcher(name).find()) return CREATE;
        if (LIST_P.matcher(name).find()) return SEARCH_LIST;
        if (BY_ID_P.matcher(name).find()) return GET_BY_ID;
        return SEARCH_LIST;
    }

    public static boolean isSystemConfig(String endpoint) {
        return CONFIG_P.matcher(lastSegment(endpoint)).find();
    }

    /** "/ckycBackend/user/getUserById?x=1" -> "getUserById" */
    public static String lastSegment(String endpoint) {
        String p = path(endpoint);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** endpoint without scheme, host and query: "https://h/a/b?x=1" -> "/a/b" */
    public static String path(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        e = e.replaceFirst("^https?://[^/]+", "");
        int q = e.indexOf('?');
        return q >= 0 ? e.substring(0, q) : e;
    }

    private static String override(String name) {
        String all = ConfigManager.get("api.kind.overrides", "");
        if (all == null || all.isBlank()) return null;
        for (String part : all.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2 && name.equalsIgnoreCase(kv[0].trim())) {
                try {
                    return valueOf(kv[1].trim().toUpperCase()).name();
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
