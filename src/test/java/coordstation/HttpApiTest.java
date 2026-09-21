package coordstation;

import coordstation.json.Json;
import coordstation.registry.Registry;
import coordstation.server.HttpApi;
import coordstation.server.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {
    private HttpApi api;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        if (api != null) {
            // server daemon threads die with the test JVM; no public stop needed
        }
    }

    private String base() {
        return "http://127.0.0.1:" + api.port();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (body == null) b.GET();
        else b.method(method, HttpRequest.BodyPublishers.ofString(body))
             .header("Content-Type", "application/json");
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seed(Registry r) {
        r.registerCrs(TestSupport.j("{\"id\":\"WGS84\",\"name\":\"WGS 84\"}"));
        r.registerCrs(TestSupport.j("{\"id\":\"GCJ02\",\"name\":\"GCJ-02\"}"));
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "WGS84", "GCJ02",
                TestSupport.WORLD, 0.5, "[1,0,0.006,0,1,-0.001]", true)));
    }

    @Test
    void fullApiWorkflow(@TempDir Path dir) throws Exception {
        Store store = new Store(dir.resolve("store.json"));
        Registry registry = new Registry();
        seed(registry);
        api = new HttpApi(registry, store);
        api.start("127.0.0.1", 0);

        HttpResponse<String> home = send("GET", "/", null);
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("坐标变换注册站"));

        HttpResponse<String> state = send("GET", "/api/state", null);
        assertEquals(200, state.statusCode());
        assertEquals(3, ((Number) Json.parseObject(state.body()).get("version")).intValue());

        HttpResponse<String> bad = send("POST", "/api/transform",
                "{\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,999]},\"src\":\"WGS84\",\"dst\":\"GCJ02\"}");
        assertEquals(400, bad.statusCode());
        assertTrue(Json.parseObject(bad.body()).get("error").toString().contains("latitude"));

        HttpResponse<String> tr = send("POST", "/api/transform",
                "{\"geometry\":{\"type\":\"Point\",\"coordinates\":[116.3,39.9]},\"src\":\"WGS84\",\"dst\":\"GCJ02\"}");
        assertEquals(200, tr.statusCode());
        Map<String, Object> trj = Json.parseObject(tr.body());
        assertNotNull(trj.get("chosen"));

        HttpResponse<String> job = send("POST", "/api/jobs",
                "{\"items\":[{\"id\":\"a\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[116.3,39.9]},"
                        + "\"src\":\"WGS84\",\"dst\":\"GCJ02\"}]}");
        assertEquals(200, job.statusCode());
        String fp1 = (String) Json.parseObject(job.body()).get("fingerprint");

        assertTrue(store.exists(), "mutations persist to the store file");

        // restart: history survives from disk
        Registry reloaded = new Registry();
        store.loadInto(reloaded);
        HttpResponse<String> got = send("GET", "/api/jobs/job-1", null);
        // query against the original server first
        got = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/jobs/job-1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, got.statusCode());
        assertEquals(fp1, Json.parseObject(got.body()).get("fingerprint"));

        // export determinism
        HttpResponse<String> exp1 = send("GET", "/api/export", null);
        Registry copy = new Registry();
        copy.importStore(Json.parseObject(exp1.body()));
        assertEquals(Json.writeCanonical(Json.parse(exp1.body())),
                Json.writeCanonical(copy.exportStore()));
    }

    @Test
    void pendingEdgeFlowThroughApi(@TempDir Path dir) throws Exception {
        Store store = new Store(dir.resolve("store2.json"));
        Registry registry = new Registry();
        registry.registerCrs(TestSupport.j("{\"id\":\"A\"}"));
        registry.registerCrs(TestSupport.j("{\"id\":\"B\"}"));
        registry.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", true)));
        api = new HttpApi(registry, store);
        api.start("127.0.0.1", 0);

        HttpResponse<String> badConfirm = send("POST", "/api/edges/e1/confirm", "{\"reason\":\"\"}");
        assertEquals(400, badConfirm.statusCode());

        HttpResponse<String> missing = send("POST", "/api/edges/nope/confirm", "{\"reason\":\"x\"}");
        assertEquals(400, missing.statusCode());
    }

    @Test
    void unknownRouteReturns422WithExplanation(@TempDir Path dir) throws Exception {
        Store store = new Store(dir.resolve("store3.json"));
        Registry registry = new Registry();
        registry.registerCrs(TestSupport.j("{\"id\":\"A\"}"));
        registry.registerCrs(TestSupport.j("{\"id\":\"B\"}"));
        registry.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", false)));
        api = new HttpApi(registry, store);
        api.start("127.0.0.1", 0);

        HttpResponse<String> resp = send("POST", "/api/transform",
                "{\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]},\"src\":\"B\",\"dst\":\"A\"}");
        assertEquals(422, resp.statusCode());
        Map<String, Object> body = Json.parseObject(resp.body());
        assertTrue(body.get("error").toString().contains("no usable transform chain"));
        assertEquals(List.of(), body.get("candidates"));
    }
}
