package com.api.automation.utils;

import io.restassured.response.Response;

public class FailureAnalyzer {

    public static String analyze(Response res) {

        int code = res.getStatusCode();

        if (code == 400) return "Bad Request (Payload issue)";
        if (code == 401) return "Unauthorized (Token issue)";
        if (code == 404) return "Endpoint not found";
        if (code >= 500) return "Server failure";

        return "Unknown error";
    }
}