package com.api.product;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.api.product.core.engine.ModelRunner;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.engine.WsClient;
import com.api.product.core.importer.WebSocketBuilder;
import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Mutation;
import com.api.product.core.model.ApiModel.TestCase;

/** WebSocket adapter against a tiny in-process server (handshake + frames written by hand): /echo, /secure (401), /strict (closes 1007 on non-JSON). */
public class WebSocketTest {

    private ServerSocket server;
    private int port;

    @BeforeClass
    public void start() throws Exception {
        server = new ServerSocket(0, 20, java.net.InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterClass
    public void stop() throws Exception {
        server.close();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while ((b = in.read()) >= 0) {
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
                if (state == 4) break;
            }
            String req = head.toString(StandardCharsets.UTF_8);
            String path = req.split(" ")[1];
            String key = null;
            for (String line : req.split("\r\n")) if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            if (path.equals("/secure") && !req.toLowerCase().contains("authorization: bearer ok")) {
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                int len = b1 & 0x7F;
                if (len == 126) len = (in.read() << 8) | in.read();
                byte[] mask = in.readNBytes(4);
                byte[] data = in.readNBytes(len);
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if (opcode == 8) {
                    out.write(new byte[] { (byte) 0x88, 0 });
                    return;
                }
                String msg = new String(data, StandardCharsets.UTF_8);
                if (path.equals("/strict") && !msg.trim().startsWith("{")) {
                    out.write(new byte[] { (byte) 0x88, 2, 0x03, (byte) 0xEF });   // close 1007 (invalid payload data)
                    out.flush();
                    return;
                }
                byte[] reply = ("echo:" + msg).getBytes(StandardCharsets.UTF_8);
                out.write(0x81);
                out.write(reply.length);
                out.write(reply);
                out.flush();
            }
        } catch (Exception ignored) {
            // test server: the client side asserts what matters
        }
    }

    @Test
    public void clientConnectsSendsAndReceives() {
        WsClient.Result r = WsClient.exchange("ws://localhost:" + port + "/echo", Map.of(), "{\"hello\":1}", 1, 3000);
        Assert.assertEquals(r.status, 101, r.error);
        Assert.assertEquals(r.messages.get(0), "echo:{\"hello\":1}");
    }

    @Test
    public void refusedHandshakeAndErrorClosesAreMappedToStatuses() {
        Assert.assertEquals(WsClient.exchange("ws://localhost:" + port + "/secure", Map.of(), "x", 1, 2000).status, 401);
        Assert.assertEquals(WsClient.exchange("ws://localhost:" + port + "/secure", Map.of("Authorization", "Bearer ok"), "x", 1, 2000).status, 101);
        WsClient.Result bad = WsClient.exchange("ws://localhost:" + port + "/strict", Map.of(), "not json", 1, 2000);
        Assert.assertEquals(bad.closeCode, 1007);
        Assert.assertEquals(bad.status, 400);
    }

    @Test
    public void runnerJudgesWebSocketPositiveAndNegative() throws Exception {
        ApiModel m = WebSocketBuilder.newModel("ws://localhost:" + port + "/strict", "{\"hello\":1}", 1, "echo:");
        m.testCases.get(0).lifecycle = "ACTIVE";
        TestCase bad = new TestCase();
        bad.id = "TC_ws_BAD";
        bad.endpointId = m.endpoints.get(0).id;
        bad.category = "WRONG_TYPE";
        bad.title = "message that is not JSON";
        bad.lifecycle = "ACTIVE";
        bad.mutation = new Mutation();
        bad.mutation.bodyOverride = "this is not json";
        m.testCases.add(bad);
        TestCase noSession = new TestCase();
        noSession.id = "TC_ws_ACCEPTED";
        noSession.endpointId = m.endpoints.get(0).id;
        noSession.category = "WRONG_TYPE";
        noSession.title = "garbage that the server wrongly accepts";
        noSession.lifecycle = "ACTIVE";
        noSession.mutation = new Mutation();
        noSession.mutation.bodyOverride = "{\"garbage\":true}";   // valid JSON for this server, so it echoes: accepted
        m.testCases.add(noSession);
        ModelRunner runner = new ModelRunner(m, RulePack.classpath("rules/api-test-rules.properties"), null, false);
        Map<String, TestResult> res = new java.util.LinkedHashMap<>();
        for (TestResult r : runner.run()) res.put(r.testCaseId, r);
        Assert.assertEquals(res.get(m.testCases.get(0).id).outcome, "PASS", String.valueOf(res.get(m.testCases.get(0).id).remark));
        Assert.assertEquals(res.get("TC_ws_BAD").outcome, "PASS", "server closed with 1007: a proper rejection");
        Assert.assertEquals(res.get("TC_ws_ACCEPTED").outcome, "FAIL", "an echoed answer to a bad message is an accepted input");
    }
}
