package ctstation;

import java.util.Map;

/** Registry updates after a job never alter the stored historical result. */
public final class HistoryBindingTest {

    public static void run(Asserts a) {
        a.group("history-binding");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "A", "projected");
        TestSupport.crs(svc, "B", "projected");
        TestSupport.edge(svc, "E1", "A", "B", new double[]{1, 0, 0, 1, 100, 200},
                null, 0.5, 0.5, Boolean.TRUE);

        Map<String, Object> job1 = TestSupport.job(svc, "A", "B", TestSupport.pointGeo(1, 2));
        String id = (String) job1.get("jobId");
        String before = Json.canonical(job1);
        int boundVersion = ((Number) job1.get("registryVersion")).intValue();

        // Register a much better (disagreeing) edge: quarantined pending,
        // then confirmed with a reason, which creates a new registry version.
        TestSupport.edge(svc, "E2_BETTER", "A", "B", new double[]{1, 0, 0, 1, 0, 0},
                null, 0.001, 0.001, Boolean.TRUE);
        Map<String, Object> pendingOnly = TestSupport.job(svc, "A", "B", TestSupport.pointGeo(1, 2));
        a.eq(TestSupport.item(pendingOnly, 0).get("selectedPath"), "E1",
                "pending better edge ignored until confirmed");
        svc.confirmEdge("E2_BETTER", Json.object(
                "reason", "new survey supersedes legacy offset", "state", "active"));

        Map<String, Object> fetched = svc.getJob(id);
        a.eq(Json.canonical(fetched), before, "stored job is byte-stable after registry change");
        a.eq(fetched.get("registryVersion"), boundVersion, "job bound to old registry version");

        // After confirmation a new job uses the new best chain.
        Map<String, Object> job2 = TestSupport.job(svc, "A", "B", TestSupport.pointGeo(1, 2));
        a.check(!job2.get("fingerprint").equals(job1.get("fingerprint")),
                "registry version changes the job fingerprint");
        a.eq(TestSupport.item(job2, 0).get("selectedPath"), "E2_BETTER", "new job picks new edge");
        a.eq(TestSupport.item(job1, 0).get("selectedPath"), "E1", "old job still explains E1 path");

        // Confirming an already-active edge is rejected (history untouched).
        a.throwsCode(() -> svc.confirmEdge("E1", Json.object("reason", "x")),
                "not-pending", "active edge cannot be re-confirmed");
        a.eq(Json.canonical(svc.getJob(id)), before, "history still stable");
    }
}
