package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** /CosmosOVD/DLverfiy  ->  {"result":{"dlNumber":"...","detailsOfDrivingLicence":{"status":"ACTIVE","name":"..."}}} */
public class DrivingLicencePage extends ApiPage {

    @Override public String name() { return "Driving Licence Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"dlverfiy", "dlverify"}; }

    @Override public String expectedResult() {
        return "DL details are returned for the same DL number and the licence status is ACTIVE";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .notBlank("Result returned", "result")
                .sameAsRequest("DL number", "result.dlNumber", "number")
                .equalsAny("Licence status", "result.detailsOfDrivingLicence.status", "ACTIVE")
                .notBlank("Holder name", "result.detailsOfDrivingLicence.name")
                .result("DL " + r.get("result.dlNumber") + " | " + r.get("result.detailsOfDrivingLicence.status")
                                + " | " + r.get("result.detailsOfDrivingLicence.name"),
                        r.first("message", "result.message"));
    }
}
