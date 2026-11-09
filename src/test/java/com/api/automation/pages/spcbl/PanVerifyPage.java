package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.PageResult;

/**
 * /TruthScreenOVD/PanVerify
 * failure seen: {"msg":"PAN Number should be of 10 characters","Status":1014}
 *               {"msg":"Invalid docType, please provide valid docType.","Status":1022}
 */
public class PanVerifyPage extends ApiPage {

    @Override public String name() { return "PAN Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"panverify"}; }

    @Override public String expectedResult() {
        return "Status = 1 and PAN details are returned for the PAN number";
    }

    @Override public PageResult validate(ApiResponse r) {
        return TruthScreen.base(r)
                .result("PAN: " + (TruthScreen.error(r) == null ? "details returned" : TruthScreen.error(r)),
                        TruthScreen.error(r));
    }
}
