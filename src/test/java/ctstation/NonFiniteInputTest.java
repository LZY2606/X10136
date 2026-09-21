package ctstation;

import java.util.Arrays;

/** NaN / Infinity must be refused before computation, from JSON or Java API. */
public final class NonFiniteInputTest {

    public static void run(Asserts a) {
        a.group("non-finite");

        a.throwsCode(() -> new Point2(Double.NaN, 1), "non-finite", "NaN x rejected");
        a.throwsCode(() -> new Point2(0, Double.POSITIVE_INFINITY),
                "non-finite", "Infinity y rejected");

        String[] badPayloads = {
                "{\"x\": NaN}",
                "{\"v\": Infinity}",
                "{\"v\": -Infinity}"
        };
        for (String p : badPayloads) {
            a.throwsCode(() -> Json.parse(p), "bad-json", "non-finite JSON token rejected: " + p);
        }

        // End-to-end job with NaN embedded in GeoJSON coordinates.
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "P", "projected");
        TestSupport.crs(svc, "Q", "projected");
        TestSupport.edge(svc, "E", "P", "Q", new double[]{1, 0, 0, 1, 0, 0},
                null, 0, 0, Boolean.TRUE);
        String body = "{\"sourceCrs\":\"P\",\"targetCrs\":\"Q\",\"objects\":["
                + "{\"type\":\"Point\",\"coordinates\":[NaN,1]}]}";
        a.throwsCode(() -> Json.parseObject(body), "bad-json", "NaN in job body rejected at parse");

        // Direct API: infinite value surfaced as non-finite.
        a.throwsCode(() -> TestSupport.job(svc, "P", "Q",
                Json.object("type", "Point", "coordinates", Arrays.asList(1.0d, 1.0d / 0.0d))),
                "non-finite", "Infinity coordinate rejected in job");
    }
}
