package com.api.automation.config;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Reads config.properties.
 *
 * Lookup order for get("base.url") while project RNSB is active:
 *   1. -DRNSB.base.url      (JVM flag, project specific)
 *   2. RNSB.base.url        (config.properties, project specific)
 *   3. -Dbase.url           (JVM flag)
 *   4. base.url             (config.properties)
 */
public class ConfigManager {

    private static final Properties prop = new Properties();

    static {
        String path = System.getProperty("config.file", "src/test/resources/config.properties");
        try (Reader reader = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            prop.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load config file: " + path, e);
        }
    }

    public static String get(String key) {
        String project = ProjectContext.current();
        String value = null;

        if (project != null) {
            value = lookup(project + "." + key);
        }
        if (value == null) {
            value = lookup(key);
        }
        return expand(value);
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    private static String lookup(String key) {
        String sys = System.getProperty(key);
        return sys != null ? sys : prop.getProperty(key);
    }

    private static String expand(String value) {
        if (value != null && value.contains("${user.home}")) {
            value = value.replace("${user.home}", System.getProperty("user.home"));
        }
        // ${ENV:NAME} lets secrets live in environment variables instead of this file
        if (value != null && value.contains("${ENV:")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{ENV:([^}]+)}").matcher(value);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String env = System.getenv(m.group(1).trim());
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(env == null ? "" : env));
            }
            m.appendTail(sb);
            value = sb.toString();
        }
        return value == null ? null : value.trim();
    }
}
