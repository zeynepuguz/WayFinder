package com.nomi.wayfinder.dto;

/**
 * One district (ilçe) of a city for GET /api/v1/districts?city={slug}.
 *
 * @param latitude   label point (the district centre OSM names, else a point inside it)
 * @param placeCount places (verified + OSM) inside the district
 */
public record DistrictResponse(
        String slug,
        String name,
        double latitude,
        double longitude,
        Double south,
        Double west,
        Double north,
        Double east,
        int placeCount
) {
}
