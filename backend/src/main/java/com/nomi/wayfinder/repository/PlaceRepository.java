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
