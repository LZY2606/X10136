package ctstation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Embedded HTTP server: JSON API under /api and static web assets. */
public final class WebServer {

    private final Service service;
    private final String host;
    private final int port;
    private HttpServer server;

    public WebServer(Service service, String host, int port) {
        this.service = service;
        this.host = host;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    // ------------------------------------------------------------- static

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        if (path.contains("..")) { sendText(ex, 404, "not found", "text/plain"); return; }
        String resource = "/web" + path;
        byte[] body;
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) { sendText(ex, 404, "not found", "text/plain"); return; }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            body = buf.toByteArray();
        }
        String ct = path.endsWith(".html") ? "text/html; charset=utf-8"
                : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8"
                : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ----------------------------------------------------------------- API

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (AppException ae) {
            sendJson(ex, 400, Json.object("error", Json.object(
                    "code", ae.code, "message", ae.getMessage())));
        } catch (Exception e) {
            sendJson(ex, 500, Json.object("error", Json.object(
                    "code", "internal", "message", String.valueOf(e.getMessage()))));
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if ("GET".equals(method) && "/api/state".equals(path)) {
            sendJson(ex, 200, service.state());
            return;
        }
        if ("POST".equals(method) && "/api/crs".equals(path)) {
            sendJson(ex, 200, service.registerCrs(readBody(ex)));
            return;
        }
        if ("POST".equals(method) && "/api/edges".equals(path)) {
            sendJson(ex, 200, service.registerEdge(readBody(ex)));
            return;
        }
        if ("POST".equals(method) && "/api/jobs".equals(path)) {
            sendJson(ex, 200, service.runJob(readBody(ex)));
            return;
        }
        if ("GET".equals(method) && "/api/jobs".equals(path)) {
            sendJson(ex, 200, Json.object("jobs", service.listJobs()));
            return;
        }
        if (path.startsWith("/api/jobs/")) {
            String id = path.substring("/api/jobs/".length());
            sendJson(ex, 200, service.getJob(id));
            return;
        }
        if (path.startsWith("/api/edges/") && path.endsWith("/confirm")) {
            String id = path.substring("/api/edges/".length(), path.length() - "/confirm".length());
            sendJson(ex, 200, service.confirmEdge(id, readBody(ex)));
            return;
        }
        if ("GET".equals(method) && "/api/export".equals(path)) {
            byte[] doc = Json.pretty(service.store.exportDocument())
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"coord-station-export.json\"");
            ex.sendResponseHeaders(200, doc.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(doc); }
            return;
        }
        if ("POST".equals(method) && "/api/import".equals(path)) {
            Map<String, Object> doc = readBody(ex);
            int jobs = service.store.importDocument(doc);
            sendJson(ex, 200, Json.object("imported", true, "jobs", jobs,
                    "registryVersion", service.store.versionSeq));
            return;
        }
        if ("POST".equals(method) && "/api/reseed".equals(path)) {
            sendJson(ex, 200, Json.object("reseeded", true, "state", service.reseed()));
            return;
        }
        sendJson(ex, 404, Json.object("error", Json.object("code", "not-found", "message", path)));
    }

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            return Json.parseObject(text);
        } catch (AppException ae) {
            throw new AppException("bad-json-body", ae.getMessage());
        }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.pretty(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private void sendText(HttpExchange ex, int status, String body, String ct) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
