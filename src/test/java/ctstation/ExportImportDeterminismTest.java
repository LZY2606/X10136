package ctstation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * After exporting and importing the same data:
 *  - path selection is identical,
 *  - error numbers serialise identically,
 *  - job fingerprints are identical.
 * Also exercises restart-from-disk historical binding.
 */
public final class ExportImportDeterminismTest {

    public static void run(Asserts a) throws Exception {
        a.group("export-import");

        Service src = TestSupport.freshService();
        buildScenario(src);
        Map<String, Object> jobA = TestSupport.job(src, "A", "C",
                TestSupport.pointGeo(3, -7),
                TestSupport.pointGeo(12, 40));
        Map<String, Object> export = src.store.exportDocument();
        String exportText = Json.canonical(export);

        Path dir = Files.createTempDirectory("ctr-import-");
        dir.toFile().deleteOnExit();
        Service dst = new Service(dir);
        int imported = dst.store.importDocument(export);
        a.eq(imported, 1, "one job imported");

        String reExport = Json.canonical(dst.store.exportDocument());
        a.eq(reExport, exportText, "re-export is canonical-identical");

        Map<String, Object> jobAFetch = dst.getJob((String) jobA.get("jobId"));
        a.eq(jobAFetch.get("fingerprint"), jobA.get("fingerprint"),
                "imported job fingerprint preserved");

        Map<String, Object> item0 = TestSupport.item(jobA, 0);
        Map<String, Object> item0b = TestSupport.item(jobAFetch, 0);
        a.eq(Json.canonical(item0b.get("cumulativeError")),
                Json.canonical(item0.get("cumulativeError")),
                "cumulative error serialisation identical after import");
        a.eq(item0b.get("selectedPath"), item0.get("selectedPath"),
                "selected path identical after import");

        // A freshly computed job on the imported registry must select the
        // same chain and produce the same fingerprint.
        Map<String, Object> jobB = TestSupport.job(dst, "A", "C",
                TestSupport.pointGeo(3, -7),
                TestSupport.pointGeo(12, 40));
        a.eq(jobB.get("fingerprint"), jobA.get("fingerprint"),
                "new run after import yields identical fingerprint");
        a.eq(TestSupport.item(jobB, 0).get("selectedPath"),
                item0.get("selectedPath"), "path selection identical after import");

        // Restart from disk: history still there, new edge does not change it.
        Service restarted = new Service(dir);
        Map<String, Object> before = restarted.getJob((String) jobA.get("jobId"));
        TestSupport.edge(restarted, "E_NEW", "A", "C",
                new double[]{1, 0, 0, 1, 0, 0}, null, 0.0001, 0.0001, Boolean.TRUE);
        Map<String, Object> after = restarted.getJob((String) jobA.get("jobId"));
        a.eq(Json.canonical(after), Json.canonical(before),
                "job unchanged after restart and registry update");

        // Canonical JSON key ordering is deterministic.
        String c1 = Json.canonical(Json.object("b", 1, "a", Json.object("z", 1, "y", 2)));
        a.eq(c1, "{\"a\":{\"y\":2,\"z\":1},\"b\":1}", "canonical key order");
        a.eq(Json.num(-0.0), "0", "negative zero normalised in serialisation");
    }

    private static void buildScenario(Service svc) {
        TestSupport.crs(svc, "A", "projected");
        TestSupport.crs(svc, "B", "projected");
        TestSupport.crs(svc, "C", "projected");
        TestSupport.edge(svc, "E_AB", "A", "B",
                new double[]{1.5, 0.2, -0.1, 1.1, 3, -4}, null, 0.12, 0.09, Boolean.TRUE);
        TestSupport.edge(svc, "E_BC", "B", "C",
                new double[]{0.9, 0, 0, 1.05, 7, 2}, null, 0.07, 0.11, Boolean.TRUE);
        TestSupport.edge(svc, "E_AC", "A", "C",
                new double[]{1.35, 0.18, -0.105, 1.155, 11.2, -2.15},
                null, 0.8, 0.8, Boolean.TRUE);
    }
}
