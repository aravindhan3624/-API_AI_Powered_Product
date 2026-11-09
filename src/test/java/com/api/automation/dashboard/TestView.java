package com.api.automation.dashboard;

import java.util.ArrayList;
import java.util.List;

import com.api.automation.pages.Check;

/** One test case as shown on the dashboard / HTML report (public fields = JSON). */
public class TestView {
    public String id;
    public String tcId;
    public String apiName;
    public String module;
    public String pageName;
    public String method;
    public String endpoint;       // full URL once resolved
    public String baseUrl;        // https://<ckyc-host>
    public String apiPath;        // /ckyc-api-gateway/ckycBackend/dvs/getCustomerDetails
    public String expectedHttp;   // "401 Unauthorized" / "400 Bad Request (or 422)"
    public String httpCheck;      // OK / MISMATCH
    public String vapt;           // OWASP API Top 10 / CWE reference of the check
    public String label;
    public boolean negative;
    public String status = "QUEUED";   // QUEUED / RUNNING / WAITING_OTP / PASS / FAIL / SKIP / NA (not executable)
    public String step;
    public Integer httpStatus;
    public Long timeMs;
    public boolean slow;
    public String expected;
    public String actual;
    public String reason;
    public String category;   // failure / skip category (plain English heading)
    public String remark;     // why it failed / was skipped, in plain English
    public String action;     // what is needed and by whom
    public String aiVerdict;
    public String aiReason;
    public String request;          // decrypted request (method + URL + body)
    public String requestHeaders;   // headers sent (token masked)
    public String wireRequest;      // encrypted request as it went over the network
    public String wireResponse;     // encrypted response as received
    public boolean encrypted;
    public String executedAt;       // "13-Oct-2026 17:55:42" - when this call ran (to the second)
    public String format;           // "JSON → JSON", "XML → XML" ...
    public String dataSource;       // test data file › sheet › row, payload file, live values and their source
    public String response;
    public String testData;
    public String responseFile;   // relative link to the full-response viewer
    public List<Check> checks = new ArrayList<>();
    public long startedAt;
    public long finishedAt;
}
