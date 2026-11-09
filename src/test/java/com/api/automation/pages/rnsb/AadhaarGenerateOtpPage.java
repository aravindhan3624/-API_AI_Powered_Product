package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/**
 * /CosmosOVD/AadhaarGenerateOTP
 * Seen failure: {"result":{"message":"OTP is already generated for the given Aadhaar number,
 *               please try again after 60sec","iVaultAadharRefnum":"..."},"message":null}
 */
public class AadhaarGenerateOtpPage extends ApiPage {

    @Override public String name() { return "Aadhaar Generate OTP"; }

    @Override protected String[] endpointKeys() { return new String[]{"aadhaargenerateotp"}; }

    @Override public String expectedResult() {
        return "OTP is sent to the Aadhaar-linked mobile and a reference is returned (no 'already generated' or limit error)";
    }

    @Override public PageResult validate(ApiResponse r) {
        String message = r.first("result.message", "message");
        boolean noError = message == null || !message.toLowerCase()
                .matches(".*(already|try again|limit|exceed|error|fail|invalid).*");
        return new Checks(r)
                .notBlank("Result returned", "result")
                .that("OTP generated", "no error message", message, noError)
                .result("Aadhaar OTP: " + (message == null ? "generated" : message)
                                + " | Ref " + r.first("result.iVaultAadharRefnum", "result.Client_Id", "Client_Id"),
                        message);
    }
}
