package com.api.automation.pages.common;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /OvdVerification/VerifySmsOtp
 * {"response":{"phone":"919677755667","details":"OTP matched.","id":"566...","status":"success"}}
 */
public class VerifySmsOtpPage extends ApiPage {

    @Override public String name() { return "Verify SMS OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"verifysmsotp"}; }

    @Override public String expectedResult() {
        return "Entered SMS OTP is accepted: response.status = success, details = 'OTP matched.'";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("OTP verify status", "response.status", "success")
                .contains("OTP matched", "response.details", "matched")
                .result("Mobile " + r.get("response.phone") + " - " + r.get("response.details"),
                        r.get("response.details"));
    }
}
