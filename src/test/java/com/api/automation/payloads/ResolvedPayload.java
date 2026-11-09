package com.api.automation.payloads;

public class ResolvedPayload {

    /** How a generated negative test is sent. PLAIN_BODY = unencrypted body on an encrypted endpoint, IDOR_B = sent by the second IDOR user. */
    public enum Mode { NORMAL, NO_SESSION, TAMPER_ENCRYPTION, WRONG_METHOD, TAMPER_TOKEN, TAMPER_CSRF, PLAIN_BODY, IDOR_B }

    private final String label;   // valid / negative-1 / case-2 / default / neg-03
    private final String body;    // actual JSON body

    // ── generated negative tests (TC047_01, TC047_02 ...) ──
    private String negCode;           // "01", "02" ...
    private String negCategory;       // NOT_EXISTING, WRONG_TYPE ...
    private String negTitle;          // "Not-existing record"
    private String negChange;         // "cmId = 999999999"
    private String negExpected;       // expected behaviour in words
    private String endpointOverride;  // for query-parameter negatives / wrong method
    private String methodOverride;
    private Mode mode = Mode.NORMAL;
    private boolean verifyPositive;   // e.g. "valid login still works" after a failed attempt

    public ResolvedPayload(String label, String body) {
        this.label = label;
        this.body = body;
    }

    public static ResolvedPayload negative(String code, String category, String title, String change,
                                           String expected, String body) {
        ResolvedPayload p = new ResolvedPayload("neg-" + code, body);
        p.negCode = code;
        p.negCategory = category;
        p.negTitle = title;
        p.negChange = change;
        p.negExpected = expected;
        return p;
    }

    public ResolvedPayload withEndpoint(String endpoint) { this.endpointOverride = endpoint; return this; }
    public ResolvedPayload withMethod(String method) { this.methodOverride = method; return this; }
    public ResolvedPayload withMode(Mode mode) { this.mode = mode; return this; }
    public ResolvedPayload asVerifyPositive() { this.verifyPositive = true; return this; }

    public String getLabel() { return label; }
    public String getBody() { return body; }
    public String getNegCode() { return negCode; }
    public String getNegCategory() { return negCategory; }
    public String getNegTitle() { return negTitle; }
    public String getNegChange() { return negChange; }
    public String getNegExpected() { return negExpected; }
    public String getEndpointOverride() { return endpointOverride; }
    public String getMethodOverride() { return methodOverride; }
    public Mode getMode() { return mode; }
    public boolean isVerifyPositive() { return verifyPositive; }

    /** A generated negative test (TC047_01 ...). */
    public boolean isGeneratedNegative() {
        return negCode != null && !verifyPositive;
    }

    @Override
    public String toString() {
        return negCode != null ? negCode + " " + negTitle : label;   // short name in IntelliJ / Maven test lists
    }

    /** negative-* / invalid-* payloads (and generated negatives) are expected to be rejected by the API. */
    public boolean isNegative() {
        if (isGeneratedNegative()) return true;
        String l = label == null ? "" : label.toLowerCase();
        return l.startsWith("negative") || l.startsWith("invalid");
    }
}
