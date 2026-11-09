package com.api.product.core.importer;

import java.util.List;

import com.api.product.core.engine.GrpcClient;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * gRPC adapter. Discovers the services of a live server through gRPC reflection and creates one request (method GRPC) per unary method
 * with a JSON sample of the request message. Streaming methods are reported and skipped. The application URL is stored as http(s)://host:port
 * (https = TLS) so the host allow-list and production checks work as for every other API.
 */
public final class GrpcImporter {

    private static final ObjectMapper M = new ObjectMapper();

    private GrpcImporter() {}

    public static ApiModel fromServer(String address) throws Exception {
        String a = address.trim();
        boolean tls = a.startsWith("grpcs://") || a.startsWith("https://") || a.endsWith(":443");
        a = a.replaceFirst("^(grpcs?|https?)://", "").replaceAll("/.*$", "");
        String host = a;
        int port = tls ? 443 : 80;
        int i = a.lastIndexOf(':');
        if (i > 0) {
            host = a.substring(0, i);
            try {
                port = Integer.parseInt(a.substring(i + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Enter the gRPC address as host:port, for example grpc://localhost:50051");
            }
        }
        if (host.isBlank()) throw new IllegalArgumentException("Enter the gRPC address as host:port, for example grpc://localhost:50051");
        List<GrpcClient.Method> methods = GrpcClient.discover(host, port, tls);
        ApiModel m = new ApiModel();
        m.api.name = host.replaceAll("[^A-Za-z0-9]+", "_") + "_grpc";
        m.api.source.type = "grpc";
        m.api.source.ref = address;
        m.api.contractMode = "off";
        Environment env = new Environment();
        env.name = "DEFAULT";
        env.baseUrl = (tls ? "https://" : "http://") + host + ":" + port;
        m.environments.add(env);
        m.api.source.warnings.add("gRPC reflection is enabled on this server. Useful for testing; production servers should disable it (OWASP API8).");
        for (GrpcClient.Method gm : methods) {
            if (!gm.unary) {
                m.api.source.warnings.add(gm.fullMethod + ": streaming methods are not supported yet and were skipped.");
                continue;
            }
            Endpoint ep = new Endpoint();
            ep.id = "grpc_" + gm.service.replaceAll("[^A-Za-z0-9]+", "_") + "_" + gm.name;
            ep.name = gm.service.substring(gm.service.lastIndexOf('.') + 1) + "/" + gm.name;
            ep.module = gm.service.substring(gm.service.lastIndexOf('.') + 1);
            ep.method = "GRPC";
            ep.path = gm.fullMethod;
            ep.contentType = "application/grpc";
            ep.requestBody = M.readTree(gm.sampleJson);
            ep.idempotent = gm.name.matches("(?i)^(get|list|find|check|read|search|describe|query|watch|ping|echo|empty|health).*");
            m.endpoints.add(ep);
            TestCase t = new TestCase();
            t.id = "TC_" + ep.id + "_POSITIVE";
            t.endpointId = ep.id;
            t.category = "POSITIVE";
            t.title = ep.name + " - valid request";
            t.lifecycle = "DRAFT";
            t.origin = "generated";
            m.testCases.add(t);
        }
        if (m.endpoints.isEmpty()) m.api.source.warnings.add("No unary methods were found.");
        return m;
    }
}
