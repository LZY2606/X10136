package ctstation;

import java.util.List;
import java.util.Map;

/** Equal-error chains must break ties by a stable, documented rule. */
public final class StableTieTest {

    public static void run(Asserts a) {
        a.group("stable-tie");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "A", "projected");
        TestSupport.crs(svc, "B", "projected");
        TestSupport.crs(svc, "C", "projected");

        // Three parallel A->C chains with identical transform/accuracy:
        // direct edges Z_DIRECT and A_DIRECT (both A->C), and via B.
        double[] same = {1, 0, 0, 1, 0, 0};
        TestSupport.edge(svc, "Z_DIRECT", "A", "C", same, null, 0.5, 0.5, Boolean.TRUE);
        TestSupport.edge(svc, "A_DIRECT", "A", "C", same, null, 0.5, 0.5, Boolean.TRUE);
        TestSupport.edge(svc, "M_AB", "A", "B", same, null, 0.25, 0.25, Boolean.TRUE);
        TestSupport.edge(svc, "M_BC", "B", "C", same, null, 0.25, 0.25, Boolean.TRUE);

        Map<String, Object> j = TestSupport.job(svc, "A", "C", TestSupport.pointGeo(0, 0));
        Map<String, Object> item = TestSupport.item(j, 0);
        a.eq(item.get("selectedPath"), "A_DIRECT",
                "tie broken by lexicographically smallest edge id");

        @SuppressWarnings("unchecked")
        List<Object> cands = (List<Object>) item.get("candidates");
        a.eq(cands.size(), 3, "all three simple chains enumerated");
        a.eq(((Map<?, ?>) cands.get(0)).get("path"), "A_DIRECT", "candidate order stable (1)");
        a.eq(((Map<?, ?>) cands.get(1)).get("path"), "M_AB>M_BC", "candidate order stable (2)");
        a.eq(((Map<?, ?>) cands.get(2)).get("path"), "Z_DIRECT", "candidate order stable (3)");

        // Lower error wins regardless of id ordering.
        Service svc2 = TestSupport.freshService();
        TestSupport.crs(svc2, "A", "projected");
        TestSupport.crs(svc2, "C", "projected");
        TestSupport.edge(svc2, "AAA", "A", "C", same, null, 0.9, 0.9, Boolean.TRUE);
        TestSupport.edge(svc2, "ZZZ", "A", "C", same, null, 0.1, 0.1, Boolean.TRUE);
        Map<String, Object> j2 = TestSupport.job(svc2, "A", "C", TestSupport.pointGeo(0, 0));
        a.eq(TestSupport.item(j2, 0).get("selectedPath"), "ZZZ", "smaller error wins over id order");
    }
}
