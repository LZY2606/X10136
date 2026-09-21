package coordstation.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2D affine transform:
 *   x' = a*x + b*y + tx
 *   y' = c*x + d*y + ty
 * params order: [a, b, tx, c, d, ty].
 */
public final class Affine {
    public final double a, b, tx, c, d, ty;

    public Affine(double a, double b, double tx, double c, double d, double ty) {
        this.a = a; this.b = b; this.tx = tx;
        this.c = c; this.d = d; this.ty = ty;
    }

    public static Affine identity() {
        return new Affine(1, 0, 0, 0, 1, 0);
    }

    public double[] apply(double x, double y) {
        return new double[]{a * x + b * y + tx, c * x + d * y + ty};
    }

    public double determinant() {
        return a * d - b * c;
    }

    public boolean invertible() {
        double det = determinant();
        return Double.isFinite(det) && det != 0.0;
    }

    public Affine inverse() {
        double det = determinant();
        if (!invertible()) throw new IllegalStateException("transform is not invertible");
        double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        return new Affine(ia, ib, -(ia * tx + ib * ty), ic, id, -(ic * tx + id * ty));
    }

    /** this ∘ other : apply other first, then this. */
    public Affine compose(Affine other) {
        return new Affine(
                a * other.a + b * other.c,
                a * other.b + b * other.d,
                a * other.tx + b * other.ty + tx,
                c * other.a + d * other.c,
                c * other.b + d * other.d,
                c * other.tx + d * other.ty + ty);
    }

    public List<Object> toJson() {
        List<Object> l = new ArrayList<>();
        l.add(a); l.add(b); l.add(tx); l.add(c); l.add(d); l.add(ty);
        return l;
    }

    public static Affine fromJson(List<Object> l) {
        if (l.size() != 6) throw new IllegalArgumentException("affine needs exactly 6 numbers");
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            Object o = l.get(i);
            if (!(o instanceof Number)) throw new IllegalArgumentException("affine params must be numbers");
            v[i] = ((Number) o).doubleValue();
        }
        return new Affine(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    public static Affine fromJsonMap(Map<String, Object> m) {
        return new Affine(
                coordstation.json.Json.num(m, "a"), coordstation.json.Json.num(m, "b"),
                coordstation.json.Json.num(m, "tx"), coordstation.json.Json.num(m, "c"),
                coordstation.json.Json.num(m, "d"), coordstation.json.Json.num(m, "ty"));
    }
}
