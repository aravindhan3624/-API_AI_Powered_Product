package com.api.automation.pages;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a page validation. */
public class PageResult {

    public final boolean pass;
    public final String message;        // failure reason (empty on pass)
    public final String actualSummary;  // one-line "Actual Result" for reports
    public final List<Check> checks;

    public PageResult(boolean pass, String message, String actualSummary, List<Check> checks) {
        this.pass = pass;
        this.message = message == null ? "" : message;
        this.actualSummary = actualSummary == null ? "" : actualSummary;
        this.checks = checks == null ? new ArrayList<>() : checks;
    }
}
