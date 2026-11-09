package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /Finacle/UCIC_Check_New - customer de-duplication in Finacle.
 * Endpoint returned 404 in the last run; tighten these checks once a real response is available.
 */
public class UcicCheckPage extends ApiPage {

    @Override public String name() { return "UCIC Dedupe Check (Finacle)"; }

    @Override protected String[] endpointKeys() { return new String[]{"ucic_check_new"}; }

    @Override public String expectedResult() {
        return "Finacle returns the UCIC de-dupe result for the customer (existing / new customer)";
    }

    @Override public PageResult validate(ApiResponse r) {
        String error = r.get("Message");
        return new Checks(r)
                .that("No server error message", "no 'Message' error", error, error == null)
                .result("UCIC: " + r.first("result.message", "message", "status"), error);
    }
}
