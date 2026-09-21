package ctstation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Non-invertible edges must have no reverse arc. */
public final class InvertibleEdgeTest {

    public static void run(Asserts a) {
        a.group("invertible");
        Affine singular = new Affine(1, 0, 1e-15, 0, 0, 0);
        a.check(!singular.safelyInvertible(), "near-zero determinant is not safely invertible");
        Affine rot = new Affine(0, -1, 1, 0, 5, 6);
        Affine back = rot.inverse();
        // rot is x'=-y+5, y'=x+6; its inverse maps (3,4) -> (-2,2).
        double[] p = back.apply(3, 4);
        a.approx(p[0], -2, 1e-9, "inverse rotation x");
        a.approx(p[1], 2, 1e-9, "inverse rotation y");

        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "P", "projected");
        TestSupport.crs(svc, "Q", "projected");
        TestSupport.edge(svc, "GOOD", "P", "Q", new double[]{2, 0, 0, 2, 0, 0},
                null, 0.1, 0.1, null);
        TestSupport.edge(svc, "FLAT", "P", "Q", new double[]{1, 0, 0, 0, 0, 0},
                null, 0.1, 0.1, false);

        // Inverse of GOOD: Q -> P should work and apply factor 1/2.
        Map<String, Object> j = TestSupport.job(svc, "Q", "P", TestSupport.pointGeo(10, 20));
        Map<String, Object> item = TestSupport.item(j, 0);
        a.eq(item.get("status"), "success", "reverse of invertible edge succeeds");
        Map<String, Object> out = (Map<String, Object>) item.get("output");
        double[] c = (double[]) out.get("coordinates");
        a.approx(c[0], 5, 1e-9, "inverse factor applied");

        Map<String, Object> j2 = TestSupport.job(svc, "Q", "P", TestSupport.pointGeo(1, 1));
        @SuppressWarnings("unchecked")
        List<Object> cands = (List<Object>) ((Map<String, Object>) TestSupport.item(j2, 0)).get("candidates");
        boolean sawFlatInverse = false;
        for (Object o : cands) {
            Map<?, ?> row = (Map<?, ?>) o;
            if (String.valueOf(row.get("path")).contains("FLAT")) sawFlatInverse = true;
        }
        a.check(!sawFlatInverse, "FLAT inverse arc is absent from candidate enumeration");

        // Explicit invertible=false on an otherwise invertible matrix.
        Service svc2 = TestSupport.freshService();
        TestSupport.crs(svc2, "R", "projected");
        TestSupport.crs(svc2, "S", "projected");
        TestSupport.edge(svc2, "E", "R", "S", new double[]{1, 0, 0, 1, 0, 0},
                null, 0, 0, false);
        Map<String, Object> j3 = TestSupport.job(svc2, "S", "R", TestSupport.pointGeo(0, 0));
        Map<String, Object> err = (Map<String, Object>) TestSupport.item(j3, 0).get("error");
        a.eq(err.get("code"), "no-chain", "explicitly non-invertible edge has no reverse path");
    }
}
