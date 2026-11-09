package com.api.automation.utils;

public class ValidationResult {

    public final boolean isPass;
    public final String message;

    private ValidationResult(boolean isPass, String message) {
        this.isPass = isPass;
        this.message = message;
    }

    public static ValidationResult pass() {
        return new ValidationResult(true, "");
    }

    public static ValidationResult fail(String message) {
        return new ValidationResult(false, message);
    }
}