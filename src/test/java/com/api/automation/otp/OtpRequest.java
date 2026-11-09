package com.api.automation.otp;

import java.util.UUID;

/** Everything the OTP popup shows to the tester. Public fields = JSON for the dashboard. */
public class OtpRequest {

    public String id = UUID.randomUUID().toString();
    public String project;
    public String tcId;            // test that NEEDS the OTP (verify step)
    public String apiName;
    public String endpoint;
    public String channel;         // MOBILE / EMAIL / AADHAAR / UNKNOWN
    public String target;          // 9677755667 / aravindhan.d@... / XXXXXXXX8005
    public String targetLabel;     // "Mobile number" / "Email ID" / "Aadhaar-linked mobile"
    public String ovd;             // OVD number the OTP belongs to (Aadhaar masked)
    public String ovdLabel;        // "Aadhaar" / "PAN" / "Passport" / "OVD number"
    public String sentBy;          // "TC07 · Send SMS OTP"
    public String serverMessage;   // "OTP sent." from the generation API
    public String hint;            // OTP value found in an API response (RESPONSE mode only)
    public String error;           // e.g. "Wrong OTP - enter it again"
    public String note;            // e.g. "This NEGATIVE request still sent an OTP"
    public boolean negative;       // the request that sent the OTP was a negative test
    public String where = "desktop"; // desktop = popup window on top of everything; browser = console modal
    public String defaultOtp = "000000";
    public int timeoutSeconds = 90;
    public int attempt = 1;
    public boolean canResend;

    /** Copy for the next round (wrong OTP → ask once more). */
    public OtpRequest next() {
        OtpRequest n = new OtpRequest();
        n.project = project;
        n.tcId = tcId;
        n.apiName = apiName;
        n.endpoint = endpoint;
        n.channel = channel;
        n.target = target;
        n.targetLabel = targetLabel;
        n.ovd = ovd;
        n.ovdLabel = ovdLabel;
        n.sentBy = sentBy;
        n.serverMessage = serverMessage;
        n.note = note;
        n.negative = negative;
        n.where = where;
        n.defaultOtp = defaultOtp;
        n.timeoutSeconds = timeoutSeconds;
        n.attempt = attempt + 1;
        n.canResend = canResend;
        return n;
    }
}
