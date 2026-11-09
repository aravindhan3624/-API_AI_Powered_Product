package com.api.automation.utils;

import java.nio.file.Files;
import java.nio.file.Path;

import com.api.automation.config.ConfigManager;
import com.api.automation.config.ProjectContext;

import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.response.Response;

public class TokenManager {

    private static String token;

    /** Called when a new project starts, so tokens are never shared between banks. */
    public static void reset() {
        token = null;
    }

    public static String getToken() {

        if (token == null) {

            try {
                String baseUrl = ConfigManager.get("base.url");
                String endpoint = ConfigManager.get("auth.endpoint");
                String payloadFile = ConfigManager.get("auth.payload.file");
                String tokenKey = ConfigManager.get("auth.token.key");

                Path file = ProjectContext.payloadFolder().resolve(payloadFile);
                if (!Files.exists(file)) {
                    file = ProjectContext.sharedPayloadFolder().resolve(payloadFile);
                }
                String payload = GlobalStore.resolve(Files.readString(file));

                Response res = RestAssured.given()
                        .baseUri(baseUrl)
                        .header("Content-Type", "application/json")
                        .body(payload)
                        .post(endpoint);

                token = res.jsonPath().getString(tokenKey);
                Allure.addAttachment("Token", token);
                System.out.println("Generated Token: " + token);
                Allure.addAttachment("Auth Response", res.asPrettyString());
            } catch (Exception e) {
                throw new RuntimeException("Token generation failed: " + e.getMessage());
            }
        }

        return token;
    }
}
