package ctr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WebServer {
    private final HttpServer server;
    private final RegistryService registryService;
    private final JobService jobService;

    public WebServer(String host, int port, RegistryService registryService, JobService jobService) throws IOException {
        this.registryService = registryService;
        this.jobService = jobService;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendResource(exchange, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/app.js".equals(path)) {
                sendResource(exchange, "/web/app.js", "application/javascript; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/styles.css".equals(path)) {
                sendResource(exchange, "/web/styles.css", "text/css; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/api/health".equals(path)) {
                sendJson(exchange, 200, Map.of("ok", true, "service", "坐标变换注册站"));
                return;
            }
            if ("GET".equals(method) && "/api/registry".equals(path)) {
                sendJson(exchange, 200, registryService.snapshot().toJson(true));
                return;
            }
            if ("POST".equals(method) && "/api/crs".equals(path)) {
                Crs crs = Crs.fromJson(Json.object(readJson(exchange), "CRS"));
                sendJson(exchange, 201, registryService.registerCrs(crs).toJson(true));
                return;
            }
            if ("POST".equals(method) && "/api/edges".equals(path)) {
                Map<String, Object> body = Json.object(readJson(exchange), "edge request");
                Edge edge = Edge.fromJson(body);
                Double factor = Json.optionalNumber(body, "toleranceFactor");
                sendJson(exchange, 201, registryService.registerEdge(edge, factor));
                return;
            }
            if ("POST".equals(method) && "/api/edges/confirm".equals(path)) {
                Map<String, Object> body = Json.object(readJson(exchange), "confirmation");
                RegistrySnapshot snapshot = registryService.confirmPending(
                        Json.string(body, "edgeId"), Json.string(body, "reason"));
                sendJson(exchange, 200, snapshot.toJson(true));
                return;
            }
            if ("GET".equals(method) && "/api/pending".equals(path)) {
                sendJson(exchange, 200, Map.of("pendingEdges", registryService.pendingEdges()));
                return;
            }
            if ("POST".equals(method) && "/api/plan".equals(path)) {
                Map<String, Object> body = Json.object(readJson(exchange), "plan request");
                Geometry geometry = GeoJson.parseGeometry(Json.required(body, "geometry"));
                int version = body.containsKey("registryVersion")
                        ? (int) Json.number(Json.required(body, "registryVersion"), "registryVersion")
                        : registryService.snapshot().version();
                RegistrySnapshot snapshot = registryService.snapshot(version);
                String sourceCrs = Json.string(body, "sourceCrs");
                String targetCrs = Json.string(body, "targetCrs");
                if (snapshot.crs(sourceCrs) == null) {
                    throw new ApiException(400, "Unknown source CRS: " + sourceCrs);
                }
                if (snapshot.crs(targetCrs) == null) {
                    throw new ApiException(400, "Unknown target CRS: " + targetCrs);
                }
                GeometryService.validateForCrs(geometry, snapshot.crs(sourceCrs));
                PathPlanner.PlanResult plan = PathPlanner.plan(snapshot, sourceCrs, targetCrs, geometry);
                sendJson(exchange, 200, plan.toJson());
                return;
            }
            if ("POST".equals(method) && "/api/jobs".equals(path)) {
                sendJson(exchange, 201, jobService.submit(Json.object(readJson(exchange), "job request")));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/jobs/")) {
                String id = path.substring("/api/jobs/".length());
                if (id.isBlank() || id.contains("/")) {
                    throw new ApiException(404, "Unknown endpoint");
                }
                sendJson(exchange, 200, jobService.get(id));
                return;
            }
            if ("GET".equals(method) && "/api/jobs".equals(path)) {
                sendJson(exchange, 200, Map.of("jobs", jobService.list()));
                return;
            }
            if ("GET".equals(method) && "/api/export".equals(path)) {
                sendJson(exchange, 200, registryService.exportBundle());
                return;
            }
            if ("POST".equals(method) && "/api/import".equals(path)) {
                Map<String, Object> body = Json.object(readJson(exchange), "import bundle");
                String createdAt = Json.optionalString(body, "createdAt", java.time.Instant.now().toString());
                registryService.replaceWithImport(body, createdAt);
                sendJson(exchange, 200, registryService.snapshot().toJson(true));
                return;
            }
            sendJson(exchange, 404, Map.of("error", "Not found", "path", path));
        } catch (ApiException e) {
            safeError(exchange, e.status(), e.getMessage());
        } catch (IllegalArgumentException e) {
            safeError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            safeError(exchange, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }


    private void safeError(HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, error(message));
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return map;
    }

    private Object readJson(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            String text = output.toString(StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new ApiException(400, "Request body must be JSON");
            }
            return Json.parse(text);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.pretty(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream input = WebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendJson(exchange, 404, error("Missing resource: " + resource));
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            byte[] bytes = output.toByteArray();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
