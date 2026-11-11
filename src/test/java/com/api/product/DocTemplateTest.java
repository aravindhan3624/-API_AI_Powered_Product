package com.api.product;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.importer.DocApiImporter;
import com.api.product.core.model.ApiModel;

/** The shipped API document template must import exactly as its guide promises. */
public class DocTemplateTest {

    @Test
    public void templateImportsAsDocumented() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("docs", "templates", "API_Document_Template.md")), StandardCharsets.UTF_8);
        ApiModel m = DocApiImporter.importText(text, "API_Document_Template.md");

        Assert.assertEquals(m.environments.get(0).baseUrl, "https://api.example-bank.com/v1");
        Assert.assertEquals(m.endpoints.size(), 5, "login + 4 customer APIs");
        Assert.assertEquals(m.testCases.size(), 5);
        Assert.assertTrue(m.api.source.warnings.isEmpty(), "template must import without warnings: " + m.api.source.warnings);

        ApiModel.Endpoint login = byName(m, "Login and get token");
        Assert.assertEquals(login.method, "POST");
        Assert.assertEquals(login.path, "/auth/login");
        Assert.assertEquals(login.module, "Authentication");
        Assert.assertEquals(login.requestBody.path("password").asText(), "${ENV:API_PASSWORD}", "secrets stay references");

        ApiModel.Endpoint create = byName(m, "Create customer");
        Assert.assertEquals(create.module, "Customer");
        Assert.assertEquals(create.requestBody.path("mobile").asText(), "9000000001");

        ApiModel.Endpoint get = byName(m, "Get customer by id");
        Assert.assertEquals(get.path, "/customers/${customerId}", "{customerId} becomes a variable");
        Assert.assertNull(get.requestBody);

        Assert.assertNotNull(byName(m, "Update customer").requestBody);
        Assert.assertNull(byName(m, "Delete customer").requestBody);
    }

    private static ApiModel.Endpoint byName(ApiModel m, String name) {
        for (ApiModel.Endpoint e : m.endpoints) if (name.equals(e.name)) return e;
        Assert.fail("no API named '" + name + "'; found " + m.endpoints.stream().map(e -> e.name).toList());
        return null;
    }
}
