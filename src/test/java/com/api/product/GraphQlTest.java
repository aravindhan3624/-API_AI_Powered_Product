package com.api.product;

import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.importer.GraphQlImporter;
import com.api.product.core.model.ApiModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline checks of the GraphQL adapter using a small introspection fixture. */
public class GraphQlTest {

    @Test
    public void introspectionBecomesRequestsAndNegatives() throws Exception {
        JsonNode json = new ObjectMapper().readTree(Paths.get("src/test/resources/graphql/introspection-sample.json").toFile());
        Assert.assertTrue(GraphQlImporter.looksLikeIntrospection(json));
        ApiModel m = GraphQlImporter.fromIntrospection(json, "https://api.example.test/graphql");
        Assert.assertEquals(m.endpoints.size(), 3);
        Assert.assertEquals(m.environments.get(0).baseUrl, "https://api.example.test");
        ApiModel.Endpoint user = m.endpoint("q_user");
        Assert.assertEquals(user.path, "/graphql");
        Assert.assertEquals(user.requestBody.get("query").asText(), "query User($id: ID!) { user(id: $id) { id name role } }");
        Assert.assertEquals(user.requestBody.get("variables").get("id").asText(), "1");
        ApiModel.Endpoint create = m.endpoint("m_createUser");
        Assert.assertFalse(create.idempotent, "mutations are writes");
        Assert.assertEquals(create.requestBody.get("variables").get("input").get("name").asText(), "string");
        Assert.assertTrue(m.testCases.stream().anyMatch(t -> t.id.equals("TC_q_user_MISSING_FIELD")));
        Assert.assertTrue(m.testCases.stream().anyMatch(t -> t.id.equals("TC_m_createUser_INVALID_TYPE")));
        Assert.assertTrue(m.testCases.stream().noneMatch(t -> t.endpointId.equals("q_users") && !t.category.equals("POSITIVE")), "no required args, no argument negatives");
        Assert.assertTrue(m.api.source.warnings.stream().anyMatch(w -> w.contains("Introspection is enabled")));
    }
}
