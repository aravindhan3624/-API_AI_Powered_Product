package com.api.automation.utils;

public class TestDecision {

    public final TestOutcome outcome;
    public final String reason;

    public TestDecision(TestOutcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    public static TestDecision pass() {
        return new TestDecision(TestOutcome.PASS, "");
    }

    public static TestDecision fail(String reason) {
        return new TestDecision(TestOutcome.FAIL, reason);
    }

    public static TestDecision skip(String reason) {
        return new TestDecision(TestOutcome.SKIP, reason);
    }
}