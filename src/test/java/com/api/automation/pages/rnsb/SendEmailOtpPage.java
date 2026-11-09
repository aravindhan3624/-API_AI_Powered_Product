package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** /AccountOpeningForm/SendEmailDIY  ->  {"status":"200","result":{"status":"Success","message":"Email OTP sent successfully"}} */
public class SendEmailOtpPage extends ApiPage {

    @Override public String name() { return "Send Email OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"sendemaildiy"}; }

    @Override public String expectedResult() {
        return "Email OTP is sent: status = 200, result.status = Success";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("API status", "status", "200")
                .equalsAny("Email OTP status", "result.status", "Success")
                .result("Email OTP: " + r.get("result.message"), r.get("result.message"));
    }
}
