package com.api.automation.report;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Keeps the IntelliJ / Maven console clean:
 *  - Log4j2 (used by Apache POI) "could not find a logging implementation" ERROR line
 *  - SLF4J "Failed to load class org.slf4j.impl.StaticLoggerBinder" warning (3 lines)
 * Neither affects the tests; they only appear because no logging backend is configured.
 */
public final class QuietLogging {

    private static volatile boolean done;

    private QuietLogging() {}

    public static synchronized void init() {
        if (done) return;
        done = true;
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        System.setProperty("log4j2.statusLoggerLevel", "OFF");
        System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF");

        PrintStream err = System.err;
        try {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            touch("org.slf4j.LoggerFactory", "getILoggerFactory");
            touch("org.apache.logging.log4j.LogManager", "getContext");
        } finally {
            System.setErr(err);
        }
    }

    private static void touch(String cls, String method) {
        try {
            Class.forName(cls).getMethod(method).invoke(null);
        } catch (Throwable ignored) {
            // library not on the classpath - nothing to silence
        }
    }
}
