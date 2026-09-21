package ctr.tests;

import ctr.ApiException;
public final class Asserts {
    private Asserts() {
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equal(Object actual, Object expected, String message) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(message + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void approx(double actual, double expected, double tolerance, String message) {
        if (!(Math.abs(actual - expected) <= tolerance)) {
            throw new AssertionError(message + " — expected <" + expected + " ± " + tolerance + "> but was <" + actual + ">");
        }
    }

    public static void throwsApi(Runnable runnable, int status, String fragment, String message) {
        try {
            runnable.run();
        } catch (ApiException e) {
            equal(e.status(), status, message + " status");
            check(e.getMessage().contains(fragment), message + " message: " + e.getMessage());
            return;
        }
        throw new AssertionError(message + " did not fail");
    }
}
