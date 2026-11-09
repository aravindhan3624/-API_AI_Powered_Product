package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.PageResult;

/** /TruthScreenOVD/PassportVerify */
public class PassportVerifyPage extends ApiPage {

    @Override public String name() { return "Passport Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"passportverify"}; }

    @Override public String expectedResult() {
        return "Status = 1 and passport details are returned for the file number";
    }

    @Override public PageResult validate(ApiResponse r) {
        return TruthScreen.base(r)
                .result("Passport: " + (TruthScreen.error(r) == null ? "details returned" : TruthScreen.error(r)),
                        TruthScreen.error(r));
    }
}
