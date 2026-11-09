package com.api.product.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Judging rules per category: expect.&lt;CAT&gt;=200,201 / why.&lt;CAT&gt; / owasp.&lt;CAT&gt;.
 * Same key format as src/test/resources/rules/api-test-rules.properties, so the existing rule pack is reused as is.
 */
public final class RulePack {

    private final Properties p = new Properties();
    public final String version;

    private RulePack(String version) {
        this.version = version;
    }

    public static RulePack load(Path file) throws IOException {
        RulePack r = new RulePack(file.getFileName() + "@" + Files.getLastModifiedTime(file).toMillis());
        try (InputStream in = Files.newInputStream(file)) {
            r.p.load(in);
        }
        return r;
    }

    public static RulePack classpath(String resource) throws IOException {
        RulePack r = new RulePack("classpath:" + resource);
        try (InputStream in = RulePack.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Rule pack not found: " + resource);
            r.p.load(in);
        }
        return r;
    }

    /** Expected HTTP codes for the category; empty when the pack has no rule for it. */
    public List<Integer> expected(String category) {
        List<Integer> out = new ArrayList<>();
        String v = p.getProperty("expect." + category);
        if (v == null) return out;
        for (String s : v.split(",")) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // skip junk tokens
            }
        }
        return out;
    }

    public String why(String category) {
        return p.getProperty("why." + category, "");
    }

    public String owasp(String category) {
        String v = p.getProperty("owasp." + category, "");
        return "-".equals(v) ? "" : v;
    }
}
