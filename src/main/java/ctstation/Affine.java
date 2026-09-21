package ctstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict 2D affine transform:
 * <pre>[x'] = [a b] [x] + [e]
 * [y']   [c d] [y]   [f]</pre>
 *
 * <p>Composition and the inverse are closed-form. An edge is safely
 * invertible only when the determinant is non-zero and the condition-number
 * style product {@code |det| / norm} is above {@link #SAFE_INVERSE_MIN}
 * (callers may additionally mark an edge explicitly non-invertible).
 */
public final class Affine {
    public static final double SAFE_INVERSE_MIN = 1e-12d;

    public final double a, b, c, d, e, f;

    public Affine(double a, double b, double c, double d, double e, double f) {
        if (!(Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c)
                && Double.isFinite(d) && Double.isFinite(e) && Double.isFinite(f))) {
            throw new AppException("non-finite", "affine coefficients must be finite");
        }
        this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
    }

    public static Affine identity() { return new Affine(1, 0, 0, 1, 0, 0); }

    public double[] apply(double x, double y) {
        return new double[] {a * x + b * y + e, c * x + d * y + f};
    }

    public Point2 apply(Point2 p) {
        double[] q = apply(p.x, p.y);
        return new Point2(q[0], q[1]);
    }

    public double det() { return a * d - b * c; }

    /** Smaller singular value proxy: |det| / larger row-sum norm. */
    public double invertibilityScore() {
        double det = det();
        if (det == 0.0d) return 0.0d;
        double n = Math.max(Math.abs(a) + Math.abs(b) + Math.abs(c) + Math.abs(d), 1.0d);
        return Math.abs(det) / n;
    }

    public boolean safelyInvertible() {
        return Double.isFinite(det()) && Math.abs(det()) > 0.0d
                && invertibilityScore() >= SAFE_INVERSE_MIN;
    }

    public Affine inverse() {
        double det = det();
        if (!safelyInvertible()) {
            throw new AppException("not-invertible",
                    "transform cannot be safely inverted (det=" + Json.num(det) + ")");
        }
        double invDet = 1.0d / det;
        double ia = d * invDet;
        double ib = -b * invDet;
        double ic = -c * invDet;
        double id = a * invDet;
        double ie = -(ia * e + ib * f);
        double jf = -(ic * e + id * f);
        return new Affine(ia, ib, ic, id, ie, jf);
    }

    /** Returns {@code this ∘ other}: first apply {@code other}, then this. */
    public Affine compose(Affine other) {
        return new Affine(
                a * other.a + b * other.c,
                a * other.b + b * other.d,
                c * other.a + d * other.c,
                c * other.b + d * other.d,
                a * other.e + b * other.f + e,
                c * other.e + d * other.f + f);
    }

    public double[] toArray() { return new double[] {a, b, c, d, e, f}; }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("type", "affine");
        List<Object> v = new ArrayList<>();
        for (double x : toArray()) v.add(x);
        m.put("matrix", v);
        return m;
    }

    public static Affine fromMap(Map<String, Object> m) {
        List<Object> v = Json.arr(m, "matrix");
        if (v.size() != 6) {
            throw new AppException("bad-transform", "affine matrix needs exactly 6 coefficients");
        }
        double[] coef = new double[6];
        for (int i = 0; i < 6; i++) coef[i] = Json.dbl(v.get(i), "affine matrix[" + i + "]");
        return new Affine(coef[0], coef[1], coef[2], coef[3], coef[4], coef[5]);
    }
}
