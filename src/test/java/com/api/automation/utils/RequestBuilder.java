package com.api.automation.utils;

import com.api.automation.config.ConfigManager;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;

public class RequestBuilder {
	static {
	    RestAssured.useRelaxedHTTPSValidation();
	}

    public static RequestSpecification getRequest() {
        return getRequest(null);
    }

    /**
     * @param extraHeaders "Key:Value;Key2:Value2" (Excel "Headers" column), may be null / NA
     */
    public static RequestSpecification getRequest(String extraHeaders) {
        return getRequest(extraHeaders, true);
    }

    /**
     * @param logBody false for very large bodies (1 MB / 10 MB checks): no console log, no Allure attachment
     *
     * http.proxy=127.0.0.1:8080 sends every request through Burp Suite / OWASP ZAP (HTTP history, Repeater, Intruder).
     */
    public static RequestSpecification getRequest(String extraHeaders, boolean logBody) {

        int timeoutMs = ConfigManager.getInt("http.timeout.seconds", 60) * 1000;

        RequestSpecification request = RestAssured.given()
                .config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMs)
                        .setParam("http.socket.timeout", timeoutMs)))
                .baseUri(ConfigManager.get("base.url"))
                .filter(com.api.automation.security.SessionFilter.INSTANCE)
                .header("Content-Type", ConfigManager.get("content.type", "application/json"));
        if (logBody) request.filter(new AllureRestAssured());

        String proxy = ConfigManager.get("http.proxy", "");
        if (proxy != null && proxy.matches("[\\w.-]+:\\d+")) {
            request.proxy(proxy.substring(0, proxy.lastIndexOf(':')), Integer.parseInt(proxy.substring(proxy.lastIndexOf(':') + 1)));
        }

        if (logBody && ConfigManager.getBool("http.log", true)) {
            request.log().all();
        }

        applyHeaders(request, ConfigManager.get("default.headers"));
        applyHeaders(request, extraHeaders);

        // Optional Auth Handling
        String authType = ConfigManager.get("auth.type");

        if ("token".equalsIgnoreCase(authType)) {
            request.header("Authorization", "Bearer " + TokenManager.getToken());
        }

        return request;
    }

    private static void applyHeaders(RequestSpecification request, String headers) {
        if (headers == null || headers.isBlank() || headers.trim().equalsIgnoreCase("NA")) return;
        for (String h : headers.split(";")) {
            int idx = h.indexOf(':');
            if (idx > 0) {
                String value = GlobalStore.resolve(h.substring(idx + 1).trim()).trim();
                // optional values that are empty (e.g. "Bearer ${token?}" in cookie-session mode) are not sent
                if (value.isEmpty() || value.equalsIgnoreCase("Bearer") || value.equalsIgnoreCase("Basic")) continue;
                request.header(h.substring(0, idx).trim(), value);
            }
        }
    }
}
