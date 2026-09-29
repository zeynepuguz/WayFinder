package com.nomi.wayfinder.area;

/**
 * A city (its label point), a district (its label point) or a neighbourhood (its OSM node) a route can start from.
 *
 * @param district for a neighbourhood, the district it lies in (may be null); for a district, its own name;
 *                 null for a city
 * @param city     the city (province) it lies in, e.g. "Ankara"; for a city, its own name. null = unknown
 * @param citySlug     the city's slug ("ankara"), so a route can start there like "Yeni rota > şehir"; null = unknown
 * @param districtSlug for a district, its slug ("cankaya"); null otherwise
 */
public record NamedArea(String name, Kind kind, double latitude, double longitude, String district, String city,
                        String citySlug, String districtSlug) {

    public NamedArea(String name, Kind kind, double latitude, double longitude, String district) {
        this(name, kind, latitude, longitude, district, null);
    }

    public NamedArea(String name, Kind kind, double latitude, double longitude, String district, String city) {
        this(name, kind, latitude, longitude, district, city, null, null);
    }

    public enum Kind {
        CITY,
        DISTRICT,
        AREA
    }
}
