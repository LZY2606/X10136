package ctstation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-backed, versioned, append-mostly persistence.
 *
 * <p>The whole state is written atomically (temp file + rename) as pretty
 * JSON with fixed key ordering rules. The same file is the portable export:
 * importing it elsewhere reconstructs the registry, version log and every
 * historical job byte-for-byte (including job fingerprints).
 */
public final class Store {

    public static final int FORMAT_VERSION = 1;
    public static final String FILE_NAME = "station-store.json";

    private final Path file;

    public Map<String, Crs> crs = new LinkedHashMap<>();
    public Map<String, Edge> edges = new LinkedHashMap<>();
    public List<Map<String, Object>> versions = new ArrayList<>();
    public int versionSeq = 0;
    public int nextJobSeq = 1;
    public List<Map<String, Object>> jobs = new ArrayList<>();
    public Map<String, Map<String, Object>> jobById = new LinkedHashMap<>();
    public boolean seeded = false;

    public Store(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new AppException("io-error", "cannot create data dir: " + e.getMessage());
        }
        this.file = dataDir.resolve(FILE_NAME);
    }

    public Path path() { return file; }

    public boolean exists() { return Files.exists(file); }

    public void loadIfPresent() {
        if (!exists()) return;
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AppException("io-error", "cannot read store: " + e.getMessage());
        }
        Map<String, Object> root = Json.parseObject(text);
        Object fv = root.get("formatVersion");
        if (!(fv instanceof Number) || ((Number) fv).intValue() != FORMAT_VERSION) {
            throw new AppException("bad-store", "unsupported store formatVersion");
        }
        crs.clear();
        edges.clear();
        for (Object o : Json.arr(root, "crs")) {
            @SuppressWarnings("unchecked")
            Crs c = Crs.fromMap((Map<String, Object>) o);
            crs.put(c.id, c);
        }
        for (Object o : Json.arr(root, "edges")) {
            @SuppressWarnings("unchecked")
            Edge e = Edge.fromMap((Map<String, Object>) o);
            edges.put(e.id, e);
        }
        versions = new ArrayList<>();
        for (Object o : Json.arr(root, "versions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) o;
            versions.add(v);
        }
        versionSeq = ((Number) root.getOrDefault("versionSeq", 0)).intValue();
        nextJobSeq = ((Number) root.getOrDefault("nextJobSeq", 1)).intValue();
        jobs.clear();
        jobById.clear();
        for (Object o : Json.arr(root, "jobs")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) o;
            jobs.add(job);
            jobById.put(String.valueOf(job.get("jobId")), job);
        }
        seeded = Boolean.TRUE.equals(root.get("seeded"));
    }

    public synchronized void save() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("versionSeq", versionSeq);
        root.put("nextJobSeq", nextJobSeq);
        root.put("seeded", seeded);
        List<Object> cList = new ArrayList<>();
        for (Crs c : crs.values()) cList.add(c.toMap());
        root.put("crs", cList);
        List<Object> eList = new ArrayList<>();
        for (Edge e : edges.values()) eList.add(e.toMap());
        root.put("edges", eList);
        root.put("versions", versions);
        root.put("jobs", jobs);
        String text = Json.pretty(root);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AppException("io-error", "cannot write store: " + e.getMessage());
        }
    }

    /** Portable bundle: exactly the persisted document. */
    public Map<String, Object> exportDocument() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("versionSeq", versionSeq);
        root.put("nextJobSeq", nextJobSeq);
        root.put("seeded", seeded);
        List<Object> cList = new ArrayList<>();
        for (Crs c : crs.values()) cList.add(c.toMap());
        root.put("crs", cList);
        List<Object> eList = new ArrayList<>();
        for (Edge e : edges.values()) eList.add(e.toMap());
        root.put("edges", eList);
        root.put("versions", versions);
        root.put("jobs", jobs);
        return root;
    }

    /** Replace all state with an imported document; returns number of jobs. */
    @SuppressWarnings("unchecked")
    public synchronized int importDocument(Map<String, Object> doc) {
        Object fv = doc.get("formatVersion");
        if (!(fv instanceof Number) || ((Number) fv).intValue() != FORMAT_VERSION) {
            throw new AppException("bad-import", "unsupported formatVersion");
        }
        Map<String, Crs> newCrs = new LinkedHashMap<>();
        for (Object o : Json.arr(doc, "crs")) {
            Crs c = Crs.fromMap((Map<String, Object>) o);
            newCrs.put(c.id, c);
        }
        Map<String, Edge> newEdges = new LinkedHashMap<>();
        for (Object o : Json.arr(doc, "edges")) {
            Edge e = Edge.fromMap((Map<String, Object>) o);
            if (!newCrs.containsKey(e.sourceCrs) || !newCrs.containsKey(e.targetCrs)) {
                throw new AppException("bad-import", "edge " + e.id + " references unknown CRS");
            }
            newEdges.put(e.id, e);
        }
        List<Map<String, Object>> newVersions = new ArrayList<>();
        for (Object o : Json.arr(doc, "versions")) newVersions.add((Map<String, Object>) o);
        List<Map<String, Object>> newJobs = new ArrayList<>();
        for (Object o : Json.arr(doc, "jobs")) newJobs.add((Map<String, Object>) o);

        this.crs.clear();
        this.crs.putAll(newCrs);
        this.edges.clear();
        this.edges.putAll(newEdges);
        this.versions = newVersions;
        this.versionSeq = ((Number) doc.getOrDefault("versionSeq", newVersions.size())).intValue();
        this.nextJobSeq = ((Number) doc.getOrDefault("nextJobSeq", 1)).intValue();
        this.jobs = newJobs;
        this.jobById.clear();
        for (Map<String, Object> j : newJobs) jobById.put(String.valueOf(j.get("jobId")), j);
        this.seeded = Boolean.TRUE.equals(doc.get("seeded"));
        save();
        return newJobs.size();
    }

    public Map<String, Object> getJob(String id) { return jobById.get(id); }
}
