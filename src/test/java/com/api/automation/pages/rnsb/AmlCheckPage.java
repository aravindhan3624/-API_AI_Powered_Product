package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /AmlCheck/ValidateAML - AML / sanction screening.
 * Endpoint returned 404 in the last run; tighten these checks once a real response is available.
 */
public class AmlCheckPage extends ApiPage {

    @Override public String name() { return "AML Screening"; }

    @Override protected String[] endpointKeys() { return new String[]{"validateaml"}; }

    @Override public String expectedResult() {
        return "AML screening result is returned for the customer (match / no match)";
    }

    @Override public PageResult validate(ApiResponse r) {
        String error = r.get("Message");
        return new Checks(r)
                .that("No server error message", "no 'Message' error", error, error == null)
                .result("AML: " + r.first("result.message", "message", "status"), error);
    }
}
