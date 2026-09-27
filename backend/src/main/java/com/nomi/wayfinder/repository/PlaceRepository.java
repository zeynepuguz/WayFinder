package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.Place;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long>, JpaSpecificationExecutor<Place> {

    // Loads places together with their opening hours in one query (avoids N+1)
    @EntityGraph(attributePaths = "openingHours")
    List<Place> findByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = "openingHours")
    Optional<Place> findWithOpeningHoursById(Long id);

    // ST_MakePoint takes (longitude, latitude). ST_DWithin uses the GiST index.
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            ORDER BY "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findNearby(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("limit") int limit
    );

    // Places that can fill a stop: one of the categories, or tagged with the stop's matching tag
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
              AND (p.category IN (:categories)
                   OR (CAST(:tag AS text) IS NOT NULL AND CAST(:tag AS text) = ANY (p.tags)))
            ORDER BY "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findCandidates(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("categories") Collection<String> categories,
            @Param("tag") String tag,
            @Param("limit") int limit
    );

    /**
     * Like findCandidates, but only places between minRadius and maxRadius ("better but farther").
     * There can be hundreds in 5 km, so the most promising come first: real rating, then interest
     * matches (interests = comma separated tags, "" for none), then distance.
     */
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :maxRadius)
              AND NOT ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :minRadius)
              AND (p.category IN (:categories)
                   OR (CAST(:tag AS text) IS NOT NULL AND CAST(:tag AS text) = ANY (p.tags)))
            ORDER BY p.rating DESC NULLS LAST,
                     (p.tags && string_to_array(CAST(:interests AS text), ',')) DESC,
                     "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findCandidatesInRing(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("minRadius") double minRadiusMeters,
            @Param("maxRadius") double maxRadiusMeters,
            @Param("categories") Collection<String> categories,
            @Param("tag") String tag,
            @Param("interests") String interests,
            @Param("limit") int limit
    );

    /**
     * Places inside a map viewport (south/west/north/east in degrees). The && bounding box test uses
     * the GiST index. Verified places first, then by distance from (lat, lon).
     */
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE p.location && CAST(ST_MakeEnvelope(:west, :south, :east, :north, 4326) AS geography)
              AND (CAST(:category AS text) IS NULL OR p.category = CAST(:category AS text))
            ORDER BY CASE WHEN p.source = 'OSM' THEN 1 ELSE 0 END, "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findInArea(
            @Param("south") double south,
            @Param("west") double west,
            @Param("north") double north,
            @Param("east") double east,
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("category") String category,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography))
            FROM places p
            WHERE p.id = :placeId
            """, nativeQuery = true)
    Double distanceTo(
            @Param("placeId") Long placeId,
            @Param("lat") double latitude,
            @Param("lon") double longitude
    );
}
