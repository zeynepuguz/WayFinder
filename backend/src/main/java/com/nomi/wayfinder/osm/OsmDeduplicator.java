package com.nomi.wayfinder.osm;

import java.util.List;
import java.util.Optional;

/**
 * Keeps OSM from adding a second copy of a place we already have from a verified source.
 * An OSM element is a duplicate when a non-OSM place lies within MAX_DISTANCE_METERS and the
 * folded names match (one contains the other: "Çiya" vs "Çiya Sofrası").
 * The verified row always wins; the import only ever gives it the element's wikidata / Commons file
 * (for its photo) when it has none yet, never any other data.
 */
public final class OsmDeduplicator {

    static final double MAX_DISTANCE_METERS = 80;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final List<ExistingPlace> existing;

    public OsmDeduplicator(List<ExistingPlace> existing) {
        this.existing = existing.stream()
                .map(p -> new ExistingPlace(p.id(), OsmPlaceMapper.fold(p.name()), p.latitude(), p.longitude(),
                        p.hasMedia()))
                .filter(p -> !p.name().isEmpty())
                .toList();
    }

    public boolean isDuplicate(OsmPlaceMapper.OsmPlace place) {
        return findDuplicateOf(place).isPresent();
    }

    // The verified place this OSM element duplicates (its name is folded), if any
    public Optional<ExistingPlace> findDuplicateOf(OsmPlaceMapper.OsmPlace place) {
        String name = OsmPlaceMapper.fold(place.name());
        if (name.isEmpty()) {
            return Optional.empty();
        }
        for (ExistingPlace other : existing) {
            if (distanceMeters(place.latitude(), place.longitude(), other.latitude(), other.longitude())
                    <= MAX_DISTANCE_METERS
                    && (name.contains(other.name()) || other.name().contains(name))) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
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

    /**
     * A verified (non-OSM) place: name as stored, WGS84 coordinates.
     *
     * @param id       database id (null in tests that only check matching)
     * @param hasMedia it already has a wikidata id or Commons file
     */
    public record ExistingPlace(Long id, String name, double latitude, double longitude, boolean hasMedia) {

        public ExistingPlace(String name, double latitude, double longitude) {
            this(null, name, latitude, longitude, false);
        }
    }
}
