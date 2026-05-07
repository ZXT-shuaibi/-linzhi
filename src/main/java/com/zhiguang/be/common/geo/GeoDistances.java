package com.zhiguang.be.common.geo;

public final class GeoDistances {

    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    private GeoDistances() {
    }

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double latRad1 = Math.toRadians(lat1);
        double latRad2 = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLng = Math.toRadians(lng2 - lng1);

        double sinLat = Math.sin(deltaLat / 2D);
        double sinLng = Math.sin(deltaLng / 2D);
        double a = sinLat * sinLat
                + Math.cos(latRad1) * Math.cos(latRad2) * sinLng * sinLng;
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return EARTH_RADIUS_METERS * c;
    }

    public static Double nullableHaversineMeters(double lat1, double lng1, Double lat2, Double lng2) {
        if (lat2 == null || lng2 == null) {
            return null;
        }
        return haversineMeters(lat1, lng1, lat2, lng2);
    }
}
