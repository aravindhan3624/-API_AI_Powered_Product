package com.api.automation.pages.spcbl;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.PageResult;

/** /TruthScreenOVD/VoterIdVerify  ->  {"msg":{"Name":"sarath aarthiyan","District":"Madurai",...},"Status":1} */
public class VoterIdVerifyPage extends ApiPage {

    @Override public String name() { return "Voter ID Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"voteridverify"}; }

    @Override public String expectedResult() {
        return "Status = 1 and voter details (name, constituency) are returned for the EPIC number";
    }

    @Override public PageResult validate(ApiResponse r) {
        return TruthScreen.base(r)
                .notBlank("Voter name", "msg.Name")
                .result("Voter " + r.get("msg.Name") + " | " + r.get("msg.District")
                                + " | " + r.get("msg.'Assembly Constituency'"),
                        TruthScreen.error(r));
    }
}
