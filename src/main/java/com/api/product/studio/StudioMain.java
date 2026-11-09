package com.api.product.studio;

import java.net.BindException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Starts Studio: java -cp ... com.api.product.studio.StudioMain (or studio.bat). Default http://localhost:8099.
 * If that port is already taken by another Studio window, the existing one is opened instead; if another program uses it, the next free port is used.
 */
public final class StudioMain {

    private StudioMain() {}

    public static void main(String[] args) throws Exception {
        int wanted = Integer.getInteger("studio.port", 8099);
        StudioServer s = null;
        int port = wanted;
        for (int i = 0; i < 11 && s == null; i++) {
            port = wanted + i;
            StudioServer candidate = new StudioServer(port);
            try {
                candidate.start();
                s = candidate;
            } catch (BindException busy) {
                if (isStudio(port)) {
                    System.out.println("API Quality Studio is already running at http://localhost:" + port + " - opening it.");
                    open(port);
                    return;
                }
                System.out.println("Port " + port + " is used by another program; trying " + (port + 1) + " ...");
            }
        }
        if (s == null) {
            System.out.println("No free port between " + wanted + " and " + (wanted + 10) + ". Close the program that uses them, or start with -Dstudio.port=<number>.");
            System.exit(1);
        }
        System.out.println("API Quality Studio running at http://localhost:" + port + "  (close this window or press Ctrl+C to stop)");
        System.out.println("Your projects and reports are stored in: " + java.nio.file.Paths.get("").toAbsolutePath());
        if (Boolean.getBoolean("studio.open")) open(port);
        final StudioServer running = s;
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop));
        Thread.currentThread().join();
    }

    private static boolean isStudio(int port) {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.body().contains("API Quality Studio");
        } catch (Exception e) {
            return false;
        }
    }

    private static void open(int port) {
        if (!java.awt.Desktop.isDesktopSupported()) return;
        try {
            java.awt.Desktop.getDesktop().browse(URI.create("http://localhost:" + port + "/app"));
        } catch (Exception e) {
            System.out.println("Open http://localhost:" + port + " in your browser.");
        }
    }
}
