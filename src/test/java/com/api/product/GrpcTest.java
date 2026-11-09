package com.api.product;

import java.util.LinkedHashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.api.product.core.engine.GrpcClient;
import com.api.product.core.engine.ModelRunner;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.importer.GrpcImporter;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;

/** gRPC adapter against an in-process server (standard Health service + reflection): discovery, calls, status mapping, runner judging. */
public class GrpcTest {

    private Server server;
    private int port;

    @BeforeClass
    public void start() throws Exception {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("demo.Service", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
        server = NettyServerBuilder.forAddress(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
                .addService(health.getHealthService()).addService(ProtoReflectionService.newInstance()).build().start();
        port = server.getPort();
    }

    @AfterClass
    public void stop() {
        server.shutdownNow();
    }

    @Test
    public void discoversMethodsAndCallsThem() throws Exception {
        ApiModel m = GrpcImporter.fromServer("grpc://localhost:" + port);
        ApiModel.Endpoint check = m.endpoint("grpc_grpc_health_v1_Health_Check");
        Assert.assertNotNull(check, "Health/Check discovered: " + m.endpoints.stream().map(e -> e.id).toList());
        Assert.assertEquals(check.method, "GRPC");
        Assert.assertEquals(check.path, "/grpc.health.v1.Health/Check");
        Assert.assertTrue(m.api.source.warnings.stream().anyMatch(w -> w.contains("streaming")), "Watch is a streaming method and is reported");

        GrpcClient.Result ok = GrpcClient.call("localhost", port, false, "/grpc.health.v1.Health/Check", "{\"service\":\"demo.Service\"}", new LinkedHashMap<>(), 5000);
        Assert.assertEquals(ok.status, 200, ok.error);
        Assert.assertTrue(ok.body.contains("SERVING"), ok.body);
        GrpcClient.Result missing = GrpcClient.call("localhost", port, false, "/grpc.health.v1.Health/Check", "{\"service\":\"nope\"}", new LinkedHashMap<>(), 5000);
        Assert.assertEquals(missing.status, 404, "NOT_FOUND maps to 404");
        GrpcClient.Result wrongType = GrpcClient.call("localhost", port, false, "/grpc.health.v1.Health/Check", "{\"service\":{\"x\":1}}", new LinkedHashMap<>(), 5000);
        Assert.assertTrue(wrongType.unsendable);
    }

    @Test
    public void runnerJudgesPositiveNegativeAndNotApplicable() throws Exception {
        ApiModel m = GrpcImporter.fromServer("grpc://localhost:" + port);
        String id = "grpc_grpc_health_v1_Health_Check";
        m.testCases.forEach(t -> t.lifecycle = "ACTIVE");
        TestCase pos = m.testCases.stream().filter(t -> t.endpointId.equals(id)).findFirst().orElseThrow();
        ((com.fasterxml.jackson.databind.node.ObjectNode) m.endpoint(id).requestBody).put("service", "demo.Service");

        TestCase notExisting = new TestCase();
        notExisting.id = "TC_grpc_NOT_EXISTING";
        notExisting.endpointId = id;
        notExisting.category = "NOT_EXISTING";
        notExisting.title = "unknown service name";
        notExisting.lifecycle = "ACTIVE";
        notExisting.mutation = new Mutation();
        notExisting.mutation.setBodyFields.put("service", JsonNodeFactory.instance.textNode("ZZNOTEXIST999"));
        m.testCases.add(notExisting);

        TestCase wrong = new TestCase();
        wrong.id = "TC_grpc_WRONG_TYPE";
        wrong.endpointId = id;
        wrong.category = "WRONG_TYPE";
        wrong.title = "number instead of text";
        wrong.lifecycle = "ACTIVE";
        wrong.mutation = new Mutation();
        wrong.mutation.setBodyFields.put("service", JsonNodeFactory.instance.arrayNode().add("x"));
        m.testCases.add(wrong);

        Map<String, TestResult> res = new LinkedHashMap<>();
        for (TestResult r : new ModelRunner(m, RulePack.classpath("rules/api-test-rules.properties"), null, false).run()) res.put(r.testCaseId, r);
        Assert.assertEquals(res.get(pos.id).outcome, "PASS", String.valueOf(res.get(pos.id).remark));
        Assert.assertEquals(res.get("TC_grpc_NOT_EXISTING").outcome, "PASS", String.valueOf(res.get("TC_grpc_NOT_EXISTING").remark));
        Assert.assertEquals(res.get("TC_grpc_WRONG_TYPE").outcome, "SKIPPED");
    }
}
