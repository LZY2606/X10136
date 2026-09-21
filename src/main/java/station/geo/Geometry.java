package station.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** GeoJSON-style geometries: Point, LineString, Polygon (single exterior ring). */
public sealed interface Geometry {
    List<Position> positions();
    String type();
    Geometry mapPositions(UnaryOperator<Position> fn);

    record Point(Position position) implements Geometry {
        @Override public List<Position> positions() { return List.of(position); }
        @Override public String type() { return "Point"; }
        @Override public Geometry mapPositions(UnaryOperator<Position> fn) {
            return new Point(fn.apply(position));
        }
    }

    record LineString(List<Position> points) implements Geometry {
        @Override public List<Position> positions() { return points; }
        @Override public String type() { return "LineString"; }
        @Override public Geometry mapPositions(UnaryOperator<Position> fn) {
            List<Position> out = new ArrayList<>();
            for (Position p : points) out.add(fn.apply(p));
            return new LineString(out);
        }
    }

    record Polygon(List<Position> ring) implements Geometry {
        @Override public List<Position> positions() { return ring; }
        @Override public String type() { return "Polygon"; }
        @Override public Geometry mapPositions(UnaryOperator<Position> fn) {
            List<Position> out = new ArrayList<>();
            for (Position p : ring) out.add(fn.apply(p));
            return new Polygon(out);
        }
    }
}
