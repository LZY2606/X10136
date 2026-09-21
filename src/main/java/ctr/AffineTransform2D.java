package ctr;

public final class AffineTransform2D {
    public final double a;
    public final double b;
    public final double c;
    public final double d;
    public final double e;
    public final double f;

    public AffineTransform2D(double a, double b, double c, double d, double e, double f) {
        Validate.finite(a, "transform a");
        Validate.finite(b, "transform b");
        Validate.finite(c, "transform c");
        Validate.finite(d, "transform d");
        Validate.finite(e, "transform e");
        Validate.finite(f, "transform f");
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
    }

    public Coordinate apply(Coordinate point) {
        double x = a * point.x() + b * point.y() + c;
        double y = d * point.x() + e * point.y() + f;
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new ApiException(422, "Transform produced a non-finite coordinate");
        }
        Double z = point.z();
        return new Coordinate(x, y, z);
    }

    public AffineTransform2D inverse() {
        double determinant = a * e - b * d;
        if (Math.abs(determinant) < 1e-14) {
            throw new ApiException(422, "Transform is numerically singular and cannot be inverted");
        }
        double inv = 1.0 / determinant;
        double ia = e * inv;
        double ib = -b * inv;
        double id = -d * inv;
        double ie = a * inv;
        double ic = -(ia * c + ib * f);
        double ify = -(id * c + ie * f);
        return new AffineTransform2D(ia, ib, ic, id, ie, ify);
    }

    public AffineTransform2D compose(AffineTransform2D after) {
        return new AffineTransform2D(
                after.a * a + after.b * d,
                after.a * b + after.b * e,
                after.a * c + after.b * f + after.c,
                after.d * a + after.e * d,
                after.d * b + after.e * e,
                after.d * c + after.e * f + after.f);
    }

    public double operatorNorm() {
        double ata00 = a * a + d * d;
        double ata01 = a * b + d * e;
        double ata11 = b * b + e * e;
        double trace = ata00 + ata11;
        double determinant = ata00 * ata11 - ata01 * ata01;
        double discriminant = Math.max(0, trace * trace - 4.0 * determinant);
        double largestEigenvalue = (trace + Math.sqrt(discriminant)) / 2.0;
        return Math.sqrt(Math.max(0, largestEigenvalue));
    }

    public java.util.Map<String, Object> toJson() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("a", a);
        map.put("b", b);
        map.put("c", c);
        map.put("d", d);
        map.put("e", e);
        map.put("f", f);
        return map;
    }

    public static AffineTransform2D fromJson(java.util.Map<String, Object> map) {
        return new AffineTransform2D(
                Json.requiredNumber(map, "a"),
                Json.requiredNumber(map, "b"),
                Json.requiredNumber(map, "c"),
                Json.requiredNumber(map, "d"),
                Json.requiredNumber(map, "e"),
                Json.requiredNumber(map, "f"));
    }
}
