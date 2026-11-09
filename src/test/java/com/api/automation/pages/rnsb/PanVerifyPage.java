package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /CosmosOVD/PANverfiy
 * {"msg":{"aadhaarSeedingStatus":"Operative PAN","dateOfBirth":"MATCHING","name":"MATCHING",
 *         "panNumber":"ABCDE1234F","panStatus":"EXISTING AND VALID"},"status":"1","transId":"..."}
 */
public class PanVerifyPage extends ApiPage {

    @Override public String name() { return "PAN Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"panverfiy", "panverify"}; }

    @Override public String expectedResult() {
        return "PAN is EXISTING AND VALID, name and date of birth are MATCHING (status = 1)";
    }

    @Override public PageResult validate(ApiResponse r) {
        String msg = r.get("msg");
        return new Checks(r)
                .equalsAny("Status", "status", "1")
                .sameAsRequest("PAN number", "msg.panNumber", "docNumber")
                .contains("PAN status", "msg.panStatus", "VALID")
                .notContainsAny("PAN status not invalid", "msg.panStatus", "INVALID", "DEACTIVATED", "DELETED")
                .equalsAny("Name match", "msg.name", "MATCHING")
                .equalsAny("DOB match", "msg.dateOfBirth", "MATCHING")
                .result("PAN " + r.get("msg.panNumber") + " | " + r.get("msg.panStatus")
                                + " | Name " + r.get("msg.name") + " | DOB " + r.get("msg.dateOfBirth")
                                + " | " + r.get("msg.aadhaarSeedingStatus"),
                        msg != null && !msg.startsWith("{") ? msg : null);
    }
}
