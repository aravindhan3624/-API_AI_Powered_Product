package com.api.automation.otp;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.api.automation.config.ConfigManager;
import com.api.automation.dashboard.LiveDashboard;

/**
 * Asks the tester for an OTP and blocks until they answer.
 *
 *  - ONE popup at a time: the dashboard modal when the dashboard is open in a browser,
 *    otherwise the desktop popup (otp.prompt.ui=auto). Always on top of other windows.
 *  - Countdown otp.timeout.seconds (default 90). Nobody answers in time -> the popup closes and
 *    the default OTP otp.default.value (default 000000) is sent, so the run never hangs.
 *  - CI / headless: pass -Dotp=123456
 */
public final class OtpService {

    /** Re-fires the last OTP-generation request. Returns the server message. */
    public interface ResendAction {
        String resend() throws Exception;
    }

    private OtpService() {}

    public static int timeoutSeconds() {
        return ConfigManager.getInt("otp.timeout.seconds", 90);
    }

    public static String defaultOtp() {
        return ConfigManager.get("otp.default.value", "000000");
    }

    /** synchronized: never more than one OTP window at a time. */
    public static synchronized OtpResult obtain(OtpRequest req, ResendAction resendAction) {

        String preset = System.getProperty("otp");
        if (preset != null && !preset.isBlank()) {
            System.out.println("🔐 OTP taken from -Dotp");
            return OtpResult.submit(preset, "-Dotp");
        }
        req.timeoutSeconds = timeoutSeconds();
        req.defaultOtp = defaultOtp();

        // auto (default) = ONE popup only: the dashboard modal when the dashboard is open in a
        // browser, otherwise the desktop popup.  both = show both.  desktop / browser = force one.
        String ui = ConfigManager.get("otp.prompt.ui", "auto").toLowerCase();
        boolean headless = GraphicsEnvironment.isHeadless();
        boolean browser;
        boolean desktop;
        if (ui.equals("auto")) {
            // the desktop popup stays on top of EVERY window (IntelliJ, browser, Excel...), so the tester sees it
            // wherever they are; the browser modal is only used when no desktop is available (headless / CI)
            desktop = !headless;
            browser = !desktop && LiveDashboard.isRunning() && waitForBrowser(3000);
        } else {
            desktop = (ui.equals("both") || ui.equals("desktop")) && !headless;
            browser = (ui.equals("both") || ui.equals("browser")) && LiveDashboard.isRunning();
        }

        if (!desktop && !browser) {
            System.out.println("🔐 No OTP screen available (headless) - default OTP " + req.defaultOtp + " used");
            return OtpResult.timeoutDefault(req.defaultOtp, 0);
        }

        printBanner(req);

        req.where = browser ? "browser" : "desktop";
        PendingOtp pending = new PendingOtp(req);
        // the console always knows about the OTP: a modal when it is the input screen, otherwise a small note
        if (LiveDashboard.isRunning()) LiveDashboard.showOtp(pending);
        OtpDialog dialog = desktop ? OtpDialog.open(pending) : null;
        if (desktop) Chime.play();        // soft notification sound, once

        OtpResult result;
        try {
            // +1 s so the countdown visibly reaches 0 before the popup closes
            result = pending.future.get(req.timeoutSeconds + 1L, TimeUnit.SECONDS);
            if (result.action == OtpResult.Action.RESEND) {
                result = resend(req, resendAction);
            }
        } catch (TimeoutException e) {
            result = OtpResult.timeoutDefault(req.defaultOtp, req.timeoutSeconds);
            pending.complete(result);
        } catch (Exception e) {
            result = OtpResult.timeoutDefault(req.defaultOtp, req.timeoutSeconds);
        } finally {
            LiveDashboard.clearOtp(pending);
            if (dialog != null) dialog.close();
        }

        LiveDashboard.log(result.enteredByTester() ? "INFO" : "WARN", req.tcId,
                result.enteredByTester() ? "OTP entered via " + result.source
                        : result.defaulted ? "⏱ " + result.reason
                        : "OTP skipped - " + result.reason);
        System.out.println(result.enteredByTester() ? "🔐 OTP entered" : "🔐 " + (result.reason.isEmpty() ? "OTP skipped" : result.reason));
        return result;
    }

    /** Resend button (kept for projects that use it): fire the generation again, then ask once more. */
    private static OtpResult resend(OtpRequest req, ResendAction resendAction) {
        OtpRequest next = req.next();
        try {
            next.serverMessage = resendAction.resend();
            OtpContext.Generation g = OtpContext.last();
            if (g != null) {
                next.channel = g.channel;
                next.target = g.target;
                next.targetLabel = g.targetLabel;
            }
        } catch (Exception e) {
            next.error = "Resend failed: " + e.getMessage();
        }
        next.canResend = false;
        return obtain(next, resendAction);
    }

    /** True when a browser has the dashboard open (waits briefly for it to connect). */
    private static boolean waitForBrowser(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (LiveDashboard.isClientConnected()) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return LiveDashboard.isClientConnected();
    }

    private static void printBanner(OtpRequest req) {
        System.out.println("\n========= OTP REQUIRED =========");
        System.out.println("TC_ID    : " + req.tcId + "  (" + req.apiName + ")");
        if (req.ovd != null) System.out.println("OVD      : " + req.ovdLabel + " " + req.ovd);
        System.out.println("SENT TO  : " + req.targetLabel + " → " + req.target);
        if (req.error != null) System.out.println("NOTE     : " + req.error);
        System.out.println("Enter it in the popup within " + req.timeoutSeconds + " s (then default " + req.defaultOtp + " is used)");
        System.out.println("================================");
    }
}
