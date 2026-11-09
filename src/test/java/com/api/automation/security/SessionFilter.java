package com.api.automation.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * Browser-like session: keeps cookies between calls of one project run
 * (like axios withCredentials) and sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN.
 */
public final class SessionFilter implements Filter {

    /** How the session is sent on this call (negative tests). */
    public enum Tamper { NONE, NO_SESSION, TOKEN, CSRF }

    private static final Map<String, String> COOKIES = new ConcurrentHashMap<>();
    public static final SessionFilter INSTANCE = new SessionFilter();

    private SessionFilter() {}

    private static final ThreadLocal<Tamper> MODE = ThreadLocal.withInitial(() -> Tamper.NONE);

    public static void reset() {
        COOKIES.clear();
    }

    /** Negative test "No login / session": send this call without the session cookies. */
    public static void skipSession(boolean skip) {
        MODE.set(skip ? Tamper.NO_SESSION : Tamper.NONE);
    }

    /** Negative tests "Tampered token" / "Wrong CSRF token": send altered values on this call. */
    public static void tamper(Tamper t) {
        MODE.set(t == null ? Tamper.NONE : t);
    }

    /** Real value with its last characters changed, so it looks valid but its signature / match is broken. */
    public static String tamperValue(String v) {
        if (v == null || v.isEmpty()) return "tampered-value-0000";
        if (v.length() <= 6) return v + "X9";
        String tail = v.substring(v.length() - 6);
        String swap = tail.equals("Zz9Zz9") ? "Yy8Yy8" : "Zz9Zz9";
        return v.substring(0, v.length() - 6) + swap;
    }

    private static boolean authCookie(String name) {
        String n = name.toLowerCase();
        return !n.contains("xsrf") && !n.contains("csrf")
                && (n.contains("token") || n.contains("session") || n.contains("jwt") || n.contains("auth") || n.contains("sid"));
    }

    @Override
    public Response filter(FilterableRequestSpecification req, FilterableResponseSpecification res, FilterContext ctx) {
        Tamper mode = MODE.get();
        if (mode == Tamper.NO_SESSION) {
            return ctx.next(req, res);      // no cookies in, and the session cookies are not touched
        }
        COOKIES.forEach((k, v) -> {
            if (req.getCookies().hasCookieWithName(k)) return;
            req.cookie(k, mode == Tamper.TOKEN && authCookie(k) ? tamperValue(v) : v);
        });
        String xsrf = COOKIES.get("XSRF-TOKEN");
        if (xsrf != null && !req.getHeaders().hasHeaderWithName("X-XSRF-TOKEN")) {
            String v = java.net.URLDecoder.decode(xsrf, java.nio.charset.StandardCharsets.UTF_8);
            req.header("X-XSRF-TOKEN", mode == Tamper.CSRF ? tamperValue(v) : v);
        }
        Response r = ctx.next(req, res);
        if (mode != Tamper.NONE) return r;   // a tampered call never changes the stored session
        for (Cookie c : r.getDetailedCookies()) {
            if (c.getValue() == null || c.getValue().isEmpty()) COOKIES.remove(c.getName());
            else COOKIES.put(c.getName(), c.getValue());
        }
        return r;
    }
}
