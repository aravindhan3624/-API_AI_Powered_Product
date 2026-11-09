package com.api.product.core.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.store.SecretVault;

/**
 * Answers "is this really the API?" before any test is sent. A web page address is not an API address: this probe asks a few of the
 * project's own paths with OPTIONS (never executes anything) and, when needed, GET on read-only paths. A 404 means the route is not
 * there; any other answer (200, 401, 403, 405 ...) proves the route exists. It can also try likely API base addresses
 * (/api, /api/v1, the page's own path ...) and propose the one that works.
 */
public final class TargetProbe {

    public static final class Hit {
        public String path;
        public String url;
        public int options;
        public int get;
        public String error;
        /** The server answered this path differently from a made-up path, so the route really exists. */
        public boolean proven;
        public boolean exists() {
            return proven;
        }
    }

    public static final class Result {
        public String baseUrl;
        public int tested;
        public int found;
        public boolean unknown;           // nothing could be probed (all paths contain unresolved variables)
        public boolean ok;
        public String message = "";
        public final List<Hit> hits = new ArrayList<>();
        public final List<Candidate> candidates = new ArrayList<>();
    }

    public static final class Candidate {
        public String baseUrl;
        public int found;
        public int tested;
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)\\??}");
    private static final Pattern READ = Pattern.compile("(?i).*(get|list|search|find|fetch|view|query|report|status|info|config|check|meta).*");
    private final HttpClient http;

    public TargetProbe() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        } };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Probes the project's endpoints against {@code baseUrl}. */
    public Result probe(ApiModel m, String baseUrl, int maxPaths) {
        Result r = new Result();
        r.baseUrl = baseUrl;
        Map<String, String> vars = new LinkedHashMap<>();
        if (!m.environments.isEmpty()) m.environments.get(0).variables.forEach((k, v) -> {
            if (v != null && !v.startsWith("${ENV:")) vars.put(k, v);
        });
        String base = resolve(baseUrl, vars);
        if (base == null) {
            r.unknown = true;
            r.message = "The application URL still contains a variable. Enter the real address first.";
            return r;
        }
        String rnd = "/__aqs_probe_" + Long.toString(System.nanoTime(), 36);
        String bo = fp("OPTIONS", join(base, rnd)), bg = fp("GET", join(base, rnd));
        for (Endpoint ep : pick(m, maxPaths)) {
            String path = resolve(ep.path == null ? "" : ep.path.replaceAll("\\?.*", ""), vars);
            if (path == null) continue;
            Hit h = new Hit();
            h.path = path;
            h.url = join(base, path);
            try {
                String fo = fp("OPTIONS", h.url);
                h.options = code(fo);
                h.proven = differs(fo, bo);
                if (!h.proven && ("GET".equals(ep.method) || READ.matcher(path).matches())) {
                    String fg = fp("GET", h.url);
                    h.get = code(fg);
                    h.proven = differs(fg, bg);
                }
                if (h.options == 0 && h.get == 0) h.error = "no answer";
            } catch (Exception e) {
                h.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            r.hits.add(h);
        }
        r.tested = r.hits.size();
        for (Hit h : r.hits) if (h.proven) r.found++;
        int cb = Math.max(code(bo), code(bg));
        if (r.tested == 0) {
            r.unknown = true;
            r.message = "Nothing could be checked in advance (every API path needs a value that is not known yet).";
        } else if (r.found > 0) {
            r.ok = true;
            r.message = r.found + " of " + r.tested + " checked APIs exist at " + base + ".";
        } else if (r.hits.stream().allMatch(h -> h.error != null)) {
            r.message = "Cannot reach " + base + " from this PC (" + r.hits.get(0).error + "). Check the address, VPN/proxy and firewall.";
        } else if (cb >= 200 && cb < 400) {
            r.message = "The server at " + base + " answers every address the same way, even made-up paths (HTTP " + cb + "). That is typical of a website that serves one page for everything - this is not the API address.";
        } else if (cb == 404 || cb == 0) {
            r.message = "None of the " + r.tested + " checked APIs exist at " + base + " (all answered 404). This looks like the wrong address or the wrong base path.";
        } else {
            r.unknown = true;
            r.message = "The server answers every path with HTTP " + cb + " (sign-in or method rules apply before routing), so the paths cannot be confirmed in advance. The first test run will show whether they work.";
        }
        return r;
    }

    /** Tries likely API base addresses for a site URL and ranks them by how many of the project's paths exist there. */
    public Result findBase(ApiModel m, String siteUrl) {
        Result best = new Result();
        best.baseUrl = siteUrl;
        URI u;
        try {
            u = URI.create(siteUrl.trim());
        } catch (Exception e) {
            best.message = "That is not a valid URL.";
            return best;
        }
        if (u.getHost() == null) {
            best.message = "That is not a valid URL.";
            return best;
        }
        String origin = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String page = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
        String pageDir = page.replaceAll("/[^/]*\\.[A-Za-z0-9]+$", "");
        Set<String> cands = new LinkedHashSet<>();
        cands.add(origin);
        for (String p : new String[] { "/api", "/api/v1", "/api/v2", "/rest", "/services", "/gateway", "/app/api" }) cands.add(origin + p);
        if (!pageDir.isEmpty()) {
            cands.add(origin + pageDir);
            for (String p : new String[] { "/api", "/api/v1", "/rest", "/services" }) cands.add(origin + pageDir + p);
            String first = pageDir.replaceFirst("^(/[^/]+).*$", "$1");
            if (!first.equals(pageDir)) cands.add(origin + first);
        }
        if (!m.environments.isEmpty() && m.environments.get(0).baseUrl != null) {
            try {
                String own = m.environments.get(0).baseUrl;
                String path = URI.create(own.replaceAll("\\$\\{[^}]*}", "x")).getPath();
                if (path != null && !path.isBlank() && !path.equals("/")) cands.add(origin + path.replaceAll("/+$", ""));
            } catch (Exception ignored) {
                // collection base not parseable: ignore
            }
        }
        // addresses the collection itself defines (baseUrl, api_base_url ...): the user's own file knows where its API lives
        if (!m.environments.isEmpty()) {
            Map<String, String> ev = new LinkedHashMap<>();
            m.environments.get(0).variables.forEach((k, v) -> {
                if (v != null && !v.startsWith("${ENV:")) ev.put(k, v);
            });
            for (Map.Entry<String, String> e : ev.entrySet()) {
                if (!e.getKey().toLowerCase().matches(".*(base.?url|api.?base|host|server).*")) continue;
                String val = resolve(e.getValue(), ev);
                if (val != null && val.matches("(?i)^https?://[^\s/]+.*")) cands.add(val.replaceAll("/+$", ""));
            }
        }
        for (String c : cands) {
            Result r = probe(m, c, 4);
            Candidate cd = new Candidate();
            cd.baseUrl = c;
            cd.found = r.found;
            cd.tested = r.tested;
            best.candidates.add(cd);
            if (c.equals(origin) && r.tested > 0 && r.hits.stream().allMatch(h -> h.error != null)) {
                best.message = "Cannot reach " + origin + " from this PC. Check the address, VPN/proxy and firewall.";
                return best; // host unreachable: no point trying more candidates
            }
        }
        best.candidates.sort((a, b) -> b.found - a.found);
        Candidate top = best.candidates.isEmpty() ? null : best.candidates.get(0);
        if (top != null && top.found > 0) {
            best.ok = true;
            best.baseUrl = top.baseUrl;
            best.message = "The API answers at " + top.baseUrl + " (" + top.found + " of " + top.tested + " checked APIs exist).";
        } else {
            best.message = "None of the usual API addresses on " + origin + " answered for this collection's paths. Ask the API team for the exact base URL (the part before paths such as "
                    + firstPath(m) + ").";
        }
        return best;
    }

    private static String firstPath(ApiModel m) {
        return m.endpoints.isEmpty() ? "/..." : String.valueOf(m.endpoints.get(0).path);
    }

    /** Distinct, preferably read-only, non-login paths without unresolved variables. */
    private List<Endpoint> pick(ApiModel m, int max) {
        List<Endpoint> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int pass = 0; pass < 2 && out.size() < max; pass++) {
            for (Endpoint ep : m.endpoints) {
                if (out.size() >= max) break;
                String p = ep.path == null ? "" : ep.path;
                if (p.toLowerCase().matches(".*(login|signin|logout|password|otp).*") && pass == 0) continue;
                boolean read = "GET".equals(ep.method) || READ.matcher(p).matches();
                if ((pass == 0) != read) continue;
                if (seen.add(p.replaceAll("\\?.*", ""))) out.add(ep);
            }
        }
        return out;
    }

    /** "status|content-type" of a request, or "0|" when nothing answered. */
    private String fp(String method, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).method(method, HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            String ct = r.headers().firstValue("content-type").orElse("").toLowerCase().replaceAll(";.*", "").trim();
            return r.statusCode() + "|" + ct;
        } catch (Exception e) {
            return "0|";
        }
    }

    private static int code(String fp) {
        return Integer.parseInt(fp.substring(0, fp.indexOf('|')));
    }

    private static boolean differs(String f, String base) {
        int c = code(f);
        return c > 0 && c != 404 && c < 502 && !f.equals(base);
    }

    private int send(String method, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
        return r.statusCode();
    }

    /** Replaces ${var} with known non-secret values; null when something stays unresolved. */
    static String resolve(String s, Map<String, String> vars) {
        String cur = s == null ? "" : s;
        for (int i = 0; i < 5; i++) {
            Matcher m = VAR.matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                String v = vars.get(m.group(1));
                if (v == null) v = SecretVault.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
                any = true;
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!any) break;
        }
        return VAR.matcher(cur).find() ? null : cur;
    }

    private static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (path.startsWith("http")) return path;
        return b + (path.startsWith("/") ? path : "/" + path);
    }
}
