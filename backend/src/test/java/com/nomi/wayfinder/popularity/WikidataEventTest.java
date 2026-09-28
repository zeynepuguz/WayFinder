package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.popularity.WikiPopularityParser.Sitelinks;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// "instance of" (P31) from the Wikidata answer: an OSM sight that only marks an event is not a place
class WikidataEventTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsInstanceOfFromTheClaims() {
        String json = """
                {"entities": {
                  "Q13410316": {"type": "item", "id": "Q13410316",
                    "sitelinks": {"trwiki": {"site": "trwiki", "title": "Gezi Parkı protestoları"},
                                  "enwiki": {"site": "enwiki", "title": "Gezi Park protests"}},
                    "claims": {
                      "P31": [{"mainsnak": {"snaktype": "value", "property": "P31",
                                "datavalue": {"value": {"entity-type": "item", "numeric-id": 273120, "id": "Q273120"},
                                              "type": "wikibase-entityid"}}}],
                      "P585": [{"mainsnak": {"snaktype": "value", "property": "P585",
                                "datavalue": {"value": {"time": "+2013-05-28T00:00:00Z"}, "type": "time"}}}],
                      "P373": [{"mainsnak": {"snaktype": "value", "property": "P373",
                                "datavalue": {"value": "Gezi Park protests", "type": "string"}}}]
                    }},
                  "Q12506": {"type": "item", "id": "Q12506",
                    "sitelinks": {"trwiki": {"site": "trwiki", "title": "Ayasofya"}},
                    "claims": {"P31": [{"mainsnak": {"snaktype": "somevalue", "property": "P31"}}]}}
                }}
                """;
        Map<String, Sitelinks> links = WikiPopularityParser.sitelinks(json, jsonMapper);

        assertThat(links.get("Q13410316").instanceOf()).containsExactly("Q273120");
        assertThat(links.get("Q13410316").wikipedias()).isEqualTo(2);
        assertThat(links.get("Q12506").instanceOf()).isEmpty();
    }

    @Test
    void anEventMarkerIsHiddenButARealPlaceWithAWrongItemIsOnlyIgnored() {
        Sitelinks protest = new Sitelinks(30, "Gezi Parkı protestoları", "Gezi Park protests", Set.of("Q273120"));
        Sitelinks person = new Sitelinks(70, "I. Ahmed", "Ahmed I", Set.of("Q5"));
        List<PlacePopularityService.Outcome> outcomes = PlacePopularityService.decide(List.of(
                        new PlacePopularityService.Pending(1, "Q13410316", "Gezi Parkı olaylarının yeri", "ATTRACTION", "OSM"),
                        new PlacePopularityService.Pending(2, "Q13410316", "Gezi Parkı", "PARK", "OSM"),
                        new PlacePopularityService.Pending(3, "Q193634", "Sultan Ahmet Türbesi", "ATTRACTION", "OSM"),
                        new PlacePopularityService.Pending(4, "Q13410316", "Anıt", "ATTRACTION", "WEB_CHECK")),
                Map.of("Q13410316", protest, "Q193634", person), Map.of("Q13410316", 50_000L), Set.of());

        // The marker: not a place (hidden), popularity unknown
        assertThat(outcomes.get(0).event()).isTrue();
        assertThat(outcomes.get(0).mismatch()).isTrue();
        // The park is real: kept, but the protests' fame is not the park's
        assertThat(outcomes.get(1).event()).isFalse();
        assertThat(outcomes.get(1).mismatch()).isTrue();
        // A tomb tagged with the person buried there
        assertThat(outcomes.get(2).event()).isFalse();
        assertThat(outcomes.get(2).mismatch()).isTrue();
        // Verified places are never hidden by this
        assertThat(outcomes.get(3).event()).isFalse();
    }

    @Test
    void eventClassesAreOnlyThingsThatHappened() {
        assertThat(PlacePopularity.isEvent(Set.of("Q273120"))).isTrue();
        assertThat(PlacePopularity.isEvent(Set.of("Q178561"))).isTrue();
        // mosque, park
        assertThat(PlacePopularity.isEvent(Set.of("Q32815", "Q22698"))).isFalse();
        assertThat(PlacePopularity.isPerson(Set.of("Q5"))).isTrue();
    }
}
