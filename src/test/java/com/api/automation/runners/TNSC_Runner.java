package com.api.automation.runners;

/** Tamil Nadu State Apex Co-op Bank - run this class (or suites/testng-TNSC.xml). */
public class TNSC_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "TNSC";
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
