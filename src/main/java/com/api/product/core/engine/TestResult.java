package com.api.product.core.engine;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Execution result contract v1.0 (docs/schemas/result.v1.schema.json), flattened for easy reporting. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestResult {
    public String resultVersion = "1.0";
    public String runId;
    public String projectId;
    public String envId;
    public String testCaseId;
    public int testCaseVersion;
    public String endpointId;
    public String module;
    public String apiName;
    public String method;
    public String baseUrl;
    public String url;
    public String category;
    public String title;
    /** PASS | FAIL | BLOCKED | SKIPPED | NOT_EXECUTED */
    public String outcome;
    /** Pass-with-remark or the failure reason. */
    public String remark;
    public String failureCategory;
    public String severity;
    public String owasp;
    public List<Integer> expectedStatus;
    public Integer httpStatus;
    public long latencyMs;
    public String startedAt;
    public String endedAt;
    public String requestEvidence;
    public String responseEvidence;
    public String ruleVersion;
}
