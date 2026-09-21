package coordstation.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import coordstation.json.Json;
import coordstation.model.Geometry;
import coordstation.registry.Registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** JSON API + static web UI on the JDK built-in HTTP server. */
public final class HttpApi {
    private final Registry registry;
    private final Store store;
    private HttpServer server;

    public HttpApi(Registry registry, Store store) {
        this.registry = registry;
        this.store = store;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::route);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                sendResource(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/app.js") && method.equals("GET")) {
                sendResource(ex, "/web/app.js", "text/javascript; charset=utf-8");
            } else if (path.equals("/api/state") && method.equals("GET")) {
                sendJson(ex, 200, registry.stateJson());
            } else if (path.equals("/api/crs") && method.equals("POST")) {
                mutate(ex, () -> registry.registerCrs(readBody(ex)));
            } else if (path.equals("/api/edges") && method.equals("POST")) {
                mutate(ex, () -> registry.registerEdge(readBody(ex)));
            } else if (path.startsWith("/api/edges/") && path.endsWith("/confirm") && method.equals("POST")) {
                String id = path.substring("/api/edges/".length(), path.length() - "/confirm".length());
                Map<String, Object> body = readBody(ex);
                mutate(ex, () -> registry.confirmEdge(id, Json.strOr(body, "reason", "")));
            } else if (path.equals("/api/transform") && method.equals("POST")) {
                handleTransform(ex);
            } else if (path.equals("/api/jobs") && method.equals("POST")) {
                mutate(ex, () -> registry.runJob(readBody(ex)));
            } else if (path.startsWith("/api/jobs/") && method.equals("GET")) {
                String id = path.substring("/api/jobs/".length());
                Map<String, Object> job = registry.job(id);
                if (job == null) sendJson(ex, 404, error("unknown job: " + id));
                else sendJson(ex, 200, job);
            } else if (path.equals("/api/export") && method.equals("GET")) {
                sendJson(ex, 200, registry.exportStore());
            } else if (path.equals("/api/import") && method.equals("POST")) {
                mutate(ex, () -> {
                    registry.importStore(readBody(ex));
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("status", "imported");
                    ok.put("version", registry.version());
                    return ok;
                });
            } else {
                sendJson(ex, 404, error("not found: " + method + " " + path));
            }
        } catch (Registry.RegistryException | Json.JsonException
                 | Geometry.ValidationException | IllegalArgumentException e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, error("internal error: " + e));
        } finally {
            ex.close();
        }
    }

    private void handleTransform(HttpExchange ex) throws IOException {
        Map<String, Object> body = readBody(ex);
        Geometry geom = Geometry.fromGeoJson(Json.obj(body, "geometry"));
        String src = Json.str(body, "src");
        String dst = Json.str(body, "dst");
        try {
            sendJson(ex, 200, registry.transform(geom, src, dst));
        } catch (Registry.NoRouteException e) {
            Map<String, Object> out = e.explanation;
            out.put("error", e.getMessage());
            sendJson(ex, 422, out);
        }
    }

    private interface Mutation {
        Map<String, Object> run() throws IOException;
    }

    private void mutate(HttpExchange ex, Mutation m) throws IOException {
        Map<String, Object> result = m.run();
        store.save(registry);
        sendJson(ex, 200, result);
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg == null ? "unknown error" : msg);
        return m;
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readAllBytes();
        }
        if (data.length == 0) return new LinkedHashMap<>();
        return Json.parseObject(new String(data, StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.writePretty(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static void sendResource(HttpExchange ex, String resource, String contentType) throws IOException {
        byte[] data;
        try (InputStream in = HttpApi.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(ex, 404, error("missing resource " + resource));
                return;
            }
            data = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
