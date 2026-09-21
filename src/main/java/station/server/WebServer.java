package station.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import station.json.Json;
import station.store.Registry;
import station.web.Page;

/** JSON API + 单页应用。全部本地，无远程调用。 */
public final class WebServer {
    private final Registry registry;
    private final HttpServer server;

    public WebServer(Registry registry, String host, int port) throws IOException {
        this.registry = registry;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::route);
    }

    public void start() {
        server.start();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getPath();
            String method = ex.getRequestMethod();
            Object result;
            if ("GET".equals(method) && "/".equals(path)) {
                send(ex, 200, Page.HTML, "text/html; charset=utf-8");
                return;
            } else if ("GET".equals(method) && "/api/state".equals(path)) {
                result = registry.state();
            } else if ("GET".equals(method) && "/api/export".equals(path)) {
                send(ex, 200, registry.exportCanonical(), "application/json; charset=utf-8");
                return;
            } else if ("POST".equals(method) && "/api/crs".equals(path)) {
                result = registry.addCrs(body(ex));
            } else if ("POST".equals(method) && "/api/edges".equals(path)) {
                result = registry.addEdge(body(ex));
            } else if ("POST".equals(method) && "/api/edges/confirm".equals(path)) {
                Map<String, Object> b = body(ex);
                result = registry.confirmEdge(Json.asString(b.get("id"), "边 id"), b);
            } else if ("POST".equals(method) && "/api/transform".equals(path)) {
                result = registry.transform(body(ex));
            } else if ("POST".equals(method) && "/api/import".equals(path)) {
                registry.importData(Json.asObject(Json.parse(readBody(ex)), "导入数据"), true);
                result = ok("已导入，注册表版本 " + registry.version());
            } else if ("GET".equals(method) && path.startsWith("/api/jobs/")) {
                Map<String, Object> job = registry.job(path.substring("/api/jobs/".length()));
                if (job == null) {
                    send(ex, 404, Json.write(err("作业不存在")), "application/json; charset=utf-8");
                    return;
                }
                result = job;
            } else {
                send(ex, 404, Json.write(err("未知路由: " + method + " " + path)),
                        "application/json; charset=utf-8");
                return;
            }
            send(ex, 200, Json.write(result), "application/json; charset=utf-8");
        } catch (IllegalArgumentException | Json.JsonException e) {
            send(ex, 400, Json.write(err(e.getMessage())), "application/json; charset=utf-8");
        } catch (Exception e) {
            send(ex, 500, Json.write(err("服务器内部错误: " + e.getMessage())),
                    "application/json; charset=utf-8");
        }
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        return Json.asObject(Json.parse(readBody(ex)), "请求体");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private static void send(HttpExchange ex, int status, String text, String contentType)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
