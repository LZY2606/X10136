package crstation;

import java.util.Map;

/**
 * Parameterized 2D coordinate transform. Invertibility is declared per edge
 * rather than assumed from the math: a mapping with an algebraic inverse may
 * still be unsafe to invert at the advertised accuracy.
 */
public interface Transform {

    double[] apply(double[] p);

    /** Inverse mapping; only call when the owning edge is declared invertible. */
    double[] applyInverse(double[] p);

    /** True when an inverse can be built from the parameters. */
    boolean mathematicallyInvertible();

    Map<String, Object> spec();

    double DET_EPSILON = 1e-12;

    static Transform fromSpec(Map<String, Object> spec) {
        String type = Json.str(spec, "type");
        return switch (type) {
            case "affine" -> new Transforms.Affine(
                    Json.num(spec, "a"), Json.num(spec, "b"), Json.num(spec, "c"),
                    Json.num(spec, "d"), Json.num(spec, "e"), Json.num(spec, "f"));
            case "mercator" -> new Transforms.Mercator(Json.optNum(spec, "radius", 6378137.0));
            case "identity" -> new Transforms.Affine(1, 0, 0, 0, 1, 0);
            default -> throw new IllegalArgumentException("unknown transform type: " + type);
        };
    }
}
