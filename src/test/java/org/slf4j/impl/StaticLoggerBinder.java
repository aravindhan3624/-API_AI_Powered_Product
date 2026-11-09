package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * No-operation SLF4J 1.7 binding (same as the slf4j-nop artifact).
 * TestNG / RestAssured log through SLF4J; without a binding SLF4J prints a 3-line
 * "Failed to load class org.slf4j.impl.StaticLoggerBinder" warning in red at every run.
 */
public class StaticLoggerBinder implements LoggerFactoryBinder {

    public static String REQUESTED_API_VERSION = "1.6.99";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();
    private final ILoggerFactory loggerFactory = new NOPLoggerFactory();

    private StaticLoggerBinder() {}

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return NOPLoggerFactory.class.getName();
    }
}
