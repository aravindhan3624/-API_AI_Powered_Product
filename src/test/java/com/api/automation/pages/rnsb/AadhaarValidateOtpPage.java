package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** /CosmosOVD/VerifyAadhaarNumberValidateOTP  (tighten once a successful response has been seen) */
public class AadhaarValidateOtpPage extends ApiPage {

    @Override public String name() { return "Aadhaar Validate OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"verifyaadhaarnumbervalidateotp"}; }

    @Override public String expectedResult() {
        return "Aadhaar OTP is accepted and the resident's e-KYC details are returned";
    }

    @Override public PageResult validate(ApiResponse r) {
        String message = r.first("result.message", "message", "Message");
        boolean noError = message == null || !message.toLowerCase()
                .matches(".*(invalid|incorrect|expired|fail|error|mismatch).*");
        return new Checks(r)
                .notBlank("Result returned", "result")
                .that("OTP accepted", "no error message", message, noError)
                .result("Aadhaar e-KYC: " + (message == null ? "details returned" : message), message);
    }
}
