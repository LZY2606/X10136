package ctr;

import java.util.ArrayList;
import java.util.List;

public final class GeometryService {
    private GeometryService() {
    }

    public static void validateForCrs(Geometry geometry, Crs crs) {
        if ("geographic".equals(crs.axes())) {
            for (List<Coordinate> ring : geometry.rings()) {
                for (Coordinate coordinate : ring) {
                    checkGeographic(coordinate);
                }
            }
        }
    }

    private static void checkGeographic(Coordinate coordinate) {
        double x = coordinate.x();
        double y = coordinate.y();
        if (x < -180.0 || x > 180.0) {
            throw new ApiException(400, String.format(java.util.Locale.ROOT,
                    "Longitude %.12g is outside [-180,180] in geographic CRS", x));
        }
        if (y < -90.0 || y > 90.0) {
            throw new ApiException(400, String.format(java.util.Locale.ROOT,
                    "Latitude %.12g is outside [-90,90] in geographic CRS", y));
        }
    }

    public static Geometry apply(Geometry geometry, PathPlanner.ChosenPath path, Crs targetCrs) {
        if (geometry instanceof PointGeometry point) {
            return new PointGeometry(applyCoordinate(point.coordinate(), path, targetCrs));
        }
        if (geometry instanceof LineGeometry line) {
            return new LineGeometry(applyCoordinates(line.coordinates(), path, targetCrs));
        }
        PolygonGeometry polygon = (PolygonGeometry) geometry;
        return new PolygonGeometry(applyRings(polygon.ringsCoordinates(), path, targetCrs),
                polygon.crossesAntimeridian());
    }

    private static List<List<Coordinate>> applyRings(List<List<Coordinate>> rings,
                                                      PathPlanner.ChosenPath path, Crs targetCrs) {
        List<List<Coordinate>> output = new ArrayList<>();
        for (List<Coordinate> ring : rings) {
            output.add(applyCoordinates(ring, path, targetCrs));
        }
        return output;
    }

    private static List<Coordinate> applyCoordinates(List<Coordinate> coordinates,
                                                      PathPlanner.ChosenPath path, Crs targetCrs) {
        List<Coordinate> output = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            output.add(applyCoordinate(coordinate, path, targetCrs));
        }
        return output;
    }

    private static Coordinate applyCoordinate(Coordinate coordinate, PathPlanner.ChosenPath path, Crs targetCrs) {
        Coordinate transformed = path.totalTransform().apply(coordinate);
        if ("geographic".equals(targetCrs.axes())) {
            double x = normalizeLongitude(transformed.x());
            double y = clampLatitude(transformed.y());
            transformed = new Coordinate(x, y, transformed.z());
        }
        checkFiniteAndTarget(transformed, targetCrs);
        return transformed;
    }

    private static double normalizeLongitude(double x) {
        double wrapped = ((x + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        if (wrapped == -180.0 && x > 0) {
            return 180.0;
        }
        return wrapped;
    }

    private static double clampLatitude(double y) {
        double epsilon = 1e-10;
        if (Math.abs(y) <= 90.0 + epsilon) {
            return Math.max(-90.0, Math.min(90.0, y));
        }
        return y;
    }

    private static void checkFiniteAndTarget(Coordinate coordinate, Crs crs) {
        if ("geographic".equals(crs.axes())) {
            checkGeographic(coordinate);
        }
    }
}
