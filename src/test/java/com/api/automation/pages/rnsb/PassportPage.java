package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** /CosmosOVD/Passportverfiy  ->  {"result":{"fileNumber":"...","name":"...","dob":"..."},"message":null} */
public class PassportPage extends ApiPage {

    @Override public String name() { return "Passport Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"passportverfiy", "passportverify"}; }

    @Override public String expectedResult() {
        return "Passport details are returned for the same file number and date of birth";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .notBlank("Result returned", "result")
                .sameAsRequest("File number", "result.fileNumber", "fileNumber")
                .sameAsRequest("Date of birth", "result.dob", "dob")
                .notBlank("Passport holder name", "result.name")
                .result("File " + r.get("result.fileNumber") + " | " + r.get("result.name")
                                + " | " + r.get("result.typeOfApplication"),
                        r.get("message"));
    }
}
