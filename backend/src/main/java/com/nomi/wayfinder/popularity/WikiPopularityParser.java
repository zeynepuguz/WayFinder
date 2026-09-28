package com.nomi.wayfinder.popularity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Reads the Wikidata sitelinks and the Wikipedia pageviews answers (pure logic, unit tested with sample JSON).
 */
final class WikiPopularityParser {

    private WikiPopularityParser() {
    }

    /**
     * The Wikipedia articles of one Wikidata item.
     *
     * @param wikipedias number of Wikipedia language editions with an article
     * @param trTitle    Turkish Wikipedia article title, null when there is none
     * @param enTitle    English Wikipedia article title, null when there is none
     */
    record Sitelinks(int wikipedias, String trTitle, String enTitle) {
    }

    // The API reported a problem; "no-such-entity" names the unknown id (one bad id fails the whole request)
    static final class ApiErrorException extends RuntimeException {

        private final String code;
        private final String id;

        ApiErrorException(ApiError error) {
            super("Wikimedia API error " + error.code() + ": " + error.info());
            this.code = error.code();
            this.id = error.id();
        }

        String code() {
            return code;
        }

        String id() {
            return id;
        }
    }

    /**
     * wbgetentities&props=sitelinks -> requested id -> its articles. Missing items are left out; a merged
     * (redirected) item is returned under the requested id too.
     */
    static Map<String, Sitelinks> sitelinks(String json, JsonMapper jsonMapper) {
        SitelinksResponse response = jsonMapper.readValue(json, SitelinksResponse.class);
        if (response.error() != null) {
            throw new ApiErrorException(response.error());
        }
        Map<String, Sitelinks> result = new LinkedHashMap<>();
        if (response.entities() == null) {
            return result;
        }
        for (Map.Entry<String, Entity> entry : response.entities().entrySet()) {
            Entity entity = entry.getValue();
            if (entity == null || entity.missing() != null) {
                continue;
            }
            Map<String, Sitelink> links = entity.sitelinks() == null ? Map.of() : entity.sitelinks();
            Sitelinks sitelinks = new Sitelinks(PlacePopularity.wikipediaCount(links), title(links.get("trwiki")),
                    title(links.get("enwiki")));
            result.put(entry.getKey(), sitelinks);
            if (entity.redirects() != null && entity.redirects().from() != null) {
                result.put(entity.redirects().from(), sitelinks);
            }
            if (entity.id() != null) {
                result.put(entity.id(), sitelinks);
            }
        }
        return result;
    }

    /**
     * One answer of query&prop=pageviews (formatversion=2).
     *
     * @param views        requested title -> views summed over the days of this answer (days without data count 0)
     * @param continuation the pvipcontinue value when the API has more pages to report, else null
     */
    record PageviewPage(Map<String, Long> views, String continuation) {
    }

    /**
     * Daily pageviews of up to 50 articles -> per requested title the sum. Titles are followed through the API's
     * normalization ("Galata_Kulesi" -> "Galata Kulesi") and redirects back to the requested spelling; articles
     * the API reports nothing for are absent.
     */
    static PageviewPage pageviews(String json, Collection<String> requestedTitles, JsonMapper jsonMapper) {
        QueryResponse response = jsonMapper.readValue(json, QueryResponse.class);
        if (response.error() != null) {
            throw new ApiErrorException(response.error());
        }
        Map<String, Long> byTitle = new HashMap<>();
        Map<String, String> renamed = new HashMap<>();
        if (response.query() != null) {
            for (List<Redirect> list : Arrays.asList(response.query().normalized(), response.query().redirects())) {
                if (list != null) {
                    list.stream().filter(r -> r.from() != null && r.to() != null).forEach(r -> renamed.put(r.from(), r.to()));
                }
            }
            if (response.query().pages() != null) {
                for (Page page : response.query().pages()) {
                    if (page == null || page.title() == null || page.pageviews() == null) {
                        continue;
                    }
                    long sum = page.pageviews().values().stream().filter(Objects::nonNull)
                            .mapToLong(v -> Math.max(0, v)).sum();
                    byTitle.put(page.title(), sum);
                }
            }
        }
        Map<String, Long> views = new LinkedHashMap<>();
        for (String requested : requestedTitles) {
            String title = requested;
            for (int hop = 0; hop < 3 && renamed.containsKey(title); hop++) {
                title = renamed.get(title);
            }
            Long sum = byTitle.get(title);
            if (sum != null) {
                views.put(requested, sum);
            }
        }
        String continuation = response.continuation() == null ? null : response.continuation().get("pvipcontinue");
        return new PageviewPage(views, continuation);
    }

    private static String title(Sitelink link) {
        return link == null || link.title() == null || link.title().isBlank() ? null : link.title().trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiError(String code, String info, String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SitelinksResponse(Map<String, Entity> entities, ApiError error) {
    }

    // missing is present (as "") when the item does not exist; redirects when the requested id was merged
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entity(String id, Object missing, Redirect redirects, Map<String, Sitelink> sitelinks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Redirect(String from, String to) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sitelink(String site, String title) {
    }

    // action=query&prop=pageviews&formatversion=2; "continue" holds pvipcontinue when not every page was reported
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResponse(Query query, ApiError error, @JsonProperty("continue") Map<String, String> continuation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Query(List<Redirect> normalized, List<Redirect> redirects, List<Page> pages) {
    }

    // pageviews: "2026-08-01" -> views (null for days without data)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(String title, Boolean missing, Map<String, Long> pageviews) {
    }
}
