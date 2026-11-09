package com.api.automation.listeners;

import java.io.File;

import org.testng.ISuite;
import org.testng.ISuiteListener;

import com.api.automation.config.ConfigManager;
import com.api.automation.dashboard.LiveDashboard;

/** Suite level setup: clean Allure results once, start / stop the live dashboard. */
public class FrameworkListener implements ISuiteListener {

    @Override
    public void onStart(ISuite suite) {
        com.api.automation.report.QuietLogging.init();
        cleanAllureResults();
        LiveDashboard.start();
    }

    @Override
    public void onFinish(ISuite suite) {
        if (!LiveDashboard.isRunning()) return;
        // screen recording: the console takes the final screenshot and stops the recording after the report is ready
        LiveDashboard.awaitRecordingStop(ConfigManager.getInt("screen.recording.finish.wait.seconds", 90));
        int hold = ConfigManager.getInt("dashboard.hold.seconds", 8);
        if (hold > 0) {
            System.out.println("🖥  Dashboard stays live for " + hold + " s: " + LiveDashboard.url());
            try {
                Thread.sleep(hold * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LiveDashboard.stop();
    }

    // ── CLEAN ALLURE RESULTS (once per suite, so several projects can run together) ──
    private static void cleanAllureResults() {
        try {
            File folder = new File(System.getProperty("user.dir"), "target/allure-results");
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            System.out.println("✅ Old Allure results cleared");
        } catch (Exception e) {
            System.out.println("Failed to clean Allure results: " + e.getMessage());
        }
    }
}
