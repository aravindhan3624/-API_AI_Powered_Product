package com.api.automation.runners;

/** Surat People's Co-op Bank - run this class (or suites/testng-SPCBL.xml). */
public class SPCBL_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "SPCBL";
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
