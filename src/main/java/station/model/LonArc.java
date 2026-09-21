package station.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 经度弧，表示沿东经方向从 start 到 end 的覆盖区间。
 * start <= end 为普通弧；start > end 表示跨越反经线（如 170 -> -170）。
 * 所有经度规范到 [-180, 180)。比较时在展开的连续轴上进行，避免 min/max 误判。
 */
public final class LonArc {
    private static final double EPS = 1e-9;

    public final double start;
    public final double end;

    public LonArc(double start, double end) {
        this.start = norm(start);
        this.end = norm(end);
    }

    public static double norm(double lon) {
        double r = lon % 360.0;
        if (r >= 180.0) r -= 360.0;
        if (r < -180.0) r += 360.0;
        return r;
    }

    public boolean crossesAntimeridian() {
        return start > end;
    }

    /** 弧在展开轴上的跨度（度）。 */
    public double span() {
        return crossesAntimeridian() ? (end + 360.0 - start) : (end - start);
    }

    public boolean contains(double lon) {
        double x = norm(lon);
        if (!crossesAntimeridian()) return x >= start - EPS && x <= end + EPS;
        return x >= start - EPS || x <= end + EPS;
    }

    /** 把 v 展开到离 anchor 最近的表示（差值落在 [-180, 180)）。 */
    private static double unwrap(double v, double anchor) {
        double r = v - anchor;
        r = r % 360.0;
        if (r < 0) r += 360.0;
        return anchor + r;
    }

    /** this 是否完整覆盖 other（边界恰好命中也算覆盖）。 */
    public boolean covers(LonArc other) {
        double s2 = unwrap(other.start, this.start);
        double e2 = s2 + other.span();
        return s2 >= this.start - EPS && e2 <= this.start + this.span() + EPS;
    }

    /** 两弧交集；无交集返回 null。 */
    public LonArc intersect(LonArc other) {
        double s2 = unwrap(other.start, this.start);
        double e2 = s2 + other.span();
        double lo = Math.max(this.start, s2);
        double hi = Math.min(this.start + this.span(), e2);
        if (lo > hi + EPS) return null;
        return new LonArc(norm(lo), norm(hi));
    }

    /**
     * 一组经度的最小覆盖弧：在经度圆上找最大空隙，覆盖弧即其补集。
     * 跨反经线的点集（如 179 与 -179）得到 179 -> -179 的窄弧，
     * 而不是被普通 min/max 误判成几乎整条纬圈。
     */
    public static LonArc minimalCovering(List<Double> lons) {
        if (lons.isEmpty()) throw new IllegalArgumentException("经度列表为空");
        List<Double> sorted = new ArrayList<>();
        for (double l : lons) sorted.add(norm(l));
        Collections.sort(sorted);
        if (sorted.size() == 1) return new LonArc(sorted.get(0), sorted.get(0));
        double maxGap = -1;
        int gapAfter = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double a = sorted.get(i);
            double b = sorted.get((i + 1) % sorted.size());
            double gap = (i + 1 == sorted.size()) ? (b + 360.0 - a) : (b - a);
            if (gap > maxGap) { maxGap = gap; gapAfter = i; }
        }
        double start = sorted.get((gapAfter + 1) % sorted.size());
        double end = sorted.get(gapAfter);
        return new LonArc(start, end);
    }

    /** 弧上第 i / (n-1) 个采样经度（沿东经方向，确定性）。 */
    public double sample(int i, int n) {
        double frac = (n <= 1) ? 0.0 : (double) i / (double) (n - 1);
        return norm(start + span() * frac);
    }

    @Override
    public String toString() {
        return "[" + start + " -> " + end + (crossesAntimeridian() ? " 跨反经线" : "") + "]";
    }
}
