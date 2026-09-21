package crstation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server: JSON API plus a single-page canvas UI.
 * No online map or remote coordinate service is ever contacted.
 */
public final class ApiServer {

    private final Store store;
    private final Registry registry;
    private final List<JobProcessor.Job> jobs;
    private final JobProcessor processor;
    private HttpServer server;

    public ApiServer(Path dataDir, boolean seed) {
        this.store = new Store(dataDir);
        this.registry = store.loadRegistry();
        this.jobs = new CopyOnWriteArrayList<>(store.loadJobs(registry));
        this.processor = new JobProcessor(registry);
        if (seed && registry.crsList().isEmpty()) {
            DemoData.seed(registry);
        }
    }

    public Registry registry() {
        return registry;
    }

    public List<JobProcessor.Job> jobs() {
        return jobs;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                serveStatic(ex, "/crstation/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && path.startsWith("/static/")) {
                String name = path.substring("/static/".length());
                if (!name.matches("[a-zA-Z0-9._-]+")) {
                    sendError(ex, 404, "not found");
                    return;
                }
                serveStatic(ex, "/crstation/" + name, contentType(name));
                return;
            }
            if ("GET".equals(method) && path.equals("/api/state")) {
                sendJson(ex, 200, stateJson());
                return;
            }
            if ("GET".equals(method) && path.equals("/api/export")) {
                sendJson(ex, 200, store.exportBundle(registry, jobs));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/jobs/")) {
                String id = path.substring("/api/jobs/".length());
                JobProcessor.Job found = null;
                for (JobProcessor.Job j : jobs) {
                    if (j.id.equals(id) || j.fingerprint.equals(id)) {
                        found = j;
                        break;
                    }
                }
                if (found == null) {
                    sendError(ex, 404, "unknown job: " + id);
                } else {
                    sendJson(ex, 200, found.toJson(true));
                }
                return;
            }
            if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                switch (path) {
                    case "/api/crs" -> {
                        sendJson(ex, 201, registry.addCrs(body).toJson());
                        return;
                    }
                    case "/api/edges" -> {
                        sendJson(ex, 201, registry.registerEdge(body).toJson());
                        return;
                    }
                    case "/api/transform" -> {
                        String from = Json.str(body, "fromCrs");
                        String to = Json.str(body, "toCrs");
                        String geoJson = Json.str(body, "geojson");
                        JobProcessor.Job job = processor.run(from, to, geoJson);
                        store.saveJob(job);
                        jobs.add(0, job);
                        sendJson(ex, 200, job.toJson(true));
                        return;
                    }
                    case "/api/confirm" -> {
                        String edgeId = Json.str(body, "edgeId");
                        String reason = Json.str(body, "reason");
                        sendJson(ex, 200, registry.confirmEdge(edgeId, reason).toJson());
                        return;
                    }
                    case "/api/import" -> {
                        jobs.clear();
                        jobs.addAll(importBundle(body));
                        sendJson(ex, 200, stateJson());
                        return;
                    }
                    default -> {
                        sendError(ex, 404, "not found: " + path);
                        return;
                    }
                }
            }
            sendError(ex, 404, "not found: " + method + " " + path);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        } catch (IllegalStateException e) {
            sendError(ex, 409, e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private List<JobProcessor.Job> importBundle(Map<String, Object> body) {
        Map<String, Object> bundle;
        if (body.containsKey("format")) {
            bundle = body;
        } else if (body.get("bundle") instanceof Map) {
            bundle = Json.obj(body.get("bundle"));
        } else if (body.get("text") instanceof String) {
            bundle = Json.parseObject((String) body.get("text"));
        } else {
            throw new IllegalArgumentException("import requires an export bundle or {\"text\": ...}");
        }
        List<JobProcessor.Job> imported = new ArrayList<>();
        Store importedStore = new Store(store.directory());
        importedStore.importBundle(bundle, imported);
        return imported;
    }

    private Map<String, Object> stateJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registryVersion", registry.version());
        List<Object> crss = new ArrayList<>();
        for (Crs c : registry.crsList()) {
            crss.add(c.toJson());
        }
        m.put("crs", crss);
        List<Object> edges = new ArrayList<>();
        for (Edge e : registry.edges()) {
            edges.add(e.toJson());
        }
        m.put("edges", edges);
        List<Object> jobSummary = new ArrayList<>();
        for (JobProcessor.Job j : jobs) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", j.id);
            sm.put("fingerprint", j.fingerprint);
            sm.put("fromCrs", j.fromCrs);
            sm.put("toCrs", j.toCrs);
            sm.put("registryVersion", j.registryVersion);
            sm.put("createdAt", j.createdAt);
            sm.put("successCount", j.successCount);
            sm.put("failureCount", j.failureCount);
            jobSummary.add(sm);
        }
        m.put("jobs", jobSummary);
        return m;
    }

    private void serveStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        try (var in = ApiServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendError(ex, 404, "not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        return m;
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.writePretty(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", true);
        m.put("status", status);
        m.put("message", message == null ? "" : message);
        sendJson(ex, status, m);
    }
}
