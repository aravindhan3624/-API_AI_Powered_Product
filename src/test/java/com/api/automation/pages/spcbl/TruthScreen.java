package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;

/**
 * Common rules for SPCBL TruthScreen OVD responses:
 *   success : {"msg":{...details...},"Status":1,"transID":"...","tsTransId":"..."}
 *   failure : {"msg":"PAN Number should be of 10 characters","Status":1014,...}
 *             {"msg":null,"Status":0,...}
 */
final class TruthScreen {

    private TruthScreen() {}

    static Checks base(ApiResponse r) {
        String msg = r.get("msg");
        return new Checks(r)
                .equalsAny("TruthScreen Status", "Status", "1")
                .that("Document details returned", "details object", msg,
                        msg != null && msg.startsWith("{"));
    }

    /** API error text: msg when it is a plain string, otherwise the status code. */
    static String error(ApiResponse r) {
        String msg = r.get("msg");
        if (msg != null && !msg.startsWith("{")) return msg;
        String status = r.get("Status");
        return "1".equals(status) ? null : "TruthScreen Status " + status + " (no details returned)";
    }
}
