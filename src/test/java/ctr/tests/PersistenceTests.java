package ctr.tests;

import ctr.RegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PersistenceTests {
    public void testPortableExportImportProducesSameFingerprintAndVersion() throws Exception {
        Path firstDirectory = Files.createTempDirectory("ctr-export-");
        RegistryService first = new RegistryService(firstDirectory);
        first.registerCrs(new ctr.Crs("CUSTOM_X", "Custom X", "projected", "m", ""));
        first.registerCrs(new ctr.Crs("CUSTOM_Y", "Custom Y", "projected", "m", ""));
        first.registerEdge(new ctr.Edge("X_Y", "CUSTOM_X", "CUSTOM_Y",
                new ctr.AffineTransform2D(1, 0, 1.25, 0, 1, -2.5), true, 0.125, 0.125,
                ctr.Region.box(-10, -10, 10, 10, false), ctr.Region.box(-10, -10, 10, 10, false),
                "test", "", "1"), 3.0);
        Map<String, Object> bundle = first.exportBundle();

        Path secondDirectory = Files.createTempDirectory("ctr-import-");
        RegistryService second = new RegistryService(secondDirectory);
        second.replaceWithImport(bundle, String.valueOf(bundle.get("createdAt")));
        Map<String, Object> exportedAgain = second.exportBundle();
        Asserts.equal(exportedAgain.get("version"), bundle.get("version"), "version survives import");
        Asserts.equal(exportedAgain.get("fingerprint"), bundle.get("fingerprint"), "fingerprint survives import");
        String firstJson = ctr.Json.write(first.snapshot().portableJson());
        String secondJson = ctr.Json.write(second.snapshot().portableJson());
        Asserts.equal(secondJson, firstJson, "canonical registry serialization stable");
    }

    public void testImportFingerprintTamperingRejected() throws Exception {
        Path sourceDirectory = Files.createTempDirectory("ctr-tamper-source-");
        RegistryService source = new RegistryService(sourceDirectory);
        Map<String, Object> bundle = source.exportBundle();
        bundle.put("fingerprint", "deadbeef");
        Path targetDirectory = Files.createTempDirectory("ctr-tamper-target-");
        RegistryService target = new RegistryService(targetDirectory);
        Asserts.throwsApi(() -> target.replaceWithImport(bundle, "now"), 400,
                "fingerprint mismatch", "tampered import");
    }
    @SuppressWarnings("unchecked")
    public void testPendingEdgesSurvivePortableRoundTrip() throws Exception {
        Path sourceDirectory = Files.createTempDirectory("ctr-pending-export-");
        RegistryService source = new RegistryService(sourceDirectory);
        source.registerCrs(new ctr.Crs("P_A", "P A", "projected", "m", ""));
        source.registerCrs(new ctr.Crs("P_B", "P B", "projected", "m", ""));
        source.registerEdge(new ctr.Edge("P_BASE", "P_A", "P_B",
                new ctr.AffineTransform2D(1, 0, 1, 0, 1, 0), true, 0.01, 0.01,
                ctr.Region.box(0, 0, 10, 10, false), ctr.Region.box(0, 0, 10, 10, false),
                "test", "", "1"), 3.0);
        source.registerEdge(new ctr.Edge("P_BAD", "P_A", "P_B",
                new ctr.AffineTransform2D(1, 0, 99, 0, 1, 0), true, 0.01, 0.01,
                ctr.Region.box(0, 0, 10, 10, false), ctr.Region.box(0, 0, 10, 10, false),
                "test", "", "1"), 3.0);
        Map<String, Object> bundle = source.exportBundle();
        Path targetDirectory = Files.createTempDirectory("ctr-pending-import-");
        RegistryService target = new RegistryService(targetDirectory);
        target.replaceWithImport(bundle, String.valueOf(bundle.get("createdAt")));
        Asserts.equal(target.pendingEdges().size(), 1, "one pending edge imported");
        Asserts.equal(target.pendingEdges().get(0).get("id"), "P_BAD", "pending id preserved");
        Asserts.equal(target.snapshot().edge("P_BAD"), null, "pending edge remains inactive");
    }
}
