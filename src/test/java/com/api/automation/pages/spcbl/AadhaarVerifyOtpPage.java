package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /TruthScreenOVD/VerifyUIDAadharOTP
 * failure seen: {"Statuscode":"500","Errormsg":"Resident authentication failed","Poi":"","Poa":"","Pht":"","Prn":""}
 * success expected: Statuscode 200 with Poi (proof of identity) / Poa (proof of address) filled.
 */
public class AadhaarVerifyOtpPage extends ApiPage {

    @Override public String name() { return "Aadhaar Verify OTP (e-KYC)"; }

    @Override protected String[] endpointKeys() { return new String[]{"verifyuidaadharotp"}; }

    @Override public String expectedResult() {
        return "Aadhaar OTP is accepted: Statuscode = 200 and e-KYC details (Poi / Poa) are returned";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("Statuscode", "Statuscode", "200")
                .notBlank("Proof of identity (Poi)", "Poi")
                .result("e-KYC: " + r.first("Errormsg", "Statuscode"), r.get("Errormsg"));
    }
}
