package com.api.automation.runners;

/** Rajkot Nagarik Sahakari Bank - run this class (or suites/testng-RNSB.xml). */
public class RNSB_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "RNSB";
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
