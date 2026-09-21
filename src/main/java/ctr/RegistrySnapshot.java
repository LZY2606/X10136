package ctr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RegistrySnapshot(int version, String fingerprint, List<Crs> crsList, List<Edge> edges,
                               List<Map<String, Object>> pendingEdges) {
    public RegistrySnapshot {
        crsList = List.copyOf(crsList);
        edges = List.copyOf(edges);
        pendingEdges = List.copyOf(pendingEdges);
    }

    public Crs crs(String id) {
        for (Crs candidate : crsList) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    public Edge edge(String id) {
        for (Edge candidate : edges) {
            if (candidate.id.equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    public Map<String, Object> pending(String id) {
        for (Map<String, Object> candidate : pendingEdges) {
            if (id.equals(candidate.get("id"))) {
                return candidate;
            }
        }
        return null;
    }

    public Map<String, Object> toJson(boolean includePending) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("fingerprint", fingerprint);
        List<Object> crses = new ArrayList<>();
        for (Crs crs : crsList) {
            crses.add(crs.toJson());
        }
        map.put("crs", crses);
        List<Object> edgeList = new ArrayList<>();
        for (Edge edge : edges) {
            edgeList.add(edge.toJson());
        }
        map.put("edges", edgeList);
        if (includePending) {
            map.put("pendingEdges", pendingEdges);
        }
        return map;
    }

    public Map<String, Object> portableJson() {
        return toJson(false);
    }
}
