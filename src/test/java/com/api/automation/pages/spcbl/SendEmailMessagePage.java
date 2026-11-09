package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /OvdVerification/SendEmailMessage
 * {"status":"200","result":[{"MESSAGE_CODE":51038,"MESSAGE_DESC":"Success","OTP":154632,...,"API_RESULT":"SUCCESS"}]}
 */
public class SendEmailMessagePage extends ApiPage {

    @Override public String name() { return "Send Email OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"sendemailmessage"}; }

    @Override public String expectedResult() {
        return "Email OTP is sent: status = 200, MESSAGE_DESC = Success, API_RESULT = SUCCESS";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .equalsAny("API status", "status", "200")
                .equalsAny("Message", "result[0].MESSAGE_DESC", "Success")
                .equalsAny("API result", "result[0].API_RESULT", "SUCCESS")
                .result("Email OTP: " + r.get("result[0].MESSAGE_DESC") + " | " + r.get("result[0].EMAIL_SUBJECT"),
                        r.get("result[0].MESSAGE_DESC"));
    }
}
