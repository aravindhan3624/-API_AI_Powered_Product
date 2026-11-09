package com.api.automation.otp;

import java.util.concurrent.CompletableFuture;

/**
 * One open OTP prompt, shared by the desktop popup and the browser dashboard.
 * The countdown lives here, so "Retry" in either UI restarts it for both.
 */
public class PendingOtp {

    public final OtpRequest request;
    public final CompletableFuture<OtpResult> future = new CompletableFuture<>();
    private volatile long expiresAt;

    public PendingOtp(OtpRequest request) {
        this.request = request;
        restart();
    }

    /** Start a fresh countdown (Retry button). */
    public void restart() {
        expiresAt = System.currentTimeMillis() + request.timeoutSeconds * 1000L;
    }

    /** Close button: jump straight to the "time expired / retry" state. */
    public void expireNow() {
        expiresAt = System.currentTimeMillis();
    }

    public long remainingMs() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    public boolean isExpired() {
        return remainingMs() == 0;
    }

    public boolean complete(OtpResult result) {
        return future.complete(result);
    }

    /** Accepts 4-30 letters/digits (normal OTPs, or a reference id some UAT APIs expect). */
    public static String validate(String otp) {
        if (otp == null || otp.trim().isEmpty()) return "Please enter the OTP";
        if (!otp.trim().matches("[A-Za-z0-9]{4,30}")) return "OTP should be 4 or more letters/digits";
        return null;
    }
}
