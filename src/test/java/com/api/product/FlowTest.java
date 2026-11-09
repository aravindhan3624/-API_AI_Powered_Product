package com.api.product;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.flow.AutoChain;
import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Link;
import com.api.product.core.model.ApiModel.TestCase;

/** Offline checks of automatic data chaining on the Petstore v3 specification. */
public class FlowTest {

    private static final Path PETSTORE3 = Paths.get("sample_apis/specs/Petstore_v3_openapi.json");

    @Test
    public void detectApplyOrderAndRemove() throws Exception {
        ApiModel m = OpenApiModelImporter.importSpec(PETSTORE3, "ACTIVE");
        List<Link> found = AutoChain.detect(m);
        Assert.assertTrue(found.stream().anyMatch(l -> "getPetById".equals(l.toEndpointId) && "addPet".equals(l.fromEndpointId) && "$.id".equals(l.jsonPath) && "high".equals(l.confidence)));
        Assert.assertTrue(found.stream().anyMatch(l -> "getOrderById".equals(l.toEndpointId) && "placeOrder".equals(l.fromEndpointId)));
        Assert.assertTrue(found.stream().anyMatch(l -> "placeOrder".equals(l.toEndpointId) && "body".equals(l.where) && "petId".equals(l.name)));
        Assert.assertTrue(found.stream().noneMatch(l -> l.toEndpointId.equals(l.fromEndpointId)), "no self links");

        String before = m.endpoint("getPetById").path;
        m.links.addAll(found);
        Assert.assertTrue(AutoChain.apply(m) > 0);
        Assert.assertTrue(m.endpoint("getPetById").path.contains("${petId}"), m.endpoint("getPetById").path);
        Assert.assertTrue(m.endpoint("addPet").extract.stream().anyMatch(x -> "petId".equals(x.var) && "$.id".equals(x.jsonPath)));
        Assert.assertEquals(m.environments.get(0).variables.get("petId"), before.substring(before.lastIndexOf('/') + 1), "original sample kept as the default");
        Assert.assertEquals(AutoChain.apply(m), 0, "applying twice changes nothing");
        Assert.assertTrue(AutoChain.detect(m).isEmpty(), "nothing left to propose");

        TestCase add = tc("addPet"), get = tc("getPetById"), del = tc("deletePet"), other = tc("findPetsByStatus");
        Assert.assertTrue(AutoChain.rank(m, add) < AutoChain.rank(m, other));
        Assert.assertTrue(AutoChain.rank(m, other) < AutoChain.rank(m, del));
        Assert.assertEquals(AutoChain.rank(m, get), 100);

        String id = m.links.stream().filter(l -> "getPetById".equals(l.toEndpointId)).findFirst().orElseThrow().id;
        AutoChain.remove(m, id);
        Assert.assertEquals(m.endpoint("getPetById").path, before, "consumer gets its original value back");
    }

    private static TestCase tc(String endpoint) {
        TestCase t = new TestCase();
        t.endpointId = endpoint;
        t.category = "POSITIVE";
        return t;
    }
}
