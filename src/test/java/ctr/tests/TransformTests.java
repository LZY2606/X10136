package ctr.tests;

import ctr.AffineTransform2D;
import ctr.Coordinate;

public class TransformTests {
    public void testAffineInverseAndComposition() {
        AffineTransform2D transform = new AffineTransform2D(2, 0.3, 5, -0.2, 1.5, -7);
        AffineTransform2D inverse = transform.inverse();
        Coordinate point = Coordinate.geo(12.3, -4.5);
        Coordinate roundTrip = inverse.apply(transform.apply(point));
        Asserts.approx(roundTrip.x(), point.x(), 1e-12, "inverse x");
        Asserts.approx(roundTrip.y(), point.y(), 1e-12, "inverse y");
        Coordinate composed = transform.compose(inverse).apply(point);
        Asserts.approx(composed.x(), point.x(), 1e-12, "composition x");
        Asserts.approx(composed.y(), point.y(), 1e-12, "composition y");
    }

    public void testOperatorNormBoundsRotation() {
        AffineTransform2D rotation = new AffineTransform2D(0, -1, 0, 1, 0, 0);
        Asserts.approx(rotation.operatorNorm(), 1.0, 1e-14, "rotation norm is one");
        AffineTransform2D scale = new AffineTransform2D(3, 0, 0, 0, 0.5, 0);
        Asserts.approx(scale.operatorNorm(), 3.0, 1e-14, "scale norm is largest gain");
    }
}
