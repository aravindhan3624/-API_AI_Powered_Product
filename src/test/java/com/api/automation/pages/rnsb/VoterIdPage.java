package com.api.automation.pages.rnsb;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** /CosmosOVD/Voteridverfiy  ->  {"result":{"name":"...","epicNumber":"UPX0403261",...}} */
public class VoterIdPage extends ApiPage {

    @Override public String name() { return "Voter ID Verification"; }

    @Override protected String[] endpointKeys() { return new String[]{"voteridverfiy", "voteridverify"}; }

    @Override public String expectedResult() {
        return "Voter details are returned for the same EPIC number with the voter name";
    }

    @Override public PageResult validate(ApiResponse r) {
        return new Checks(r)
                .notBlank("Result returned", "result")
                .sameAsRequest("EPIC number", "result.epicNumber", "epicnumber")
                .notBlank("Voter name", "result.name")
                .result("EPIC " + r.get("result.epicNumber") + " | " + r.get("result.name")
                                + " | " + r.get("result.state"),
                        r.first("message", "result.message"));
    }
}
