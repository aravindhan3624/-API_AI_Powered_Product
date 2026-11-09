package com.api.automation.pages.common;

import java.util.List;

import com.api.automation.pages.ApiPage;

/** OvdVerification OTP services - same product in every bank. */
public final class CommonPages {

    private CommonPages() {}

    public static List<ApiPage> all() {
        return List.of(
                new GetSmsOtpPage(),
                new VerifySmsOtpPage(),
                new ValidateOtpPage()
        );
    }
}
