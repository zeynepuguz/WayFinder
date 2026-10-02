package com.nomi.wayfinder.osm;

import java.util.List;

/**
 * Where OpenStreetMap data comes from, in the shape of an Overpass API answer, so every importer works the same with
 * both sources:
 * - OverpassClient: live queries against the Overpass API (one city at a time, often slow / overloaded)
 * - PbfOsmSource: a Geofabrik extract of Türkiye (.osm.pbf) read locally with DuckDB (all cities in minutes)
 * OsmSourceConfig picks one (nomi.osm-pbf.enabled).
 */
public interface OsmSource {

    // Türkiye's provinces: admin_level=4 boundary relations with their member ways' geometry ("out geom")
    List<OverpassResponse.Element> fetchProvinces();

    // A city's food / drink / grocery places, places of worship and sights with a centre point ("out center")
    List<OverpassResponse.Element> fetchPlaces(long relationId);

    /**
     * The same, district by district where that helps (OverpassClient: small queries a busy server still answers);
     * a source that reads everything at once ignores the districts.
     */
    default List<OverpassResponse.Element> fetchPlaces(long relationId, List<Long> districtRelationIds) {
        return fetchPlaces(relationId);
    }

    // A city's districts: admin_level=6 boundary relations with geometry ("out geom")
    List<OverpassResponse.Element> fetchDistricts(long relationId);

    // A city's named neighbourhoods: place=suburb / quarter / neighbourhood nodes
    List<OverpassResponse.Element> fetchAreas(long relationId);

    // A city's campuses, schools, hospitals, prisons, military / industrial areas with geometry ("out geom")
    List<OverpassResponse.Element> fetchInstitutions(long relationId);

    // natural=coastline ways in the box with geometry; empty inland
    List<OverpassResponse.Element> fetchCoastline(double south, double west, double north, double east);
}
