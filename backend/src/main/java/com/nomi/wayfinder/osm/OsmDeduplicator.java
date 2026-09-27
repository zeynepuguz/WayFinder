package com.nomi.wayfinder.osm;

import java.util.List;

/**
 * Keeps OSM from adding a second copy of a place we already have from a verified source.
 * An OSM element is a duplicate when a non-OSM place lies within MAX_DISTANCE_METERS and the
 * folded names match (one contains the other: "Çiya" vs "Çiya Sofrası").
 * The verified row always wins; it is never changed by the import.
 */
public final class OsmDeduplicator {

    static final double MAX_DISTANCE_METERS = 80;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final List<ExistingPlace> existing;

    public OsmDeduplicator(List<ExistingPlace> existing) {
        this.existing = existing.stream()
                .map(p -> new ExistingPlace(OsmPlaceMapper.fold(p.name()), p.latitude(), p.longitude()))
                .filter(p -> !p.name().isEmpty())
                .toList();
    }

    public boolean isDuplicate(OsmPlaceMapper.OsmPlace place) {
        String name = OsmPlaceMapper.fold(place.name());
        if (name.isEmpty()) {
            return false;
        }
        for (ExistingPlace other : existing) {
            if (distanceMeters(place.latitude(), place.longitude(), other.latitude(), other.longitude())
                    <= MAX_DISTANCE_METERS
                    && (name.contains(other.name()) || other.name().contains(name))) {
                return true;
            }
        }
        return false;
    }

    // Haversine; plenty accurate for 80 m
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    // A verified (non-OSM) place: name as stored, WGS84 coordinates
    public record ExistingPlace(String name, double latitude, double longitude) {
    }
}
