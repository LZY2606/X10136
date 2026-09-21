package ctr.tests;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class AllTests {
    public static void main(String[] args) {
        List<Class<?>> classes = List.of(
                GeometryTests.class,
                RegionTests.class,
                TransformTests.class,
                PathPlannerTests.class,
                RegistryTests.class,
                JobServiceTests.class,
                PersistenceTests.class);
        int passed = 0;
        long started = System.nanoTime();
        List<String> failures = new ArrayList<>();
        for (Class<?> testClass : classes) {
            for (Method method : testClass.getDeclaredMethods()) {
                if (!method.getName().startsWith("test") || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    Object instance = testClass.getDeclaredConstructor().newInstance();
                    method.setAccessible(true);
                    method.invoke(instance);
                    passed++;
                    System.out.println("PASS " + testClass.getSimpleName() + "." + method.getName());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failures.add(testClass.getSimpleName() + "." + method.getName() + ": " + cause);
                    System.out.println("FAIL " + testClass.getSimpleName() + "." + method.getName() + " — " + cause);
                }
            }
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.println("Tests run: " + (passed + failures.size()) + ", failures: " + failures.size()
                + ", elapsed: " + seconds + "s");
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }
}
