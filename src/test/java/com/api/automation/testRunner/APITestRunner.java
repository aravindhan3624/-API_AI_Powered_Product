package com.api.automation.testRunner;

import com.api.automation.config.ConfigManager;
import com.api.automation.runners.BaseApiRunner;

/**
 * Generic runner kept for testng.xml: runs the project given by -Dproject=SPCBL,
 * otherwise active.project from config.properties.
 * Prefer the dedicated runners in com.api.automation.runners.
 */
public class APITestRunner extends BaseApiRunner {

    @Override
    protected String project() {
        String p = System.getProperty("project");
        return p != null && !p.isBlank() ? p : ConfigManager.get("active.project", "RNSB");
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
