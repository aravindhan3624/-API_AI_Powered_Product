package com.api.product;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.api.product.ai.AiSettings;
import com.api.product.ai.HardwareProfile;
import com.api.product.ai.OllamaClient;
import com.sun.net.httpserver.HttpServer;

/** The model can live on another PC: address, timeouts and model choice follow that machine, not this one. A fake in-process server stands in for Ollama; no real model runs. */
public class AiRemoteConfigTest {

    private HttpServer stub;
    private int port;

    @BeforeClass
    public void up() throws Exception {
        stub = HttpServer.create(new InetSocketAddress(0), 0);   // all interfaces, so 127.0.0.2 also reaches it
        stub.createContext("/api/tags", ex -> {
            byte[] b = "{\"models\":[{\"name\":\"qwen2.5:3b\"},{\"name\":\"qwen2.5:14b\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        stub.start();
        port = stub.getAddress().getPort();
    }

    @AfterClass
    public void down() {
        if (stub != null) stub.stop(0);
    }

    @Test
    public void addressIsClassifiedAsThisPcOrAnotherMachine() {
        for (String u : new String[] { "http://localhost:11434", "http://127.0.0.1:11434", "http://[::1]:11434", "HTTP://LocalHost", "" })
            Assert.assertFalse(AiSettings.isRemoteUrl(u), u);
        for (String u : new String[] { "http://192.168.1.50:11434", "https://ai-box.corp.local:11434/", "http://10.0.0.7", "http://127.0.0.2:11434" })
            Assert.assertTrue(AiSettings.isRemoteUrl(u), u);
    }

    @Test
    public void connectTimeoutIsLongerForAnotherMachineAndCanBeSet() {
        AiSettings s = new AiSettings();
        Assert.assertEquals(s.connectMs(), 800);
        s.url = "http://192.168.1.50:11434";
        Assert.assertEquals(s.connectMs(), 4000);
        s.connectTimeoutMs = 9000;
        Assert.assertEquals(s.connectMs(), 9000);
        s.connectTimeoutMs = 99999;
        try {
            s.validate();
            Assert.fail("a timeout above 30000 ms must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void remoteHostPicksTheBestModelThereNotWhatThisPcCouldRun() {
        OllamaClient remote = new OllamaClient("http://127.0.0.2:" + port, "auto");
        Assert.assertTrue(remote.remote());
        Assert.assertTrue(remote.available(), "fake remote host must answer");
        Assert.assertEquals(remote.model(), "qwen2.5:14b");

        OllamaClient local = new OllamaClient("http://127.0.0.1:" + port, "auto");
        Assert.assertFalse(local.remote());
        Assert.assertTrue(local.available());
        Assert.assertTrue(HardwareProfile.tierOf(local.model()) <= HardwareProfile.detect().tier, "a local model must fit this PC");

        OllamaClient named = new OllamaClient("http://127.0.0.2:" + port, "qwen2.5:3b");
        Assert.assertTrue(named.available());
        Assert.assertEquals(named.model(), "qwen2.5:3b");
    }

    @Test
    public void addressAndTimeoutSurviveSaveAndLoad() throws Exception {
        Path f = Files.createTempFile("ai-settings", ".json");
        String old = System.getProperty("ai.settings");
        System.setProperty("ai.settings", f.toString());
        try {
            AiSettings s = new AiSettings();
            s.enabled = true;
            s.url = "http://192.168.1.50:11434";
            s.model = "qwen2.5:7b";
            s.connectTimeoutMs = 6000;
            s.validate();
            s.save();
            AiSettings r = AiSettings.load();
            Assert.assertEquals(r.url, "http://192.168.1.50:11434");
            Assert.assertEquals(r.model, "qwen2.5:7b");
            Assert.assertEquals(r.connectMs(), 6000);
            Assert.assertTrue(r.enabled);
        } finally {
            if (old == null) System.clearProperty("ai.settings"); else System.setProperty("ai.settings", old);
            Files.deleteIfExists(f);
        }
    }
}
