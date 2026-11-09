package com.api.automation.runners;

/** Public dummyjson.com API - quick smoke test of the framework itself (no bank VPN needed). */
public class DEMO_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "DEMO";
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
