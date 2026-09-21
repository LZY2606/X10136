package ctstation;

import java.util.ArrayList;
import java.util.List;

/** Tiny zero-dependency assertion framework used by the built-in suite. */
public final class Asserts {
    public int passed;
    public int failed;
    public final List<String> failures = new ArrayList<>();
    private String group = "";

    public void group(String g) { this.group = g; }

    public void check(boolean cond, String message) {
        if (cond) {
            passed++;
        } else {
            failed++;
            failures.add(group + ": " + message);
            System.out.println("FAIL " + group + ": " + message);
        }
    }

    public void eq(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected),
                message + " (expected=" + expected + ", actual=" + actual + ")");
    }

    public void approx(double actual, double expected, double eps, String message) {
        check(Math.abs(actual - expected) <= eps,
                message + " (expected~" + expected + ", actual=" + actual + ")");
    }

    public void throwsCode(Runnable r, String code, String message) {
        String found = null;
        try {
            r.run();
        } catch (AppException ae) {
            found = ae.code;
        }
        eq(found, code, message);
    }

    public void finish() {
        System.out.println("TESTS passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            for (String f : failures) System.out.println("  - " + f);
            throw new AssertionError(failed + " test(s) failed");
        }
    }
}
