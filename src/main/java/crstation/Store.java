package crstation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Versioned, append-only persistence.
 *
 * <ul>
 *   <li>{@code events.jsonl} — one registry mutation per line; state is
 *       rebuilt by replay so old results always refer to an immutable
 *       historical version;</li>
 *   <li>{@code jobs.jsonl} — one immutable job record per line;</li>
 *   <li>{@code export.json} — a portable bundle of CRSs, edges (with full
 *       confirmation history) and jobs. Importing the same bundle reproduces
 *       identical path choices, error serialization and job fingerprints.</li>
 * </ul>
 */
public final class Store {

    private final Path dir;
    private final Path eventsFile;
    private final Path jobsFile;

    public Store(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.eventsFile = dir.resolve("events.jsonl");
        this.jobsFile = dir.resolve("jobs.jsonl");
    }

    public Path directory() {
        return dir;
    }

    // ------------------------------------------------------------------
    // Registry
    // ------------------------------------------------------------------

    public Registry loadRegistry() {
        Registry registry = new Registry();
        registry.setEventSink(this::appendEvent);
        if (Files.exists(eventsFile)) {
            try {
                for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Map<String, Object> ev = Json.parseObject(line);
                    registry.replay(ev);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return registry;
    }

    private synchronized void appendEvent(Map<String, Object> event) {
        appendLine(eventsFile, Json.write(event));
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    public synchronized void saveJob(JobProcessor.Job job) {
        appendLine(jobsFile, Json.write(job.toJson(false)));
    }

    public List<JobProcessor.Job> loadJobs(Registry registry) {
        List<JobProcessor.Job> jobs = new ArrayList<>();
        if (!Files.exists(jobsFile)) {
            return jobs;
        }
        try {
            for (String line : Files.readAllLines(jobsFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    jobs.add(jobFromMap(Json.parseObject(line), registry));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return jobs;
    }

    /**
     * Rebuild a Job object from stored JSON. The stored edge specs are
     * snapshots; selection data is restored but recomputation uses the
     * registry only for display lookups.
     */
    @SuppressWarnings("unchecked")
    static JobProcessor.Job jobFromMap(Map<String, Object> m, Registry registry) {
        JobProcessor.Job job = new JobProcessor.Job();
        job.id = Json.str(m, "id");
        job.fingerprint = Json.str(m, "fingerprint");
        job.registryVersion = (int) Json.optNum(m, "registryVersion", 0);
        job.fromCrs = Json.str(m, "fromCrs");
        job.toCrs = Json.str(m, "toCrs");
        job.createdAt = Json.optStr(m, "createdAt", "");
        job.successCount = (int) Json.optNum(m, "successCount", 0);
        job.failureCount = (int) Json.optNum(m, "failureCount", 0);
        int idx = 0;
        for (Object it : Json.arr(m.get("items"))) {
            Map<String, Object> im = Json.obj(it);
            boolean success = Boolean.TRUE.equals(im.get("success"));
            Geometry input = im.get("input") == null ? null
                    : Geometry.parseGeometry(Json.obj(im.get("input")));
            Geometry output = im.get("output") == null ? null
                    : Geometry.parseGeometry(Json.obj(im.get("output")));
            PathFinder.Path path = null;
            if (im.get("selectedPath") != null) {
                List<PathFinder.Step> steps = new ArrayList<>();
                Map<String, Object> pj = Json.obj(im.get("selectedPath"));
                for (Object so : Json.arr(pj.get("steps"))) {
                    Map<String, Object> sm = Json.obj(so);
                    Edge edge = registry.edge(Json.str(sm, "edgeId"));
                    if (edge == null) {
                        throw new IllegalStateException("stored job references missing edge "
                                + sm.get("edgeId"));
                    }
                    steps.add(new PathFinder.Step(edge,
                            "forward".equals(Json.str(sm, "direction"))));
                }
                double err = Json.optNum(pj, "errorBound", 0);
                path = new PathFinder.Path(steps, err);
            }
            job.items.add(new JobProcessor.ItemResult(idx,
                    im.get("featureId") == null ? null : String.valueOf(im.get("featureId")),
                    im.get("name") == null ? null : String.valueOf(im.get("name")),
                    success,
                    success ? null : Json.optStr(im, "error", ""),
                    input, output, path, null));
            idx++;
        }
        return job;
    }

    // ------------------------------------------------------------------
    // Portable export / import
    // ------------------------------------------------------------------

    /** Build a self-contained, deterministic export bundle. */
    public Map<String, Object> exportBundle(Registry registry, List<JobProcessor.Job> jobs) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", "coord-transform-registry");
        bundle.put("formatVersion", 1);
        Map<String, Object> crsSection = new LinkedHashMap<>();
        crsSection.put("version", registry.version());
        List<Object> crss = new ArrayList<>();
        for (Crs c : registry.crsList()) {
            crss.add(c.toJson());
        }
        crsSection.put("items", crss);
        List<Object> edges = new ArrayList<>();
        for (Edge e : registry.edges()) {
            edges.add(e.toJson());
        }
        crsSection.put("edges", edges);
        bundle.put("registry", crsSection);
        List<Object> jobList = new ArrayList<>();
        for (JobProcessor.Job job : jobs) {
            jobList.add(job.toJson(false));
        }
        bundle.put("jobs", jobList);
        return bundle;
    }

    /**
     * Import an export bundle into an empty store. The canonical replay order
     * is reconstructed (CRSs first, then edges in their stored order), so the
     * resulting registry version and all fingerprints match the origin.
     */
    public Registry importBundle(Map<String, Object> bundle, List<JobProcessor.Job> intoJobs) {
        if (!"coord-transform-registry".equals(Json.optStr(bundle, "format", null))) {
            throw new IllegalArgumentException("not a coord-transform-registry export");
        }
        Map<String, Object> reg = Json.obj(bundle.get("registry"));
        List<Object> crss = Json.arr(reg.get("items"));
        List<Object> edges = Json.arr(reg.get("edges"));
        List<Object> jobs = Json.arr(bundle.get("jobs"));
        try {
        if ((Files.exists(eventsFile) && Files.size(eventsFile) > 0)
                || (Files.exists(jobsFile) && Files.size(jobsFile) > 0)) {
            throw new IllegalStateException("import is only allowed into an empty data directory");
        }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Registry registry = new Registry();
        registry.setEventSink(this::appendEvent);
        for (Object c : crss) {
            registry.addCrs(Json.obj(c));
        }
        for (Object eo : edges) {
            Map<String, Object> em = Json.obj(eo);
            Edge edge = Edge.fromMap(em, registry);
            // register without re-running loop checks: status is authoritative
            registry.adopt(edge);
        }
        // restore confirmations history was already inside Edge.fromMap; emit nothing extra
        for (Object jo : jobs) {
            JobProcessor.Job job = jobFromMap(Json.obj(jo), registry);
            saveJob(job);
            intoJobs.add(job);
        }
        return registry;
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private static void appendLine(Path file, String line) {
        try {
            synchronized (Store.class) {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
