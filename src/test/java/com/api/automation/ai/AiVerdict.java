package com.api.automation.ai;

public class AiVerdict {

    public final String verdict;   // PASS / FAIL / N/A
    public final String reason;

    public AiVerdict(String verdict, String reason) {
        this.verdict = verdict;
        this.reason = reason == null ? "" : reason;
    }

    public static AiVerdict na(String reason) {
        return new AiVerdict("N/A", reason);
    }

    public boolean isFail() {
        return "FAIL".equals(verdict);
    }

    @Override
    public String toString() {
        return reason.isEmpty() ? verdict : verdict + " - " + reason;
    }
}
