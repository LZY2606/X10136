package ctstation;

import java.util.Map;

/** A new edge disagreeing with an existing loop is quarantined pending. */
public final class LoopInconsistencyTest {

    public static void run(Asserts a) {
        a.group("loop-inconsistency");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "A", "projected");
        TestSupport.crs(svc, "B", "projected");
        TestSupport.crs(svc, "C", "projected");

        double[] ab = {1, 0, 0, 1, 10, 0};
        double[] bc = {1, 0, 0, 1, 0, 20};
        TestSupport.edge(svc, "AB", "A", "B", ab, null, 0.01, 0.01, Boolean.TRUE);
        TestSupport.edge(svc, "BC", "B", "C", bc, null, 0.01, 0.01, Boolean.TRUE);

        // Consistent closing edge: A->C equals translation (10,20).
        TestSupport.edge(svc, "AC_OK", "A", "C", new double[]{1, 0, 0, 1, 10, 20},
                null, 0.01, 0.01, Boolean.TRUE);
        Edge okEdge = svc.store.edges.get("AC_OK");
        a.eq(okEdge.state, "active", "consistent edge is active immediately");

        // Inconsistent closing edge: translation (10, 500) is far off.
        TestSupport.edge(svc, "AC_BAD", "A", "C", new double[]{1, 0, 0, 1, 10, 500},
                null, 0.01, 0.01, Boolean.TRUE);
        Edge badEdge = svc.store.edges.get("AC_BAD");
        a.eq(badEdge.state, "pending", "disagreeing edge goes pending");
        a.check(badEdge.inconsistency != null, "inconsistency evidence is stored");

        // Pending edge is excluded from default paths.
        Map<String, Object> j = TestSupport.job(svc, "A", "C", TestSupport.pointGeo(0, 0));
        Map<String, Object> item = TestSupport.item(j, 0);
        a.check(!"AC_BAD".equals(item.get("selectedPath")),
                "pending edge AC_BAD skipped in default path selection");
        a.eq(item.get("selectedPath"), "AC_OK", "active direct edge wins by error");
        @SuppressWarnings("unchecked")
        java.util.List<Object> cands = (java.util.List<Object>) item.get("candidates");
        boolean pendingAbsent = true;
        for (Object o : cands) {
            if (String.valueOf(((java.util.Map<?, ?>) o).get("path")).contains("AC_BAD")) {
                pendingAbsent = false;
            }
        }
        a.check(pendingAbsent, "pending edge does not appear in candidates");

        // Confirm without reason is rejected.
        a.throwsCode(() -> svc.confirmEdge("AC_BAD", Json.object("reason", "  ", "state", "active")),
                "bad-confirm", "blank reason rejected");

        // Confirm with reason creates a new version and activates.
        int before = svc.store.versionSeq;
        Map<String, Object> confirmed = svc.confirmEdge("AC_BAD",
                Json.object("reason", "survey check: legacy grid offset 480", "state", "active"));
        a.eq(confirmed.get("state"), "active", "edge active after confirmation");
        a.check(svc.store.versionSeq == before + 1, "confirmation bumps version");
        Map<String, Object> last = svc.store.versions.get(svc.store.versions.size() - 1);
        a.eq(last.get("action"), "confirm-edge", "version log records confirm-edge");

        // Confirming a non-pending edge fails.
        a.throwsCode(() -> svc.confirmEdge("AC_OK", Json.object("reason", "x")),
                "not-pending", "only pending edges can be confirmed");
    }
}
