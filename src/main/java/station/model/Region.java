package station.model;

/**
 * 变换边的有效范围：经度弧（可跨反经线）+ 纬度区间。边界为闭区间，恰好命中算覆盖。
 */
public final class Region {
    public final LonArc lonArc;
    public final double latMin;
    public final double latMax;

    public Region(double lonMin, double lonMax, double latMin, double latMax) {
        if (latMin > latMax) throw new IllegalArgumentException("纬度范围下界大于上界");
        this.lonArc = new LonArc(lonMin, lonMax);
        this.latMin = latMin;
        this.latMax = latMax;
    }

    public boolean covers(LonArc arc, double latLo, double latHi) {
        return lonArc.covers(arc) && latLo >= latMin - 1e-9 && latHi <= latMax + 1e-9;
    }

    /** 与另一区域的交集（抽样用）；无交集返回 null。 */
    public Region intersect(Region other) {
        LonArc arc = lonArc.intersect(other.lonArc);
        double lo = Math.max(latMin, other.latMin);
        double hi = Math.min(latMax, other.latMax);
        if (arc == null || lo > hi + 1e-9) return null;
        return new Region(arc.start, arc.end, lo, hi);
    }

    @Override
    public String toString() {
        return "经度 " + lonArc + " 纬度 [" + latMin + ", " + latMax + "]";
    }
}
