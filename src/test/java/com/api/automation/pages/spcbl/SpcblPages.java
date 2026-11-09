package com.api.automation.pages.spcbl;

import java.util.List;

import com.api.automation.pages.ApiPage;

/** All API pages of SPCBL (Surat). Register every new SPCBL page here. */
public final class SpcblPages {

    private SpcblPages() {}

    public static List<ApiPage> all() {
        return List.of(
                new DlVerificationPage(),
                new PassportVerifyPage(),
                new PanVerifyPage(),
                new VoterIdVerifyPage(),
                new AadhaarSendOtpPage(),
                new AadhaarVerifyOtpPage(),
                new SendEmailMessagePage()
        );
    }
}
