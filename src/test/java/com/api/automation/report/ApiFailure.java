package com.api.automation.report;

/**
 * Thrown for a failed API check. Carries a readable reason and NO Java stack trace,
 * so IntelliJ / Maven show one clean line instead of 30 lines of framework frames.
 */
public class ApiFailure extends AssertionError {

    public ApiFailure(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
