package com.nomi.wayfinder.planning;

/**
 * Europe or Asia side of the Bosphorus. Distances are straight lines, so without this check a place
 * 1.5 km away across the water would be suggested as a "20 min walk". Walking suggestions and route
 * legs stay on the user's side.
 */
public final class BosphorusSides {

    // Approximate middle of the strait, north to south: {latitude, longitude}
    private static final double[][] MIDLINE = {
            {41.25, 29.130},
            {41.20, 29.100},
            {41.17, 29.072},
            {41.15, 29.075},
            {41.12, 29.080},
            {41.10, 29.060},
            {41.083, 29.061},
            {41.075, 29.051},
            {41.065, 29.052},
            {41.046, 29.036},
            {41.035, 29.008},
            {41.02, 28.995},
            {41.00, 29.000},
            {40.80, 29.000},
    };

    private BosphorusSides() {
    }

    public static boolean isAsianSide(double latitude, double longitude) {
        return longitude > midlineLongitude(latitude);
    }

    public static boolean sameSide(double lat1, double lon1, double lat2, double lon2) {
        return isAsianSide(lat1, lon1) == isAsianSide(lat2, lon2);
    }

    static double midlineLongitude(double latitude) {
        if (latitude >= MIDLINE[0][0]) {
            return MIDLINE[0][1];
        }
        for (int i = 1; i < MIDLINE.length; i++) {
            double[] north = MIDLINE[i - 1];
            double[] south = MIDLINE[i];
            if (latitude >= south[0]) {
                double t = (latitude - south[0]) / (north[0] - south[0]);
                return south[1] + t * (north[1] - south[1]);
            }
        }
        return MIDLINE[MIDLINE.length - 1][1];
    }
}
