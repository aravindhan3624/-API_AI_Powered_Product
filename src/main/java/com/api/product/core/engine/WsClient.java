package com.api.product.core.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One WebSocket exchange: connect (with the given headers), send a text message, wait for the expected number of answers or a timeout,
 * close. The outcome is reduced to an HTTP-like status so the normal judging rules apply: 101 = connected, the handshake answer
 * (401, 403 ...) when the upgrade is refused, and a mapped status when the server closes with an error code (1009 too big = 413,
 * 1011 internal error = 500, other protocol/policy codes = 400).
 */
public final class WsClient {

    public static final class Result {
        public int status;
        public int closeCode = -1;
        public final List<String> messages = new CopyOnWriteArrayList<>();
        public long ms;
        public String error;
        public boolean timedOut;
    }

    private WsClient() {}

    public static Result exchange(String url, Map<String, String> headers, String message, int expectMessages, long timeoutMs) {
        Result r = new Result();
        long t0 = System.nanoTime();
        CountDownLatch done = new CountDownLatch(Math.max(1, expectMessages));
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger(-1);
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            } };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustAll, new SecureRandom());
            HttpClient c = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(8)).build();
            WebSocket.Builder wb = c.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8));
            if (headers != null) headers.forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Content-Type") && !k.equalsIgnoreCase("Host") && !k.toLowerCase().startsWith("sec-websocket")) wb.header(k, v);
            });
            CompletableFuture<WebSocket> f = wb.buildAsync(URI.create(url), new WebSocket.Listener() {
                private final StringBuilder part = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    part.append(data);
                    if (last) {
                        r.messages.add(part.toString());
                        part.setLength(0);
                        done.countDown();
                    }
                    ws.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    code.set(statusCode);
                    closed.countDown();
                    while (done.getCount() > 0) done.countDown();
                    return null;
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    r.error = error.getClass().getSimpleName() + ": " + error.getMessage();
                    closed.countDown();
                    while (done.getCount() > 0) done.countDown();
                }
            });
            WebSocket ws;
            try {
                ws = f.get(timeoutMs + 8000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException || e instanceof CompletionException ? e.getCause() : e;
                if (cause instanceof WebSocketHandshakeException) {
                    r.status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
                    r.error = "handshake refused with HTTP " + r.status;
                } else {
                    r.error = cause == null ? e.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
                r.ms = (System.nanoTime() - t0) / 1_000_000;
                return r;
            }
            r.status = 101;
            if (message != null && !message.isEmpty()) ws.sendText(message, true).get(timeoutMs, TimeUnit.MILLISECONDS);
            ws.request(1);
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) r.timedOut = true;
            if (closed.getCount() > 0) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    ws.abort();
                }
            }
            if (code.get() > 0 && code.get() != 1000 && code.get() != 1001) {
                r.closeCode = code.get();
                r.status = code.get() == 1009 ? 413 : code.get() == 1011 ? 500 : code.get() == 1008 ? 403 : 400;
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        r.ms = (System.nanoTime() - t0) / 1_000_000;
        return r;
    }
}
