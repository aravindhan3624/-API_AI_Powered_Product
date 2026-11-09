package com.api.automation.pages.common;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /OvdVerification/GetSmsOtp
 * {"response":{"phone":"919677755667","details":"OTP sent.","id":"566...","status":"success"}}
 */
public class GetSmsOtpPage extends ApiPage {

    @Override public String name() { return "Send SMS OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"getsmsotp"}; }

    @Override public String expectedResult() {
        return "SMS OTP is sent to the mobile number: response.status = success, details = 'OTP sent.'";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("OTP send status", "response.status", "success")
                .notBlank("Mobile number echoed", "response.phone")
                .contains("Provider message", "response.details", "sent")
                .result("OTP sent to " + r.get("response.phone") + " - " + r.get("response.details"),
                        r.get("response.details"));
    }
}
