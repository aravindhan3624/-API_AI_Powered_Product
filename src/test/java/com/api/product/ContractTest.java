package com.api.product;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.engine.ContractValidator;
import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.importer.SpecDrift;
import com.api.product.core.model.ApiModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline checks of response-contract validation and spec drift (no network). mvn -q test -Dsuite=suites/testng-CONTRACT.xml */
public class ContractTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path PETSTORE3 = Paths.get("sample_apis/specs/Petstore_v3_openapi.json");

    private List<String> check(String schema, String json) throws Exception {
        return ContractValidator.validate(M.readTree(schema), M.readTree(json));
    }

    @Test
    public void validatorCatchesTypeRequiredEnumAndNesting() throws Exception {
        String schema = "{\"type\":\"object\",\"required\":[\"id\",\"name\"],\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\",\"maxLength\":5},"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"k\"],\"properties\":{\"k\":{\"type\":\"string\"}}}}}}";
        Assert.assertTrue(check(schema, "{\"id\":1,\"name\":\"abc\",\"status\":\"a\",\"tags\":[{\"k\":\"x\"}]}").isEmpty());
        List<String> bad = check(schema, "{\"id\":\"1\",\"status\":\"z\",\"tags\":[{}]}");
        String all = String.join(" | ", bad);
        Assert.assertTrue(all.contains("$.id: expected integer but got string"), all);
        Assert.assertTrue(all.contains("$.name: required field is missing"), all);
        Assert.assertTrue(all.contains("not one of"), all);
        Assert.assertTrue(all.contains("$.tags[0].k: required field is missing"), all);
    }

    @Test
    public void validatorHandlesNullableUnknownKeywordsAndNumbers() throws Exception {
        Assert.assertTrue(check("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"nullable\":true}}}", "{\"a\":null}").isEmpty());
        Assert.assertFalse(check("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}", "{\"a\":null}").isEmpty());
        Assert.assertTrue(check("{\"type\":\"integer\",\"x-vendor\":1,\"format\":\"weird\"}", "5.0").isEmpty());
        Assert.assertFalse(check("{\"type\":\"number\",\"maximum\":3}", "4").isEmpty());
        Assert.assertTrue(check("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "7").isEmpty());
        Assert.assertFalse(check("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "true").isEmpty());
    }

    @Test
    public void importStoresContractAndHash() throws Exception {
        ApiModel m = OpenApiModelImporter.importSpec(PETSTORE3, "DRAFT");
        long withSchema = m.endpoints.stream().filter(e -> e.responseSchema != null).count();
        Assert.assertTrue(withSchema >= 10, "response schemas stored: " + withSchema);
        Assert.assertTrue(m.endpoints.stream().allMatch(e -> e.specKey != null && e.specHash != null && !e.specHash.isEmpty()));
        ApiModel.Endpoint petForType = m.endpoints.stream().filter(e -> "getPetById".equals(e.id)).findFirst().orElseThrow();
        Assert.assertEquals(petForType.responseSchema.path("properties").path("tags").path("items").path("properties").path("id").path("type").asText(), "integer", "leaf types are never cut");
        // a real Petstore pet response must satisfy the stored contract
        ApiModel.Endpoint getPet = m.endpoints.stream().filter(e -> "getPetById".equals(e.id)).findFirst().orElseThrow();
        JsonNode pet = M.readTree("{\"id\":10,\"name\":\"doggie\",\"category\":{\"id\":1,\"name\":\"Dogs\"},\"photoUrls\":[\"u\"],\"tags\":[{\"id\":1,\"name\":\"t\"}],\"status\":\"available\"}");
        Assert.assertTrue(ContractValidator.validate(getPet.responseSchema, pet).isEmpty());
        JsonNode broken = M.readTree("{\"id\":\"ten\",\"photoUrls\":\"u\",\"status\":\"gone\"}");
        Assert.assertTrue(ContractValidator.validate(getPet.responseSchema, broken).size() >= 3);
    }

    @Test
    public void driftFindsAddedRemovedChangedAndAppliesSafely() throws Exception {
        ApiModel current = OpenApiModelImporter.importSpec(PETSTORE3, "ACTIVE");
        ApiModel latest = OpenApiModelImporter.importSpec(PETSTORE3, "DRAFT");
        Assert.assertFalse(SpecDrift.compare(current, latest).any(), "identical spec must show no drift");

        latest.endpoints.removeIf(e -> "deletePet".equals(e.id));
        latest.endpoints.stream().filter(e -> "getPetById".equals(e.id)).findFirst().orElseThrow().specHash = "changed";
        ApiModel.Endpoint added = new ApiModel.Endpoint();
        added.id = "brandNew";
        added.method = "GET";
        added.path = "/new";
        added.specKey = "GET /new";
        added.specHash = "x";
        latest.endpoints.add(added);

        SpecDrift.Report r = SpecDrift.compare(current, latest);
        Assert.assertEquals(r.added.size(), 1);
        Assert.assertEquals(r.removed.size(), 1);
        Assert.assertEquals(r.changed.size(), 1);

        int testsBefore = current.testCases.size();
        SpecDrift.apply(current, latest);
        Assert.assertTrue(current.testCases.stream().filter(t -> "deletePet".equals(t.endpointId)).allMatch(t -> "DRAFT".equals(t.lifecycle) && t.tags.contains("spec-removed")));
        Assert.assertTrue(current.testCases.stream().filter(t -> "getPetById".equals(t.endpointId)).allMatch(t -> t.tags.contains("spec-changed")));
        Assert.assertTrue(current.endpoints.stream().anyMatch(e -> "brandNew".equals(e.id)));
        Assert.assertTrue(current.testCases.size() >= testsBefore, "nothing is deleted");
    }
}
