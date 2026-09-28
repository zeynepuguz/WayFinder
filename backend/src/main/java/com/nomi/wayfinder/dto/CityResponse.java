package com.nomi.wayfinder.dto;

/**
 * One Turkish city (il) for GET /api/v1/cities and /api/v1/cities/at.
 *
 * @param latitude      label point (the province's admin centre in OSM, else a point inside it)
 * @param placeCount    places (verified + OSM) inside the city; 0 = not imported yet
 * @param districtCount districts (ilçeler) imported for the city
 */
public record CityResponse(
        String slug,
        String name,
        double latitude,
        double longitude,
        Double south,
        Double west,
        Double north,
        Double east,
        int placeCount,
        int districtCount
) {
}
