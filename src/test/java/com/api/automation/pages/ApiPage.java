package com.api.automation.pages;

/**
 * One "page" = one API functionality of a project (API Page Object Model).
 * It knows the endpoint, the business expectation, and how to validate the JSON response.
 *
 * To add a new API:
 *   1. create a class in pages/<project>/ extending ApiPage
 *   2. add it to that project's list in <Project>Pages.java
 */
public abstract class ApiPage {

    /** Readable functionality name, e.g. "PAN Verification". */
    public abstract String name();

    /** Last path segment(s) this page handles, lower-case, e.g. "panverfiy". */
    protected abstract String[] endpointKeys();

    /** Business expectation shown as "Expected Result" (unless the Excel row gives one). */
    public abstract String expectedResult();

    /** Validates a successful (positive) call. */
    public abstract PageResult validate(ApiResponse r);

    public boolean matches(String endpoint) {
        if (endpoint == null) return false;
        String path = endpoint.split("\\?")[0].toLowerCase();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        for (String key : endpointKeys()) {
            if (path.endsWith("/" + key.toLowerCase()) || path.equals(key.toLowerCase())) return true;
        }
        return false;
    }
}
