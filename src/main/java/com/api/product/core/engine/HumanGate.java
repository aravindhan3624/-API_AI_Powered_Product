package com.api.product.core.engine;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JOptionPane;

/**
 * Human-in-the-loop step (OTP / CAPTCHA). The run pauses and a person enters the value; nothing is read from responses.
 * In Studio (-Dotp.web=true) the value is typed into a box on the Live run page; on the command line a desktop popup is used.
 * Order: -Dvar.OTP=123456 (handled by the runner) -> web box or popup -> null (test is reported BLOCKED).
 */
public final class HumanGate {

    public static final class Pending {
        public final String project, testId, api, url;
        public final long since = System.currentTimeMillis();
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String value;

        Pending(String project, String testId, String api, String url) {
            this.project = project;
            this.testId = testId;
            this.api = api;
            this.url = url;
        }
    }

    private static volatile Pending pending;

    private HumanGate() {}

    public static boolean available() {
        return Boolean.getBoolean("otp.web") || (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("otp.dialog.off"));
    }

    /** The step that is waiting for a person right now, or null. */
    public static Pending pending() {
        return pending;
    }

    /** Called by the Studio page: the typed value (null or blank = skip this step). */
    public static boolean submit(String value) {
        Pending p = pending;
        if (p == null) return false;
        p.value = value == null || value.isBlank() ? null : value.trim();
        p.latch.countDown();
        return true;
    }

    /** @return the typed value, or null when cancelled / not available / not entered within 5 minutes */
    public static String ask(String project, String testId, String api, String url) {
        if (!available()) return null;
        if (Boolean.getBoolean("otp.web")) {
            Pending p = new Pending(project, testId, api, url);
            pending = p;
            try {
                p.latch.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending = null;
            }
            return p.value;
        }
        String msg = "<html><b>" + esc(project) + "</b> - " + esc(testId) + " " + esc(api)
                + "<br>An OTP was sent for this step. Enter it to continue.<br><small>" + esc(url) + "</small></html>";
        Object v = JOptionPane.showInputDialog(null, msg, "OTP required", JOptionPane.QUESTION_MESSAGE, null, null, "");
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
