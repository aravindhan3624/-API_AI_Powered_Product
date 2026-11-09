package com.api.product.capture;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.api.product.core.engine.Redactor;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;

/**
 * Records the API calls a web application makes while a PERSON uses it in a visible, isolated browser window.
 * The person types the credentials, OTP and CAPTCHA in that window: this class never sees or stores them.
 * Only requests to the allow-listed hosts are recorded; cookies, tokens, passwords and OTP values are masked before
 * anything is kept; form field values are never read. Nothing is replayed or modified.
 */
public final class FlowCapture implements AutoCloseable {

    public static final class Entry {
        public String time;
        public String page;
        public String method;
        public String url;
        public int status;
        public String requestContentType;
        public String requestBody;
        public String responseContentType;
        public String responseBody;
        public long durationMs;
    }

    private static final Set<String> RECORDED = Set.of("xhr", "fetch", "document");
    private static final int MAX_BODY = 4000;

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    private final List<String> pages = Collections.synchronizedList(new ArrayList<>());
    private final Map<Request, Long> started = new ConcurrentHashMap<>();
    private final Set<String> allowedHosts;
    private Playwright pw;
    private Browser browser;
    private BrowserContext ctx;
    private volatile boolean running;

    public FlowCapture(Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) throw new IllegalArgumentException("Give at least one allowed host to record");
        this.allowedHosts = allowedHosts;
    }

    /** @param headless false for real use (a person works in the window); true only for automated smoke tests */
    public Page start(String startUrl, boolean headless) {
        pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(headless);
        String channel = System.getProperty("capture.channel", "msedge");   // installed Edge / Chrome: nothing to download
        if (!"bundled".equals(channel)) lo.setChannel(channel);
        browser = pw.chromium().launch(lo);
        ctx = browser.newContext();   // fresh profile: none of the person's own cookies, history or saved passwords
        Page page = ctx.newPage();
        page.onRequest(r -> {
            if (wanted(r)) started.put(r, System.nanoTime());
        });
        page.onResponse(this::record);
        page.onFrameNavigated(f -> {
            if (f == page.mainFrame() && allowed(f.url())) pages.add(f.url());
        });
        running = true;
        if (startUrl != null && !startUrl.isBlank()) {
            if (!allowed(startUrl)) throw new IllegalArgumentException("Start URL host is not in the allow-list");
            page.navigate(startUrl);
        }
        return page;
    }

    private boolean wanted(Request r) {
        return RECORDED.contains(r.resourceType().toLowerCase(Locale.ROOT)) && allowed(r.url());
    }

    private boolean allowed(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null && allowedHosts.stream().anyMatch(a -> h.equalsIgnoreCase(a) || h.endsWith("." + a.toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            return false;
        }
    }

    private void record(Response resp) {
        Request req = resp.request();
        if (!running || !wanted(req)) return;
        try {
            Entry e = new Entry();
            e.time = Instant.now().toString();
            e.page = req.frame().page().url();
            e.method = req.method();
            e.url = stripQuerySecrets(req.url());
            e.status = resp.status();
            Long t0 = started.remove(req);
            e.durationMs = t0 == null ? 0 : (System.nanoTime() - t0) / 1_000_000;
            e.requestContentType = req.headers().getOrDefault("content-type", "");
            String rb = req.postData();
            e.requestBody = rb == null ? null : cut(Redactor.mask(rb));
            String ct = resp.headers().getOrDefault("content-type", "");
            e.responseContentType = ct;
            if (ct.contains("json") || ct.contains("xml") || ct.contains("text")) {
                if (!"document".equalsIgnoreCase(req.resourceType())) e.responseBody = cut(Redactor.mask(resp.text()));
            }
            entries.add(e);
        } catch (Exception ignored) {
            // a response that cannot be read (redirect, aborted) is simply not recorded
        }
    }

    private static String stripQuerySecrets(String url) {
        return url.replaceAll("(?i)([?&](?:token|access_token|apikey|api_key|key|otp|password|secret|signature)=)[^&]*", "$1***");
    }

    private static String cut(String s) {
        return s == null || s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "...[truncated]";
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public List<String> pages() {
        synchronized (pages) {
            return new ArrayList<>(pages);
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        try {
            if (ctx != null) ctx.close();
            if (browser != null) browser.close();
            if (pw != null) pw.close();
        } catch (Exception ignored) {
            // closing an already closed browser
        }
    }
}
