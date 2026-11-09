package com.api.automation.pages.common;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /OvdVerification/ValidateOTP  (email OTP)
 * {"status":"200","result":[{"MESSAGE_CODE":51038,"MESSAGE_DESC":"Success"}]}
 */
public class ValidateOtpPage extends ApiPage {

    @Override public String name() { return "Validate Email OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"validateotp"}; }

    @Override public String expectedResult() {
        return "Email OTP is validated: status = 200 and MESSAGE_DESC = Success";
    }

    @Override public PageResult validate(ApiResponse r) {
        String desc = r.first("result[0].MESSAGE_DESC", "result.MESSAGE_DESC", "result.status", "result.message");
        return new Checks(r)
                .equalsAny("API status", "status", "200", "success")
                .that("OTP validation message", "Success", desc,
                        desc != null && desc.toLowerCase().contains("success"))
                .result("OTP validation: " + desc, desc);
    }
}
