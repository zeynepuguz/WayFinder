package com.nomi.wayfinder.osm;

/**
 * A city (province) row the importers work on.
 *
 * @param id         cities.id
 * @param relationId the province's OSM relation id (223474 for İstanbul); its Overpass area is 3600000000 + this
 */
public record OsmCity(long id, String slug, String name, long relationId) {
}
