package com.nomi.wayfinder.geo;

/**
 * The one straight-line distance used across the app (dedup, matching, routes, photo checks).
 * It agrees with PostGIS distances on geography closely enough for the few kilometres we compare.
 */
public final class GeoMath {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoMath() {
    }

    // Great-circle distance (haversine) in meters; the clamp keeps rounding errors from producing NaN
    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
