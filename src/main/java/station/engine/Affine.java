package station.engine;

import java.util.ArrayList;
import java.util.List;

/** 二维仿射变换：x' = a*x + b*y + c；y' = d*x + e*y + f。 */
public final class Affine {
    public final double a, b, c, d, e, f;

    public Affine(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c;
        this.d = d; this.e = e; this.f = f;
    }

    public static Affine identity() {
        return new Affine(1, 0, 0, 0, 1, 0);
    }

    public double[] apply(double x, double y) {
        return new double[]{a * x + b * y + c, d * x + e * y + f};
    }

    public double determinant() {
        return a * e - b * d;
    }

    /** 数学逆；行列式为 0 时返回 null。 */
    public Affine inverse() {
        double det = determinant();
        if (det == 0.0 || !Double.isFinite(det)) return null;
        double ia = e / det, ib = -b / det;
        double id = -d / det, ie = a / det;
        double ic = -(ia * c + ib * f);
        double ifn = -(id * c + ie * f);
        return new Affine(ia, ib, ic, id, ie, ifn);
    }

    public List<Object> toList() {
        List<Object> out = new ArrayList<>();
        out.add(a); out.add(b); out.add(c);
        out.add(d); out.add(e); out.add(f);
        return out;
    }

    public static Affine fromList(List<Object> vals) {
        if (vals.size() != 6) throw new IllegalArgumentException("仿射参数必须是 6 个数值 [a,b,c,d,e,f]");
        double[] p = new double[6];
        for (int i = 0; i < 6; i++) {
            p[i] = station.json.Json.asDouble(vals.get(i), "仿射参数[" + i + "]");
            if (!Double.isFinite(p[i])) {
                throw new IllegalArgumentException("仿射参数[" + i + "] 非有限数字: " + p[i]);
            }
        }
        return new Affine(p[0], p[1], p[2], p[3], p[4], p[5]);
    }
}
