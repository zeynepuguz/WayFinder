package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;

import java.util.*;

/**
 * Keeps OSM from adding a second copy of a place we already have from a verified source.
 * An OSM element is a duplicate when a non-OSM place lies within MAX_DISTANCE_METERS and the
 * folded names match (one contains the other: "Çiya" vs "Çiya Sofrası").
 * The verified row always wins; the import only ever gives it the element's wikidata / Commons file
 * (for its photo) when it has none yet, never any other data.
 *
 * OSM also often maps one place several times (a park as a node and as a way, two nodes 300 m apart):
 * {@link #dedupeAmongThemselves} keeps one element per same category + same folded name within
 * SAME_NAME_METERS (SAME_NAME_LARGE_METERS for parks and attractions, which are big).
 */
public final class OsmDeduplicator {

    static final double MAX_DISTANCE_METERS = 80;
    static final double SAME_NAME_METERS = 150;
    static final double SAME_NAME_LARGE_METERS = 600;
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

    /**
     * Drops OSM elements that are another element of the same place: same category, same folded name and
     * close together. The kept one is the most useful: one with a photo reference (wikidata / Commons) or
     * opening hours first, then a way / relation (the real outline) over a node, then the lower OSM id.
     * The input order of the kept elements is preserved.
     */
    public static Deduped dedupeAmongThemselves(List<OsmPlaceMapper.OsmPlace> places) {
        List<OsmPlaceMapper.OsmPlace> byPreference = new ArrayList<>(places);
        byPreference.sort(Comparator
                .comparing((OsmPlaceMapper.OsmPlace p) -> !(p.hasMedia() || !p.openingHours().isEmpty()))
                .thenComparing(p -> p.osmId().startsWith("node/"))
                .thenComparingLong(OsmDeduplicator::numericId));

        Map<String, List<OsmPlaceMapper.OsmPlace>> keptByKey = new HashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (OsmPlaceMapper.OsmPlace place : byPreference) {
            String name = OsmPlaceMapper.fold(place.name());
            if (name.isEmpty()) {
                continue;
            }
            List<OsmPlaceMapper.OsmPlace> kept = keptByKey.computeIfAbsent(place.category() + "|" + name,
                    k -> new ArrayList<>());
            double limit = sameNameMeters(place.category());
            boolean duplicate = kept.stream().anyMatch(k -> distanceMeters(place.latitude(), place.longitude(),
                    k.latitude(), k.longitude()) <= limit);
            if (duplicate) {
                dropped.add(place.osmId());
            } else {
                kept.add(place);
            }
        }

        List<OsmPlaceMapper.OsmPlace> result = places.stream().filter(p -> !dropped.contains(p.osmId())).toList();
        return new Deduped(result, List.copyOf(dropped));
    }

    static double sameNameMeters(PlaceCategory category) {
        return category == PlaceCategory.PARK || category == PlaceCategory.ATTRACTION
                ? SAME_NAME_LARGE_METERS : SAME_NAME_METERS;
    }

    private static long numericId(OsmPlaceMapper.OsmPlace place) {
        String id = place.osmId();
        try {
            return Long.parseLong(id.substring(id.indexOf('/') + 1));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * @param kept          one element per place
     * @param droppedOsmIds the other elements ("way/123"); rows already imported for them are removed when
     *                      nothing references them
     */
    public record Deduped(List<OsmPlaceMapper.OsmPlace> kept, List<String> droppedOsmIds) {
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
