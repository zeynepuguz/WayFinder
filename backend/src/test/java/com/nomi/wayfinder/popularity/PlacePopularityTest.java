package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.popularity.WikiPopularityParser.Sitelinks;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// Sample Wikidata / pageviews answers (shortened real shapes); no network
class PlacePopularityTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void scoreIsLogScaledAndWeighsLanguagesDouble() {
        assertThat(PlacePopularity.score(0, 0)).isZero();
        assertThat(PlacePopularity.score(150, 300_000)).isCloseTo(2 * Math.log(151) + Math.log(300_001), within(1e-9));
        assertThat(PlacePopularity.score(150, 300_000)).isCloseTo(22.6, within(0.1));
        assertThat(PlacePopularity.score(2, 300)).isCloseTo(7.9, within(0.1));
        // A place known worldwide beats one read a lot only locally
        assertThat(PlacePopularity.score(60, 20_000)).isGreaterThan(PlacePopularity.score(3, 60_000));
        assertThat(PlacePopularity.score(-1, -5)).isZero();
    }

    @Test
    void readsWikipediaSitelinksAndTitles() {
        String json = """
                {"entities": {
                  "Q12506": {"type": "item", "id": "Q12506", "sitelinks": {
                     "trwiki": {"site": "trwiki", "title": "Ayasofya"},
                     "enwiki": {"site": "enwiki", "title": "Hagia Sophia"},
                     "dewiki": {"site": "dewiki", "title": "Hagia Sophia"},
                     "zh_min_nanwiki": {"site": "zh_min_nanwiki", "title": "Hagia Sophia"},
                     "commonswiki": {"site": "commonswiki", "title": "Category:Hagia Sophia"},
                     "enwikivoyage": {"site": "enwikivoyage", "title": "Hagia Sophia"},
                     "enwikiquote": {"site": "enwikiquote", "title": "Hagia Sophia"}}},
                  "Q999": {"type": "item", "id": "Q999", "sitelinks": {}},
                  "Q5": {"id": "Q5", "missing": ""},
                  "Q7": {"type": "item", "id": "Q8", "redirects": {"from": "Q7", "to": "Q8"},
                         "sitelinks": {"enwiki": {"site": "enwiki", "title": "Galata Tower"}}}
                }}
                """;
        Map<String, Sitelinks> links = WikiPopularityParser.sitelinks(json, jsonMapper);

        // Wikipedias only: not Commons, Wikivoyage or Wikiquote
        assertThat(links.get("Q12506")).isEqualTo(new Sitelinks(4, "Ayasofya", "Hagia Sophia"));
        assertThat(links.get("Q999")).isEqualTo(new Sitelinks(0, null, null));
        assertThat(links).doesNotContainKey("Q5");
        // Merged item: under the requested id too
        assertThat(links.get("Q7")).isEqualTo(new Sitelinks(1, null, "Galata Tower"));
        assertThat(links.get("Q8")).isEqualTo(links.get("Q7"));
    }

    @Test
    void apiErrorsNameTheUnknownItem() {
        assertThatThrownBy(() -> WikiPopularityParser.sitelinks(
                "{\"error\": {\"code\": \"no-such-entity\", \"info\": \"Could not find Q1\", \"id\": \"Q1\"}}", jsonMapper))
                .isInstanceOfSatisfying(WikiPopularityParser.ApiErrorException.class, e -> {
                    assertThat(e.code()).isEqualTo("no-such-entity");
                    assertThat(e.id()).isEqualTo("Q1");
                });
    }

    @Test
    void sumsDailyPageviewsPerRequestedTitle() {
        String json = """
                {"batchcomplete": true, "continue": {"pvipcontinue": "Galata_Kulesi", "continue": "||"},
                 "query": {"normalized": [{"fromencoded": false, "from": "Galata_Kulesi", "to": "Galata Kulesi"}],
                           "redirects": [{"from": "Galata Tower", "to": "Galata Kulesi"}],
                           "pages": [
                             {"pageid": 1, "ns": 0, "title": "Ayasofya",
                              "pageviews": {"2026-08-01": 300, "2026-08-02": null, "2026-08-03": 250}},
                             {"pageid": 2, "ns": 0, "title": "Galata Kulesi", "pageviews": {"2026-08-01": 700}},
                             {"ns": 0, "title": "Yok Böyle Sayfa", "missing": true}]}}
                """;
        WikiPopularityParser.PageviewPage page = WikiPopularityParser.pageviews(json,
                List.of("Ayasofya", "Galata_Kulesi", "Galata Tower", "Yok Böyle Sayfa"), jsonMapper);

        // Days without data count 0; normalized / redirected titles come back under the requested spelling
        assertThat(page.views()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Ayasofya", 550L, "Galata_Kulesi", 700L, "Galata Tower", 700L));
        assertThat(page.continuation()).isEqualTo("Galata_Kulesi");
        assertThat(WikiPopularityParser.pageviews("{\"batchcomplete\": true}", List.of("A"), jsonMapper).views()).isEmpty();
    }

    @Test
    void tooManyRequestsWaitsForRetryAfter() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        assertThat(WikiPopularityClient.retryAfter(headers)).isNull();
        headers.set("Retry-After", "42");
        assertThat(WikiPopularityClient.retryAfter(headers)).isEqualTo(java.time.Duration.ofSeconds(42));
        headers.set("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(WikiPopularityClient.retryAfter(headers)).isNull();
    }

    @Test
    void theWikidataItemMustBeAboutThePlace() {
        assertThat(PlacePopularity.aboutThePlace("Sultanahmet Camii", "Sultan Ahmed Camii", "Blue Mosque")).isTrue();
        assertThat(PlacePopularity.aboutThePlace("Aya İrini", "Aya İrini Kilisesi", "Hagia Irene")).isTrue();
        assertThat(PlacePopularity.aboutThePlace("Kapalı Çarşı", "Kapalıçarşı", "Grand Bazaar, Istanbul")).isTrue();
        assertThat(PlacePopularity.aboutThePlace("Rumeli Hisarı", null, "Rumelihisarı")).isTrue();
        assertThat(PlacePopularity.aboutThePlace("Adı Olmayan", null, null)).isTrue();
        // OSM tagged the tomb with the sultan buried in it
        assertThat(PlacePopularity.aboutThePlace("Sultan Ahmet Türbesi", "I. Ahmed", "Ahmed I")).isFalse();
        assertThat(PlacePopularity.aboutThePlace("Mimar Sinan Türbesi", "Mimar Sinan", "Mimar Sinan")).isTrue();
        assertThat(PlacePopularity.aboutThePlace("Beyazıt Kulesi", "Osmanlı İmparatorluğu", "Ottoman Empire")).isFalse();
    }

    @Test
    void failedLookupsAreRetriedAndUnknownItemsCountAsNoArticles() {
        List<PlacePopularityService.Pending> pending = List.of(
                new PlacePopularityService.Pending(1, "Q12506", "Ayasofya"),
                new PlacePopularityService.Pending(2, "Q12506", "Ayasofya Camii"),
                new PlacePopularityService.Pending(3, "Q5", "Bilinmeyen"),
                new PlacePopularityService.Pending(4, "Q77", "Galata Kulesi"),
                new PlacePopularityService.Pending(5, "Q193634", "Sultan Ahmet Türbesi"));
        List<PlacePopularityService.Outcome> outcomes = PlacePopularityService.decide(pending,
                Map.of("Q12506", new Sitelinks(4, "Ayasofya", "Hagia Sophia"),
                        "Q193634", new Sitelinks(72, "I. Ahmed", "Ahmed I")),
                Map.of("Q12506", 120_000L, "Q193634", 115_000L), Set.of("Q77"));

        assertThat(outcomes).containsExactly(
                new PlacePopularityService.Outcome(1, 4, 120_000, false),
                new PlacePopularityService.Outcome(2, 4, 120_000, false),
                new PlacePopularityService.Outcome(3, 0, 0, false),
                // about the sultan, not the tomb
                new PlacePopularityService.Outcome(5, 72, 115_000, true));
    }
}
