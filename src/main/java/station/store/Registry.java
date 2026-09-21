package station.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import station.engine.Affine;
import station.engine.PathFinder;
import station.engine.Transformer;
import station.json.Json;
import station.model.Geometry;
import station.model.LonArc;
import station.model.Region;
import station.model.TransformEdge;

/**
 * 坐标变换注册站核心：参考系、变换边、版本化持久化、批量作业。
 * 每次注册表变更产生新版本号；历史作业记录一旦写入不再变化。
 */
public final class Registry {

    private long version = 0;
    private final Map<String, Map<String, Object>> crs = new LinkedHashMap<>();
    private final Map<String, TransformEdge> edges = new LinkedHashMap<>();
    private final List<Map<String, Object>> jobs = new ArrayList<>();
    private long jobSeq = 0;
    private final List<Map<String, Object>> history = new ArrayList<>();
    private final Path storeFile; // 可为 null（纯内存，测试用）

    public Registry(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static Registry load(Path storeFile) {
        Registry r = new Registry(storeFile);
        if (storeFile != null && Files.exists(storeFile)) {
            try {
                String text = Files.readString(storeFile, StandardCharsets.UTF_8);
                r.importData(Json.asObject(Json.parse(text), "存储文件"), false);
            } catch (Exception e) {
                throw new RuntimeException("加载存储文件失败: " + e.getMessage(), e);
            }
        }
        return r;
    }

    public synchronized long version() { return version; }

    // ---------- 参考系 ----------

    public synchronized Map<String, Object> addCrs(Map<String, Object> body) {
        String id = Json.asString(body.get("id"), "参考系 id");
        String name = Json.asString(body.get("name"), "参考系 name");
        if (crs.containsKey(id)) throw new IllegalArgumentException("参考系已存在: " + id);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        crs.put(id, entry);
        bump("注册参考系 " + id);
        return entry;
    }

    // ---------- 变换边 ----------

    public synchronized Map<String, Object> addEdge(Map<String, Object> body) {
        TransformEdge e = TransformEdge.fromJson(body);
        if (edges.containsKey(e.id)) throw new IllegalArgumentException("变换边已存在: " + e.id);
        requireCrs(e.fromCrs);
        requireCrs(e.toCrs);
        if (e.fromCrs.equals(e.toCrs)) throw new IllegalArgumentException("边的源与目标参考系不能相同");

        // 回路一致性检查：与现有 ACTIVE 链路在抽样点上比对
        String inconsistency = checkCycleConsistency(e);
        if (inconsistency != null) {
            e.status = TransformEdge.Status.PENDING;
            e.pendingReason = inconsistency;
        }
        edges.put(e.id, e);
        bump("注册变换边 " + e.id + (inconsistency != null ? "（待确认）" : ""));
        Map<String, Object> out = e.toJson();
        return out;
    }

    /**
     * 若新边 e: A->B 与现有 A->B 默认链路构成回路，在两者有效范围交集内
     * 确定性抽样 3x3 网格点比对正变换结果；最大偏差超过容差则判定不一致。
     */
    private String checkCycleConsistency(TransformEdge e) {
        List<TransformEdge> active = activeEdges();
        LonArc arc = e.region.lonArc;
        PathFinder.Result existing = PathFinder.find(active, e.fromCrs, e.toCrs,
                arc, e.region.latMin, e.region.latMax);
        if (existing.best == null) return null; // 无既有链路，不构成回路冲突

        // 计算新边与既有链路所有边的有效范围交集
        Region intersection = e.region;
        for (PathFinder.Arc a : existing.best.arcs) {
            intersection = intersection.intersect(a.edge.region);
            if (intersection == null) return null; // 范围不相交，无法抽样比对
        }

        double tolerance = 2.0 * (e.accuracy + existing.best.totalError) + 1e-9;
        double maxDeviation = 0;
        double[] worst = null;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double lon = intersection.lonArc.sample(i, 3);
                double lat = intersection.latMin
                        + (intersection.latMax - intersection.latMin) * j / 2.0;
                double[] direct = e.affine.apply(lon, lat);
                double x = lon, y = lat;
                for (PathFinder.Arc a : existing.best.arcs) {
                    double[] r = a.affine.apply(x, y);
                    x = r[0];
                    y = r[1];
                }
                double dev = Math.hypot(direct[0] - x, direct[1] - y);
                if (dev > maxDeviation) {
                    maxDeviation = dev;
                    worst = new double[]{lon, lat};
                }
            }
        }
        if (maxDeviation > tolerance) {
            return "与既有链路 " + existing.best.key() + " 在抽样点 ("
                    + Json.formatNumber(worst[0]) + ", " + Json.formatNumber(worst[1])
                    + ") 处偏差 " + Json.formatNumber(maxDeviation)
                    + " 超过容差 " + Json.formatNumber(tolerance);
        }
        return null;
    }

    public synchronized Map<String, Object> confirmEdge(String id, Map<String, Object> body) {
        TransformEdge e = edges.get(id);
        if (e == null) throw new IllegalArgumentException("变换边不存在: " + id);
        String reason = Json.asString(body.get("reason"), "确认理由");
        if (reason.isBlank()) throw new IllegalArgumentException("确认动作必须携带理由");
        if (e.status != TransformEdge.Status.PENDING) {
            throw new IllegalArgumentException("边 " + id + " 不处于待确认状态");
        }
        e.status = TransformEdge.Status.ACTIVE;
        e.confirmReason = reason;
        e.confirmedAtVersion = version + 1;
        bump("确认变换边 " + id + "：" + reason);
        return e.toJson();
    }

    // ---------- 批量变换 ----------

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> transform(Map<String, Object> body) {
        String sourceCrs = Json.asString(body.get("sourceCrs"), "sourceCrs");
        String targetCrs = Json.asString(body.get("targetCrs"), "targetCrs");
        requireCrs(sourceCrs);
        requireCrs(targetCrs);
        List<Object> objects = Json.asArray(body.get("objects"), "objects");
        if (objects.isEmpty()) throw new IllegalArgumentException("objects 不能为空");

        long jobVersion = version;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            Map<String, Object> item = Json.asObject(objects.get(i), "objects[" + i + "]");
            String id = item.containsKey("id") ? Json.asString(item.get("id"), "对象 id") : "obj-" + i;
            results.add(transformOne(id, item.get("geometry"), sourceCrs, targetCrs));
        }

        Map<String, Object> job = new LinkedHashMap<>();
        jobSeq++;
        job.put("id", "job-" + jobSeq);
        job.put("registryVersion", jobVersion);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceCrs", sourceCrs);
        request.put("targetCrs", targetCrs);
        request.put("objects", objects);
        job.put("request", request);
        job.put("results", results);
        job.put("fingerprint", fingerprint(job));
        jobs.add(job);
        save();
        return job;
    }

    private Map<String, Object> transformOne(String id, Object geometryObj,
                                             String sourceCrs, String targetCrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        try {
            if (geometryObj == null) throw new Geometry.ValidationException("缺少 geometry 字段");
            Geometry g = Geometry.fromGeoJson(geometryObj);
            LonArc arc = g.lonArc();
            PathFinder.Result found = PathFinder.find(activeEdges(), sourceCrs, targetCrs,
                    arc, g.latMin(), g.latMax());
            List<Object> candidates = new ArrayList<>();
            for (PathFinder.Candidate c : found.candidates) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("path", c.key);
                cm.put("totalError", c.totalError);
                cm.put("accepted", c.accepted);
                cm.put("chosen", c.chosen);
                cm.put("reason", c.reason);
                candidates.add(cm);
            }
            out.put("candidates", candidates);
            if (found.best == null) {
                out.put("status", "error");
                out.put("diagnostic", found.failure);
                return out;
            }
            Geometry transformed = Transformer.apply(g, found.best);
            out.put("status", "ok");
            out.put("path", found.best.describe());
            out.put("errorBound", found.best.totalError);
            out.put("input", g.toGeoJson());
            out.put("output", transformed.toGeoJson());
        } catch (Geometry.ValidationException | Json.JsonException | IllegalArgumentException ex) {
            out.put("status", "error");
            out.put("diagnostic", ex.getMessage());
        }
        return out;
    }

    // ---------- 查询 / 导出 / 导入 ----------

    public synchronized Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("crs", new ArrayList<>(crs.values()));
        List<Object> edgeList = new ArrayList<>();
        for (TransformEdge e : edges.values()) edgeList.add(e.toJson());
        m.put("edges", edgeList);
        List<Object> jobList = new ArrayList<>();
        for (Map<String, Object> j : jobs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", j.get("id"));
            s.put("registryVersion", j.get("registryVersion"));
            s.put("fingerprint", j.get("fingerprint"));
            jobList.add(s);
        }
        m.put("jobs", jobList);
        m.put("history", new ArrayList<>(history));
        return m;
    }

    public synchronized Map<String, Object> job(String id) {
        for (Map<String, Object> j : jobs) {
            if (id.equals(j.get("id"))) return j;
        }
        return null;
    }

    public synchronized Map<String, Object> exportData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "coordinate-transform-registry/1");
        m.put("version", version);
        m.put("crs", new ArrayList<>(crs.values()));
        List<Object> edgeList = new ArrayList<>();
        for (TransformEdge e : edges.values()) edgeList.add(e.toJson());
        m.put("edges", edgeList);
        m.put("jobs", new ArrayList<>(jobs));
        m.put("jobSeq", jobSeq);
        m.put("history", new ArrayList<>(history));
        return m;
    }

    public synchronized String exportCanonical() {
        return Json.writeCanonical(exportData());
    }

    @SuppressWarnings("unchecked")
    public synchronized void importData(Map<String, Object> data, boolean persist) {
        String format = Json.asString(data.get("format"), "format");
        if (!"coordinate-transform-registry/1".equals(format)) {
            throw new IllegalArgumentException("不支持的导出格式: " + format);
        }
        Registry fresh = new Registry(null);
        fresh.version = ((Number) data.get("version")).longValue();
        for (Object o : Json.asArray(data.get("crs"), "crs")) {
            Map<String, Object> c = Json.asObject(o, "参考系");
            fresh.crs.put(Json.asString(c.get("id"), "参考系 id"), c);
        }
        for (Object o : Json.asArray(data.get("edges"), "edges")) {
            TransformEdge e = TransformEdge.fromJson(Json.asObject(o, "变换边"));
            fresh.edges.put(e.id, e);
        }
        for (Object o : Json.asArray(data.get("jobs"), "jobs")) {
            fresh.jobs.add(Json.asObject(o, "作业"));
        }
        fresh.jobSeq = ((Number) data.get("jobSeq")).longValue();
        for (Object o : Json.asArray(data.get("history"), "history")) {
            fresh.history.add(Json.asObject(o, "历史"));
        }
        this.version = fresh.version;
        this.crs.clear(); this.crs.putAll(fresh.crs);
        this.edges.clear(); this.edges.putAll(fresh.edges);
        this.jobs.clear(); this.jobs.addAll(fresh.jobs);
        this.jobSeq = fresh.jobSeq;
        this.history.clear(); this.history.addAll(fresh.history);
        if (persist) save();
    }

    // ---------- 内部 ----------

    private List<TransformEdge> activeEdges() {
        List<TransformEdge> out = new ArrayList<>();
        for (TransformEdge e : edges.values()) {
            if (e.status == TransformEdge.Status.ACTIVE) out.add(e);
        }
        return out;
    }

    private void requireCrs(String id) {
        if (!crs.containsKey(id)) throw new IllegalArgumentException("参考系未注册: " + id);
    }

    private void bump(String action) {
        version++;
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("version", version);
        h.put("action", action);
        history.add(h);
        save();
    }

    private void save() {
        if (storeFile == null) return;
        try {
            if (storeFile.getParent() != null) Files.createDirectories(storeFile.getParent());
            Files.writeString(storeFile, exportCanonical(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("持久化失败: " + e.getMessage(), e);
        }
    }

    public static String fingerprint(Map<String, Object> jobWithoutFingerprint) {
        Map<String, Object> copy = new LinkedHashMap<>(jobWithoutFingerprint);
        copy.remove("fingerprint");
        return sha256(Json.writeCanonical(copy));
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
