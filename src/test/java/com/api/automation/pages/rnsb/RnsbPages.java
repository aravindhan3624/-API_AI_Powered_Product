package com.api.automation.pages.rnsb;

import java.util.List;

import com.api.automation.pages.ApiPage;

/** All API pages of RNSB (Rajkot). Register every new RNSB page here. */
public final class RnsbPages {

    private RnsbPages() {}

    public static List<ApiPage> all() {
        return List.of(
                new PanVerifyPage(),
                new DrivingLicencePage(),
                new VoterIdPage(),
                new PassportPage(),
                new AadhaarGenerateOtpPage(),
                new AadhaarValidateOtpPage(),
                new SendEmailOtpPage(),
                new UcicCheckPage(),
                new AmlCheckPage()
        );
    }
}
