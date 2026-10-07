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

    // Places shown in the lists and on the map (findNearby, findInArea), by the category / tag the user picked
    String LISTED = """
              AND NOT p.hidden
              -- Likely closed: no current Overture source knows this OSM food place
              AND NOT p.unconfirmed
              -- "Tümü" leaves out markets and places of worship (they have their own filter); "İbadet" also lists
              -- the famous mosques / churches that are sights (ATTRACTION tagged religious)
              AND (CAST(:category AS text) IS NULL AND p.category NOT IN ('MARKET', 'WORSHIP')
                   OR p.category = CAST(:category AS text)
                   OR CAST(:category AS text) = 'WORSHIP' AND 'religious' = ANY (p.tags)
                   -- A café serving breakfast is in both lists (osm/PlaceTags.breakfastAware)
                   OR CAST(:category AS text) = 'BREAKFAST' AND 'breakfast' = ANY (p.tags)
                   OR CAST(:category AS text) = 'CAFE' AND 'cafe' = ANY (p.tags))
              -- A sub-kind by tag: İbadet > Cami ve mescit (mosque), Kilise (church), ...
              AND (CAST(:tag AS text) IS NULL OR CAST(:tag AS text) = ANY (p.tags))
            """;

    // Places a route may suggest
    String PLANNABLE = """
              AND NOT p.hidden
              -- Likely closed: no current Overture source knows this OSM food place
              AND NOT p.unconfirmed
              -- The owner marked it "may have closed": listed with a warning, never suggested
              AND p.review IS NULL
              -- Cafés on a campus, in a hospital or a factory site are not planned (osm/OsmContextImporter)
              AND NOT p.inside_institution
            """;

    // Plannable places that can fill a stop: one of the categories, or tagged with the stop's matching tag
    String FILLS_STOP = PLANNABLE + """
              AND (p.category IN (:categories)
                   OR (CAST(:tag AS text) IS NOT NULL AND CAST(:tag AS text) = ANY (p.tags)))
            """;

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
            """ + LISTED + """
            ORDER BY "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findNearby(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("category") String category,
            @Param("tag") String tag,
            @Param("limit") int limit
    );

    // Places that can fill a stop: one of the categories, or tagged with the stop's matching tag
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            """ + FILLS_STOP + """
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

    // Like findCandidates, but a random sample of the whole circle (home suggestions: not always the nearest few)
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            """ + FILLS_STOP + """
            ORDER BY random()
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> sampleCandidates(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("categories") Collection<String> categories,
            @Param("tag") String tag,
            @Param("limit") int limit
    );

    /**
     * Like findCandidates, but only places that match the user's interests (planning/InterestMatcher): one of the
     * interest tags (comma separated, "" for none) or, when nearSea, within ~300 m of the coast. The nearest 40
     * candidates of a busy area can all be ordinary cafés; this finds the fitting ones a little further away.
     */
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            """ + FILLS_STOP + """
              AND (p.tags && string_to_array(CAST(:interestTags AS text), ',') OR (:nearSea AND p.near_sea))
            ORDER BY "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findInterestCandidates(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("categories") Collection<String> categories,
            @Param("tag") String tag,
            @Param("interestTags") String interestTags,
            @Param("nearSea") boolean nearSea,
            @Param("limit") int limit
    );

    /**
     * Like findCandidates, but only places whose name, cuisine or tags contain one of the words the user asked for
     * when swapping a stop (planning/StopWish: "kebap" -> "%kebap%", comma separated). Turkish letters are folded.
     */
    @Query(value = """
            SELECT p.id AS id,
                   ST_Distance(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography)) AS "distanceMeters"
            FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            """ + FILLS_STOP + """
              AND lower(translate(p.name || ' ' || coalesce(p.cuisine, '') || ' ' || array_to_string(p.tags, ' '),
                                  'İIıŞşĞğÜüÖöÇç', 'iiissgguuoocc'))
                  LIKE ANY (string_to_array(CAST(:patterns AS text), ','))
            ORDER BY "distanceMeters"
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaceDistance> findWishCandidates(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("categories") Collection<String> categories,
            @Param("tag") String tag,
            @Param("patterns") String patterns,
            @Param("limit") int limit
    );

    // Visible, plannable places of these categories around a point (is the area worth planning a route in?)
    @Query(value = """
            SELECT count(*) FROM places p
            WHERE ST_DWithin(p.location, CAST(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326) AS geography), :radius)
            """ + PLANNABLE + """
              AND p.category IN (:categories)
            """, nativeQuery = true)
    long countPlannable(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radius") double radiusMeters,
            @Param("categories") Collection<String> categories
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
            """ + FILLS_STOP + """
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
            """ + LISTED + """
            ORDER BY CASE WHEN p.source IN ('OSM', 'OVERTURE') THEN 1 ELSE 0 END, "distanceMeters"
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
            @Param("tag") String tag,
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
