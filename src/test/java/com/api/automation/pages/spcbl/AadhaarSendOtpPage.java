package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /TruthScreenOVD/AadharVerifySendOTP
 * success: {"Statuscode":"200","Errormsg":"OTP send successfully.","Aadhartxn":"000713","AadharRefNum":"AJYC..."}
 * failure: {"Statuscode":"500","Errormsg":"Exceeded Maximum OTP generation Limit. Try After some time",...}
 */
public class AadhaarSendOtpPage extends ApiPage {

    @Override public String name() { return "Aadhaar Send OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"aadharverifysendotp"}; }

    @Override public String expectedResult() {
        return "OTP is sent to the Aadhaar-linked mobile: Statuscode = 200 with Aadhartxn and AadharRefNum";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("Statuscode", "Statuscode", "200")
                .notBlank("Aadhaar transaction id", "Aadhartxn")
                .notBlank("Aadhaar reference number", "AadharRefNum")
                .result(r.get("Errormsg") + " | txn " + r.get("Aadhartxn") + " | ref " + r.get("AadharRefNum"),
                        r.get("Errormsg"));
    }
}
