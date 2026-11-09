package com.api.automation.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;

/**
 * Masks customer PII before it is shown on screen, shared with the team or sent to AI.
 *   Aadhaar 641540198005  ->  XXXXXXXX8005
 *   Current OTP value     ->  ******
 */
public final class PiiMasker {

    private static final Pattern AADHAAR = Pattern.compile("(?<![0-9])(\\d{4})[ -]?(\\d{4})[ -]?(\\d{4})(?![0-9])");
    private static final Pattern PAN = Pattern.compile("\\b([A-Z]{3})[ABCFGHLJPT]([A-Z])(\\d{4})([A-Z])\\b");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(?:91)?([6-9]\\d{5})(\\d{4})(?![0-9])");

    private PiiMasker() {}

    // "password": "...", "token": "...", password=... in JSON / query strings
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(\"?(?:password|passwd|pwd|newPassword|oldPassword|token|accessToken|jwtToken|jwt|authToken|csrfToken|csrf_token|"
                    + "clientSecret|responseSecret|secret|apiKey|api_key)\"?\\s*[:=]\\s*\"?)([^\"&,}\\s]+)");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{8,}");

    /** Passwords, tokens and secrets - ALWAYS masked in every report / screen / log. */
    public static String maskSecrets(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = SECRET_FIELD.matcher(text).replaceAll("$1****");
        out = JWT.matcher(out).replaceAll("eyJ****");
        out = BEARER.matcher(out).replaceAll("$1****");
        String pwd = ConfigManager.get("var.password");
        if (pwd != null && pwd.length() >= 4) out = out.replace(pwd, "****");
        return out;
    }

    /** Masking for dashboard / HTML / Allure (Aadhaar/OTP controlled by mask.pii). */
    public static String mask(String text) {
        text = maskSecrets(text);
        if (!ConfigManager.getBool("mask.pii", true)) return text;
        return maskAadhaarAndOtp(text);
    }

    /** Test data shown in the console / saved HTML: numbers (Aadhaar ...) are visible unless mask.pii.testdata=true; passwords and tokens are always masked. */
    public static String maskTestData(String text) {
        text = maskSecrets(text);
        if (!ConfigManager.getBool("mask.pii.testdata", false)) return text;
        return maskAadhaarAndOtp(text);
    }

    /** Masking for the Excel report (Aadhaar/OTP controlled by mask.pii.excel; secrets always). */
    public static String maskForExcel(String text) {
        text = maskSecrets(text);
        if (!ConfigManager.getBool("mask.pii.excel", false)) return text;
        return maskAadhaarAndOtp(text);
    }

    /** Strict masking for anything leaving this machine (cloud AI). */
    public static String maskStrict(String text) {
        if (text == null) return null;
        String out = maskAadhaarAndOtp(text);
        out = PAN.matcher(out).replaceAll("$1XX$3X");
        out = MOBILE.matcher(out).replaceAll("XXXXXX$2");
        out = out.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "xxxx@xxxx");
        return out;
    }

    public static String maskAadhaarAndOtp(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = AADHAAR.matcher(text);
        String out = m.replaceAll("XXXXXXXX$3");

        String otp = GlobalStore.get("OTP");
        if (otp != null && otp.length() >= 4 && otp.length() <= 8) {
            out = out.replace(otp, "******");
        }
        return out;
    }
}
