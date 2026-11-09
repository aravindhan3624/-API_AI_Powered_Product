package com.api.automation.runners;

/** CKYC - positive tests only (report: Reports_CKYC_Positive_<date>_<time>.xlsx). CKYC_Positive_Negative_Runner runs both together. */
public class CKYC_Positive_Runner extends BaseApiRunner {

    @Override
    protected String project() {
        return "CKYC";
    }

    @Override
    protected RunMode runMode() {
        return RunMode.POSITIVE;
    }

    /** Screen recording of the Chrome window: false = off (default). Set to true to record runs of THIS runner. */
    @Override
    protected boolean screenRecording() {
        return false;
    }
}
