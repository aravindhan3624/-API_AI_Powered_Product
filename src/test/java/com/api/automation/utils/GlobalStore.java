package com.api.automation.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;

/**
 * Values shared between test steps (extracted from responses) plus built-in variables.
 *
 * Built-in variables usable in Endpoint / Payload / Headers:
 *   ${MOBILE} ${EMAIL} ${TESTER_NAME}  -> from config.properties (tester.*)
 *   ${TXN_ID}     -> unique 10 digit number (new per use)
 *   ${UUID}       -> random UUID
 *   ${TIMESTAMP}  -> epoch millis
 *   ${TODAY}      -> dd/MM/yyyy
 *   ${NOW:yyyyMMddHHmmss}   -> current time in any Java date pattern
 *   ${RANDOM:5}   -> 5 random digits
 *   ${CFG:key}    -> value from config.properties (project key first), e.g. ${CFG:url.pacsapi}
 *   ${ENV:NAME}   -> environment variable
 *   ${BASIC_AUTH:alias} -> base64(user:pass) from config auth.<alias>.username / .password
 */
public class GlobalStore {

    private static final Map<String, String> store = new ConcurrentHashMap<>();
    private static final Pattern VAR = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern JSON_VAR = Pattern.compile("\"\\$\\{json:([A-Za-z0-9_]+)}\"");

    public static void put(String key, String value) {
        if (key == null) return;
        if (value == null) {
            store.remove(key);       // put(key, null) = reset, as before
        } else {
            store.put(key, value);
        }
    }

    public static String get(String key) {
        return store.get(key);
    }

    public static boolean has(String key) {
        String v = store.get(key);
        return v != null && !v.isBlank() && !"null".equalsIgnoreCase(v);
    }

    public static void remove(String key) {
        store.remove(key);
    }

    public static Map<String, String> getAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(store));
    }

    public static void clear() {
        store.clear();
    }

    /** Fallback values used while resolving on this thread (read + cleared by the engine for the report). */
    private static final ThreadLocal<java.util.Set<String>> FALLBACKS = ThreadLocal.withInitial(java.util.LinkedHashSet::new);

    /** Notes like "no 'dvsChkCmId' today, used dvsCmId = 3", then clears them. */
    public static String takeFallbackNotes() {
        java.util.Set<String> s = FALLBACKS.get();
        String r = String.join("; ", s);
        s.clear();
        return r;
    }

    /** Replaces every ${VAR} that has a value; unknown variables are left untouched. ${a|b|c} = first with a value. */
    public static String resolve(String text) {
        if (text == null || !text.contains("${")) return text == null ? "" : text;

        // "${json:ids}" (with the quotes) -> raw JSON value, e.g. ["MN05100118","MN05100119"]
        Matcher jm = JSON_VAR.matcher(text);
        StringBuilder jsb = new StringBuilder();
        while (jm.find()) {
            String v = has(jm.group(1)) ? store.get(jm.group(1)) : null;
            jm.appendReplacement(jsb, Matcher.quoteReplacement(v != null ? v : jm.group(0)));
        }
        jm.appendTail(jsb);
        text = jsb.toString();

        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            boolean optional = key.endsWith("?");          // ${csrf_token?} → "" when not available
            if (optional) key = key.substring(0, key.length() - 1);
            if (key.contains("|") && !key.contains(":")) {  // ${dvsChkCmId|dvsCmId|anyCmId} → first one with a value
                String[] alts = key.split("\\|");
                String value = null;
                for (int i = 0; i < alts.length && value == null; i++) {
                    String a = alts[i].trim();
                    if (has(a)) {
                        value = store.get(a);
                        if (i > 0) FALLBACKS.get().add("no '" + alts[0].trim() + "' today, used " + a + " = " + value);
                    }
                }
                if (value == null && optional) value = "";
                m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
                continue;
            }
            String value = has(key) ? store.get(key) : builtIn(key);
            if (value == null && optional) value = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** First ${VAR} still present after resolving, or null. */
    public static String findUnresolved(String text) {
        if (text == null) return null;
        Matcher m = VAR.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String builtIn(String key) {
        int colon = key.indexOf(':');
        if (colon > 0) {
            String fn = key.substring(0, colon).toUpperCase();
            String arg = key.substring(colon + 1);
            try {
                switch (fn) {
                    case "DATE": {
                        // ${DATE:-30:dd/MM/yyyy} -> date 30 days ago
                        int c2 = arg.indexOf(':');
                        int days = Integer.parseInt(arg.substring(0, c2).trim());
                        return LocalDate.now().plusDays(days).format(DateTimeFormatter.ofPattern(arg.substring(c2 + 1), java.util.Locale.ENGLISH));
                    }
                    case "NOW":
                        return java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern(arg, java.util.Locale.ENGLISH));
                    case "RANDOM": {
                        int n = Math.max(1, Math.min(18, Integer.parseInt(arg.trim())));
                        StringBuilder sb = new StringBuilder();
                        sb.append(ThreadLocalRandom.current().nextInt(1, 10));
                        for (int i = 1; i < n; i++) sb.append(ThreadLocalRandom.current().nextInt(10));
                        return sb.toString();
                    }
                    case "CFG":
                        return ConfigManager.get(arg.trim());
                    case "ENV":
                        return System.getenv(arg.trim());
                    case "BASIC_AUTH": {
                        String user = ConfigManager.get("auth." + arg.trim() + ".username");
                        String pass = ConfigManager.get("auth." + arg.trim() + ".password");
                        if (user == null || pass == null) return null;
                        return java.util.Base64.getEncoder().encodeToString(
                                (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    default:
                        return null;
                }
            } catch (Exception e) {
                System.out.println("⚠ Cannot resolve ${" + key + "}: " + e.getMessage());
                return null;
            }
        }
        switch (key.toUpperCase()) {
            case "MOBILE":      return ConfigManager.get("tester.mobile");
            case "EMAIL":       return ConfigManager.get("tester.email");
            case "TESTER_NAME": return ConfigManager.get("tester.name");
            case "TXN_ID":      return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
            case "UUID":        return UUID.randomUUID().toString();
            case "TIMESTAMP":   return String.valueOf(System.currentTimeMillis());
            case "TODAY":       return LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            default:
                // Project test-data variables in config.properties:  CKYC.var.userId=1  →  ${userId}
                String v = ConfigManager.get("var." + key);
                // a config value may itself use variables, e.g. REQ-${NOW:yyyyMMddHHmmss}-${RANDOM:3}
                return v != null && v.contains("${") && !v.contains("${" + key) ? resolve(v) : v;
        }
    }
}
