package com.api.product.core.store;

/** Who is acting in the current request (team mode); falls back to the operating-system user when nobody is signed in. */
public final class Actor {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Actor() {}

    public static void set(String user) {
        if (user == null) CURRENT.remove();
        else CURRENT.set(user);
    }

    public static String current() {
        return CURRENT.get();
    }
}
