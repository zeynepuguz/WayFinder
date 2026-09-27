package com.nomi.wayfinder.osm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The parts of an Overpass API JSON answer ("out center tags") the importer reads.
 * Nodes carry lat/lon; ways and relations carry a center point instead.
 * remark: Overpass puts runtime errors (e.g. timeouts) here while still answering 200.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OverpassResponse(List<Element> elements, String remark) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Element(String type, long id, Double lat, Double lon, Center center, Map<String, String> tags) {

        public Double latitude() {
            return lat != null ? lat : center == null ? null : center.lat();
        }

        public Double longitude() {
            return lon != null ? lon : center == null ? null : center.lon();
        }

        public String tag(String key) {
            return tags == null ? null : tags.get(key);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Center(Double lat, Double lon) {
    }
}
