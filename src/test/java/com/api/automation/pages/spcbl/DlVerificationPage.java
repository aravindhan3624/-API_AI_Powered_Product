package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.PageResult;

/** /TruthScreenOVD/DLVerification  ->  {"msg":{"Driving License Number":"...","Name":"...",...},"Status":1} */
public class DlVerificationPage extends ApiPage {

    @Override public String name() { return "Driving Licence Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"dlverification"}; }

    @Override public String expectedResult() {
        return "Status = 1 and DL details are returned for the same DL number";
    }

    @Override public PageResult validate(ApiResponse r) {
        return TruthScreen.base(r)
                .sameAsRequest("DL number", "msg.'Driving License Number'", "docNumber")
                .result("DL " + r.get("msg.'Driving License Number'") + " | DOB " + r.get("msg.'Date of Birth'")
                                + " | " + r.first("msg.Name", "msg.'Name'"),
                        TruthScreen.error(r));
    }
}
