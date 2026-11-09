package com.api.automation.otp;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.api.automation.config.ConfigManager;

/**
 * Remembers the last OTP-generation call (who the OTP was sent to, and how to resend it),
 * so the verify step can show "OTP sent to Mobile 9677755667" and offer "Resend OTP".
 *
 * Where the OTP went is taken from, in order:
 *   1. the generation RESPONSE (e.g. "phone":"919677755667")
 *   2. the generation REQUEST - i.e. your test data (PhoneNumber / mobile / email / CST_ID / Aadhaar)
 *   3. config.properties tester.mobile / tester.email (last fallback only)
 */
public final class OtpContext {

    public static class Generation {
        public String tcId;
        public String apiName;
        public String method;
        public String endpoint;
        public String body;
        public String headers;
        public String extractRule;
        public String channel;
        public String target;
        public String targetLabel;
        public String serverMessage;
        public String ovd;          // OVD number the OTP belongs to (Aadhaar masked)
        public String ovdLabel;     // Aadhaar / PAN / Passport / Voter ID / Driving licence / OVD number
        public long time;
    }

    private static volatile Generation last;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern AADHAAR = Pattern.compile("(?<![0-9])(\\d{12})(?![0-9])");
    private static final Pattern MOBILE = Pattern.compile("^(?:\\+?91)?([6-9]\\d{9})$");

    /** JSON fields / query params that carry the OTP destination. */
    private static final Pattern CONTACT_FIELD = Pattern.compile(
            "[\"?&]?(otpmaskedtarget|maskedtarget|phone|phonenumber|phoneno|mobile|mobileno|mobilenumber|mobile_no|msisdn|email|emailid|email_id|mailid|cst_id)"
                    + "\"?\\s*[:=]\\s*\"?([^\"&,}\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private OtpContext() {}

    public static void record(Generation g, String responseBody) {
        describe(g, responseBody);
        last = g;
    }

    /** Fills target + OVD without making it "the last generation" (used for negative tests). */
    public static void describe(Generation g, String responseBody) {
        g.time = System.currentTimeMillis();
        String[] t = detectTarget(g.endpoint, g.body, responseBody);
        g.channel = t[0];
        g.target = t[1];
        g.targetLabel = t[2];
        String[] o = detectOvd((g.endpoint == null ? "" : g.endpoint) + " " + (g.body == null ? "" : g.body), responseBody);
        if (o != null) {
            g.ovdLabel = o[0];
            g.ovd = o[1];
        }
    }

    /** OVD field name -> label. The request (test data) wins over the response. */
    private static final Pattern OVD_FIELD = Pattern.compile(
            "[\"?&]?(aadhaar(?:no|number|num)?|aadhar(?:no|number|num|refnum)?|uid(?:no|number)?|pan(?:no|number)?|passport(?:no|number)?|"
                    + "voter(?:id|no)?|epic(?:no)?|dl(?:no|number)?|drivinglicen[cs]e(?:no|number)?|ovd(?:no|number|value|id)?|"
                    + "idnumber|idno|documentnumber|docnumber|docno|proofnumber|ckycno|ckycnumber|kycno)"
                    + "\"?\\s*[:=]\\s*\"?([A-Za-z0-9/-]{4,30})",
            Pattern.CASE_INSENSITIVE);

    /** @return {label, value} or null */
    public static String[] detectOvd(String request, String response) {
        for (String text : new String[]{request, response}) {
            if (text == null || text.isBlank()) continue;
            Matcher m = OVD_FIELD.matcher(text);
            while (m.find()) {
                String field = m.group(1).toLowerCase();
                String value = m.group(2);
                if (value.startsWith("$")) continue;
                String label = field.startsWith("aadha") || field.startsWith("uid") ? "Aadhaar"
                        : field.startsWith("pan") ? "PAN"
                        : field.startsWith("passport") ? "Passport"
                        : field.startsWith("voter") || field.startsWith("epic") ? "Voter ID"
                        : field.startsWith("dl") || field.startsWith("driving") ? "Driving licence"
                        : field.startsWith("ckyc") || field.startsWith("kyc") ? "CKYC number"
                        : "OVD number";
                if ("Aadhaar".equals(label) && value.matches("\\d{12}")) value = "XXXX XXXX " + value.substring(8);
                return new String[]{label, value};
            }
        }
        return null;
    }

    public static Generation last() {
        return last;
    }

    public static void clear() {
        last = null;
    }

    /** Is this endpoint an OTP generation / send API? */
    public static boolean isGenerationApi(String endpoint) {
        if (endpoint == null) return false;
        String e = endpoint.split("\\?")[0].toLowerCase();

        String extra = ConfigManager.get("otp.generate.keywords", "");
        boolean configured = Arrays.stream(extra.split(","))
                .map(String::trim).filter(k -> !k.isEmpty())
                .anyMatch(k -> e.contains(k.toLowerCase()));
        if (configured) return true;

        boolean action = e.contains("generate") || e.contains("send") || e.contains("getsms");
        boolean otpish = e.contains("otp") || e.contains("sms") || e.contains("email") || e.contains("mail");
        return action && otpish;
    }

    /** @return {channel, target, label} */
    public static String[] detectTarget(String endpoint, String requestBody, String responseBody) {

        // 1. The API response says where it sent the OTP
        String[] fromResponse = contactIn(responseBody, " (confirmed by API response)");
        if (fromResponse != null) return fromResponse;

        // 2. The request (your test data)
        String request = (endpoint == null ? "" : endpoint) + " " + (requestBody == null ? "" : requestBody);
        String[] fromRequest = contactIn(request, "");
        if (fromRequest != null) return fromRequest;

        String lower = request.toLowerCase();
        if (lower.contains("aadha") || lower.contains("uid")) {
            Matcher m = AADHAAR.matcher(request);
            String masked = m.find() ? "Aadhaar XXXX XXXX " + m.group(1).substring(8) : "Aadhaar-linked mobile";
            return new String[]{"AADHAAR", masked, "Mobile linked to Aadhaar"};
        }

        // any e-mail / mobile value anywhere in the request
        Matcher em = EMAIL.matcher(request);
        if (em.find()) return new String[]{"EMAIL", em.group(), "Email ID"};
        Matcher mob = Pattern.compile("(?<![0-9])(?:91)?([6-9]\\d{9})(?![0-9])").matcher(request);
        if (mob.find()) return new String[]{"MOBILE", mob.group(1), "Mobile number"};

        // 3. Last fallback: config
        boolean mail = lower.contains("mail");
        String cfg = ConfigManager.get(mail ? "tester.email" : "tester.mobile", "");
        return new String[]{mail ? "EMAIL" : "MOBILE", cfg.isEmpty() ? "registered " + (mail ? "email" : "mobile") : cfg,
                (mail ? "Email ID" : "Mobile number") + " (from config)"};
    }

    /** First phone/email-looking field in the text, classified by its value. */
    private static String[] contactIn(String text, String suffix) {
        if (text == null || text.isBlank()) return null;
        Matcher m = CONTACT_FIELD.matcher(text);
        while (m.find()) {
            String value = m.group(2).trim();
            if (m.group(1).toLowerCase().contains("masked") && !value.isEmpty()) {
                // Already masked by the server, e.g. "a***@example.com" or "XXXXXX4521"
                return value.contains("@")
                        ? new String[]{"EMAIL", value, "Email ID" + suffix}
                        : new String[]{"MOBILE", value, "Mobile number" + suffix};
            }
            if (EMAIL.matcher(value).matches()) {
                return new String[]{"EMAIL", value, "Email ID" + suffix};
            }
            Matcher mm = MOBILE.matcher(value);
            if (mm.matches()) {
                return new String[]{"MOBILE", mm.group(1), "Mobile number" + suffix};
            }
        }
        return null;
    }
}
