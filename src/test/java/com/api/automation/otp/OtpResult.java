package com.api.automation.otp;

public class OtpResult {

    public enum Action { SUBMIT, RESEND, SKIP }

    public final Action action;
    public final String otp;
    public final String source;   // desktop / browser / -Dotp / timeout default
    public final String reason;
    /** true = nobody answered within the time limit, the default OTP (otp.default.value) is used. */
    public final boolean defaulted;

    private OtpResult(Action action, String otp, String source, String reason, boolean defaulted) {
        this.action = action;
        this.otp = otp;
        this.source = source;
        this.reason = reason;
        this.defaulted = defaulted;
    }

    public static OtpResult submit(String otp, String source) {
        return new OtpResult(Action.SUBMIT, otp.trim(), source, "", false);
    }

    /** Time limit reached: continue with the default OTP (e.g. 000000). */
    public static OtpResult timeoutDefault(String otp, int seconds) {
        return new OtpResult(Action.SUBMIT, otp, "timeout default",
                "OTP not entered within " + seconds + " s - default OTP " + otp + " used", true);
    }

    public static OtpResult resend(String source) {
        return new OtpResult(Action.RESEND, null, source, "", false);
    }

    public static OtpResult skip(String reason) {
        return new OtpResult(Action.SKIP, null, "", reason, false);
    }

    /** Typed by a person (popup / dashboard), not -Dotp or the timeout default. */
    public boolean enteredByTester() {
        return action == Action.SUBMIT && !defaulted && !"-Dotp".equals(source);
    }
}
