package com.api.automation.pages;

import com.api.automation.utils.ResponseAnalyzer;
import com.api.automation.utils.ResponseHandler;

import io.restassured.path.json.JsonPath;

/** Everything a page needs to validate one API call. */
public class ApiResponse {

    public final String tcId;
    public final String method;
    public final String endpoint;
    public final String requestBody;
    public final int httpStatus;
    public final String body;          // normalized (escaped JSON unwrapped)
    public final JsonPath json;        // null when body is not JSON
    public final JsonPath requestJson; // null when request is not JSON
    public final long timeMs;

    public ApiResponse(String tcId, String method, String endpoint, String requestBody,
                       int httpStatus, String normalizedBody, long timeMs) {
        this.tcId = tcId;
        this.method = method;
        this.endpoint = endpoint;
        this.requestBody = requestBody;
        this.httpStatus = httpStatus;
        this.body = normalizedBody;
        this.json = ResponseHandler.jsonOrNull(normalizedBody);
        this.requestJson = ResponseHandler.jsonOrNull(requestBody == null ? "" : requestBody.trim());
        this.timeMs = timeMs;
    }

    /** Value at a JSON path of the response, or null. */
    public String get(String path) {
        return json == null ? null : ResponseAnalyzer.str(json, path);
    }

    /** Value at a JSON path of the request body, or null. */
    public String req(String path) {
        return requestJson == null ? null : ResponseAnalyzer.str(requestJson, path);
    }

    /** First non-null value among several paths. */
    public String first(String... paths) {
        for (String p : paths) {
            String v = get(p);
            if (v != null) return v;
        }
        return null;
    }
}
