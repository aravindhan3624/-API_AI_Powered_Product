package com.api.automation.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Small fluent helper used inside pages:
 *
 *   return new Checks(r)
 *       .equalsAny("Status", "Status", "1")
 *       .notBlank("PAN number", "msg.panNumber")
 *       .result(summary, r.get("msg"));
 */
public class Checks {

    private final ApiResponse r;
    private final List<Check> checks = new ArrayList<>();

    public Checks(ApiResponse r) {
        this(r, true);
    }

    protected Checks(ApiResponse r, boolean requireJson) {
        this.r = r;
        if (requireJson && r.json == null) {
            checks.add(new Check("Response is JSON", "JSON body", abbreviate(r.body), false));
        }
    }

    /** For non-JSON responses (images, files). */
    public static class Binary extends Checks {
        public Binary(ApiResponse r) {
            super(r, false);
        }
    }

    /** Value at path equals one of the allowed values (case-insensitive). */
    public Checks equalsAny(String name, String path, String... allowed) {
        String actual = r.get(path);
        boolean pass = false;
        for (String a : allowed) {
            if (a.equalsIgnoreCase(actual == null ? "null" : actual)) pass = true;
        }
        checks.add(new Check(name, String.join(" / ", allowed), str(actual), pass));
        return this;
    }

    /** Value at path contains text (case-insensitive). */
    public Checks contains(String name, String path, String text) {
        String actual = r.get(path);
        boolean pass = actual != null && actual.toLowerCase().contains(text.toLowerCase());
        checks.add(new Check(name, "contains '" + text + "'", str(actual), pass));
        return this;
    }

    /** Value at path is present and not blank. */
    public Checks notBlank(String name, String path) {
        String actual = r.get(path);
        checks.add(new Check(name, "present", str(actual), actual != null));
        return this;
    }

    /** Value at path does NOT contain any of the words (case-insensitive). */
    public Checks notContainsAny(String name, String path, String... words) {
        String actual = r.get(path);
        String hit = null;
        if (actual != null) {
            for (String w : words) {
                if (actual.toLowerCase().contains(w.toLowerCase())) { hit = w; break; }
            }
        }
        checks.add(new Check(name, "no '" + String.join("/", words) + "'", str(actual), hit == null));
        return this;
    }

    /** Response value equals the value sent in the request (e.g. same PAN / DL number). */
    public Checks sameAsRequest(String name, String responsePath, String requestPath) {
        String sent = r.req(requestPath);
        String got = r.get(responsePath);
        boolean pass = sent == null || (got != null && got.equalsIgnoreCase(sent));
        checks.add(new Check(name, sent == null ? "(not sent)" : sent, str(got), pass));
        return this;
    }

    /** Any custom condition. */
    public Checks that(String name, String expected, String actual, boolean pass) {
        checks.add(new Check(name, expected, str(actual), pass));
        return this;
    }

    public List<Check> list() {
        return checks;
    }

    /**
     * @param summary       one line for "Actual Result" (e.g. "PAN EXISTING AND VALID")
     * @param errorMessage  message from the API to show when a check fails (may be null)
     */
    public PageResult result(String summary, String errorMessage) {
        List<Check> failed = checks.stream().filter(c -> !c.pass).collect(Collectors.toList());
        if (failed.isEmpty()) {
            return new PageResult(true, "", clean(summary), checks);
        }
        String reason = errorMessage != null && !errorMessage.isBlank() && !errorMessage.startsWith("{")
                ? errorMessage
                : failed.get(0).name + " - expected " + failed.get(0).expected + " but was " + failed.get(0).actual;
        // On failure the "actual result" is what the API said, not a half-empty summary
        return new PageResult(false, reason, reason, checks);
    }

    /** Drops " | xxx null" parts of a summary when a field was not returned. */
    private static String clean(String summary) {
        if (summary == null) return "";
        String s = summary.replaceAll("\\s*\\|\\s*[^|]*\\bnull\\b[^|]*", "").trim();
        return s.endsWith(": null") ? s.substring(0, s.length() - 6) : s;
    }

    private static String str(String v) {
        return v == null ? "null" : abbreviate(v);
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
