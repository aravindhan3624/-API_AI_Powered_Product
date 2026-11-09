package com.api.product.core.engine;

import java.util.regex.Pattern;

/** Masks secret-looking JSON fields and bearer tokens before evidence is stored or shown. */
public final class Redactor {

    private static final Pattern FIELD = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|secret|token|accessToken|refreshToken|apikey|api_key|otp|authorization)\"\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer[ \\t]+)[A-Za-z0-9._\\-]+");

    private Redactor() {}

    public static String mask(String s) {
        if (s == null) return null;
        String out = FIELD.matcher(s).replaceAll("$1\"***\"");
        return BEARER.matcher(out).replaceAll("$1***");
    }
}
