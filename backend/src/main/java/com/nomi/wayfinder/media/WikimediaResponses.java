package com.nomi.wayfinder.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The parts of the Wikidata and Commons API answers WikimediaParser reads.
 * Both APIs answer 200 with {"error": {"code": ...}} on problems (e.g. "maxlag" when the servers are busy).
 */
final class WikimediaResponses {

    private WikimediaResponses() {
    }

    // id: Wikidata names the unknown item on "no-such-entity" (one bad id fails the whole request)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiError(String code, String info, String id) {
    }

    // action=wbgetentities&props=claims
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WikidataResponse(Map<String, Entity> entities, ApiError error) {
    }

    // missing is present (as "") when the item does not exist; redirects when the requested id was merged
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entity(String id, Object missing, Redirect redirects, Map<String, List<Claim>> claims) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Redirect(String from, String to) {
    }

    // rank: preferred / normal / deprecated
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Claim(String rank, Snak mainsnak) {
    }

    // snaktype: value / somevalue / novalue
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snak(String snaktype, DataValue datavalue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataValue(Object value, String type) {
    }

    // action=query&prop=imageinfo&formatversion=2
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommonsResponse(Query query, ApiError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Query(List<Redirect> normalized, List<Redirect> redirects, List<Page> pages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(String title, Boolean missing, Boolean invalid, List<ImageInfo> imageinfo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageInfo(String thumburl, String url, String descriptionurl, Map<String, MetadataValue> extmetadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetadataValue(Object value) {
    }
}
