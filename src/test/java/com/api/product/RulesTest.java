package com.api.product;

import java.nio.file.Paths;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.flow.RuleAssertions;
import com.api.product.core.flow.SyntheticData;
import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.model.ApiModel;

/** Offline checks: business rules to reviewable tests, and synthetic data planning. */
public class RulesTest {

    private ApiModel petstore() throws Exception {
        return OpenApiModelImporter.importSpec(Paths.get("sample_apis/specs/Petstore_v3_openapi.json"), "ACTIVE");
    }

    @Test
    public void rulesBecomeProposalsWithAmbiguityFlags() throws Exception {
        ApiModel m = petstore();
        String text = "BR-001 POST /pet: the name is required and must be at most 20 characters.\n"
                + "BR-002 The response of POST /pet must contain the id, and the response time must be under 2 seconds.\n"
                + "BR-003 The status of a pet must be one of available, pending, sold.\n"
                + "BR-004 The API must return the data as per requirement and respond quickly.\n"
                + "BR-005 The name is required.\n";
        RuleAssertions.Result r = RuleAssertions.analyze(m, text);
        Assert.assertTrue(r.proposals.stream().anyMatch(p -> p.kind.equals("REQUIRED") && p.field.equals("name") && p.endpointId.equals("addPet") && p.status.equals("ready")));
        Assert.assertTrue(r.proposals.stream().anyMatch(p -> p.kind.equals("MAX_LENGTH") && p.value.equals("20")));
        Assert.assertTrue(r.proposals.stream().anyMatch(p -> p.kind.equals("MAX_LATENCY") && p.value.equals("2000")));
        Assert.assertTrue(r.proposals.stream().anyMatch(p -> p.kind.equals("ALLOWED_VALUES") && p.value.equals("available,pending,sold")));
        Assert.assertTrue(r.unparsed.stream().anyMatch(u -> u.contains("BR-004")), "vague rule is reported, not guessed");

        int before = m.testCases.size();
        for (RuleAssertions.Proposal p : r.proposals) if (p.status.equals("ready")) RuleAssertions.apply(m, p);
        Assert.assertTrue(m.testCases.size() > before);
        Assert.assertTrue(m.testCases.stream().filter(t -> "brd".equals(t.origin)).allMatch(t -> "DRAFT".equals(t.lifecycle)), "new tests always start as DRAFT");
        Assert.assertTrue(m.testCases.stream().filter(t -> "TC_addPet_POSITIVE".equals(t.id)).findFirst().orElseThrow().expected.assertions.stream().anyMatch(a -> "maxLatencyMs".equals(a.type)));
    }

    @Test
    public void ambiguousFieldIsNotGuessed() throws Exception {
        RuleAssertions.Result r = RuleAssertions.analyze(petstore(), "BR-009 The name is required.");
        Assert.assertEquals(r.proposals.size(), 1);
        Assert.assertEquals(r.proposals.get(0).status, "needs-confirmation");
        Assert.assertFalse(r.proposals.get(0).candidates.isEmpty());
    }

    @Test
    public void syntheticDataOnlyTouchesPlaceholdersAndNeverSecrets() throws Exception {
        ApiModel m = new ApiModel();
        ApiModel.Endpoint e = new ApiModel.Endpoint();
        e.id = "reg";
        e.method = "POST";
        e.path = "/reg";
        e.requestBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"email\":\"string\",\"mobile\":\"\",\"password\":\"string\",\"city\":\"Pune\",\"firstName\":\"sample\",\"notes\":{\"name\":\"string\"}}");
        m.endpoints.add(e);
        List<SyntheticData.Change> plan = SyntheticData.plan(m);
        Assert.assertTrue(plan.stream().anyMatch(c -> c.field.equals("email") && c.newValue.endsWith("@example.test")));
        Assert.assertTrue(plan.stream().anyMatch(c -> c.field.equals("mobile")));
        Assert.assertTrue(plan.stream().noneMatch(c -> c.field.equals("password")), "secrets untouched");
        Assert.assertTrue(plan.stream().noneMatch(c -> c.field.equals("city")), "real values untouched");
        Assert.assertTrue(plan.stream().noneMatch(c -> c.field.endsWith("name") && c.field.contains(".")), "nested labels untouched");
        Assert.assertEquals(SyntheticData.apply(m, plan), plan.size());
        Assert.assertTrue(SyntheticData.plan(m).isEmpty(), "idempotent");
    }
}
