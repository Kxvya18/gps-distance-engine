package com.moveinsync.distance.core;

// Straight-line (haversine) distance between two points.
// StrictMath gives the same answer on every machine, which billing needs.
public final class GeoMath {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoMath() {
    }

    public static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = StrictMath.toRadians(lat1);
        double phi2 = StrictMath.toRadians(lat2);
        double deltaPhi = StrictMath.toRadians(lat2 - lat1);
        double deltaLambda = StrictMath.toRadians(lon2 - lon1);

        double sinLat = StrictMath.sin(deltaPhi / 2);
        double sinLon = StrictMath.sin(deltaLambda / 2);
        double a = sinLat * sinLat + StrictMath.cos(phi1) * StrictMath.cos(phi2) * sinLon * sinLon;

        return 2 * EARTH_RADIUS_METERS * StrictMath.asin(StrictMath.sqrt(a));
    }

    public static double speedKmh(double meters, long millis) {
        if (millis <= 0) {
            return meters == 0 ? 0 : Double.POSITIVE_INFINITY;
        }
        return (meters / 1000.0) / (millis / 3_600_000.0);
    }
}
