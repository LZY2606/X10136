package ctstation;

import java.lang.reflect.Method;

/** Discovers *Test classes in this package and runs their run(Asserts). */
public final class AllTests {

    public static void main(String[] args) throws Exception {
        String[] suite = {
                "ctstation.AntimeridianTest",
                "ctstation.BoundaryTest",
                "ctstation.InvertibleEdgeTest",
                "ctstation.StableTieTest",
                "ctstation.LoopInconsistencyTest",
                "ctstation.BatchPartialFailureTest",
                "ctstation.HistoryBindingTest",
                "ctstation.NonFiniteInputTest",
                "ctstation.ExportImportDeterminismTest",
                "ctstation.ValidationTest"
        };
        Asserts asserts = new Asserts();
        for (String name : suite) {
            Class<?> c = Class.forName(name);
            Method m = c.getDeclaredMethod("run", Asserts.class);
            m.setAccessible(true);
            m.invoke(null, asserts);
        }
        asserts.finish();
    }
}
