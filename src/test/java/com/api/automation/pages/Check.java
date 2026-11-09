package com.api.automation.pages;

/** One verification line: what was expected, what came back, pass/fail. */
public class Check {

    public final String name;
    public final String expected;
    public final String actual;
    public final boolean pass;

    public Check(String name, String expected, String actual, boolean pass) {
        this.name = name;
        this.expected = expected;
        this.actual = actual;
        this.pass = pass;
    }

    @Override
    public String toString() {
        return (pass ? "✔ " : "✗ ") + name + " | expected: " + expected + " | actual: " + actual;
    }
}
