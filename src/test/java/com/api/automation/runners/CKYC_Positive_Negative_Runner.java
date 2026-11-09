package com.api.automation.runners;

/**
 * CKYC - POSITIVE + NEGATIVE tests in one report (RunMode.ALL, the default).
 * Negatives follow each positive (TC047_01, TC047_02 ...) while negative.enabled=true in config.properties.
 * Only one type:  CKYC_Positive_Runner  /  CKYC_Negative_Runner
 */
public class CKYC_Positive_Negative_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "CKYC";
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return true;
    }
}
