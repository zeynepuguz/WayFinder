package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import com.nomi.wayfinder.popularity.WikiPopularityParser.Sitelinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores how popular places with a Wikidata id are in the real world (see PlacePopularity for the formula):
 * 1. Wikidata sitelinks (batches of 50): number of Wikipedia language editions + the Turkish / English titles
 * 2. Wikipedia pageviews of those two articles over the last PopularityProperties.pageviewDays days (50 per request)
 * 3. wiki_sitelinks, wiki_pageviews, popularity, popularity_checked_at; when the item is evidently about something
 *    else (PlacePopularity.aboutThePlace: OSM tagged a tomb with the sultan's item) the popularity stays NULL
 *
 * Only real API answers are stored. A place whose lookup failed (network, busy servers) is left unchecked and
 * retried on the next pass; places without a Wikidata id keep popularity NULL (unknown, never 0).
 * Hidden places are skipped. Writes use JDBC so JPA saves never overwrite them.
 */
@Service
public class PlacePopularityService {

    private static final Logger log = LoggerFactory.getLogger(PlacePopularityService.class);

    private static final String PENDING = """
            SELECT id, wikidata, name, category, source FROM places
            WHERE wikidata IS NOT NULL AND NOT hidden
              AND (CAST(? AS bigint) IS NULL OR city_id = ?)
              AND (popularity_checked_at IS NULL
                   OR popularity_checked_at < now() - CAST(? AS double precision) * interval '1 second')
            ORDER BY popularity_checked_at NULLS FIRST, id
            LIMIT ?
            """;

    private static final String WRITE = """
            UPDATE places SET wiki_sitelinks = ?, wiki_pageviews = ?, popularity = ?, popularity_checked_at = now()
            WHERE id = ?
            """;

    private static final String HIDE_EVENT = """
            UPDATE places SET not_a_place = TRUE, hidden = TRUE, updated_at = now()
            WHERE id = ? AND source = 'OSM'
            """;

    private final WikiPopularityClient client;
    private final JdbcTemplate jdbc;
    private final PopularityProperties properties;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PlacePopularityService(WikiPopularityClient client, JdbcTemplate jdbc, PopularityProperties properties,
                                  ApplicationEventPublisher events) {
        this.client = client;
        this.jdbc = jdbc;
        this.properties = properties;
        this.events = events;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * One pass (409 when one is already running).
     *
     * @param cityId only this city's places; null = every city
     */
    public UpdateResult update(Long cityId) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "A popularity update is already running");
        }
        try {
            long started = System.currentTimeMillis();
            List<Pending> pending = jdbc.query(PENDING,
                    (rs, i) -> new Pending(rs.getLong("id"), rs.getString("wikidata"), rs.getString("name"),
                            rs.getString("category"), rs.getString("source")),
                    cityId, cityId, properties.recheckAfter().toSeconds(), Math.max(1, properties.maxPlacesPerRun()));
            if (pending.isEmpty()) {
                log.info("Popularity: nothing to check{}", cityId == null ? "" : " in city " + cityId);
                return new UpdateResult(0, 0, 0, 0, 0);
            }
            log.info("Popularity: checking {} places{}...", pending.size(), cityId == null ? "" : " of city " + cityId);

            // 1. Wikidata: articles per item
            Map<String, Sitelinks> sitelinks = new HashMap<>();
            Set<String> failed = new HashSet<>();
            List<String> ids = pending.stream().map(Pending::wikidata).distinct().toList();
            for (int from = 0; from < ids.size(); from += WikiPopularityClient.BATCH_SIZE) {
                List<String> batch = ids.subList(from, Math.min(ids.size(), from + WikiPopularityClient.BATCH_SIZE));
                try {
                    sitelinks.putAll(client.fetchSitelinks(batch));
                } catch (RuntimeException e) {
                    log.warn("Popularity: Wikidata lookup of {} items failed, retried next run: {}", batch.size(),
                            e.getMessage());
                    failed.addAll(batch);
                }
            }

            // 2. Pageviews of the Turkish and English articles, 50 per request
            Map<String, Long> views = new HashMap<>();
            for (String language : List.of("tr", "en")) {
                Map<String, List<String>> itemsByTitle = new LinkedHashMap<>();
                for (String id : ids) {
                    Sitelinks links = sitelinks.get(id);
                    String title = links == null ? null : "tr".equals(language) ? links.trTitle() : links.enTitle();
                    if (title != null && !failed.contains(id)) {
                        itemsByTitle.computeIfAbsent(title, t -> new ArrayList<>()).add(id);
                    }
                }
                List<String> titles = new ArrayList<>(itemsByTitle.keySet());
                for (int from = 0; from < titles.size(); from += WikiPopularityClient.BATCH_SIZE) {
                    List<String> batch = titles.subList(from, Math.min(titles.size(), from + WikiPopularityClient.BATCH_SIZE));
                    try {
                        Map<String, Long> counts = client.fetchViews(language, batch);
                        for (String title : batch) {
                            long count = counts.getOrDefault(title, 0L);
                            itemsByTitle.get(title).forEach(id -> views.merge(id, count, Long::sum));
                        }
                    } catch (RuntimeException e) {
                        log.warn("Popularity: {} Wikipedia pageviews of {} articles failed, retried next run: {}",
                                language, batch.size(), e.getMessage());
                        batch.forEach(title -> failed.addAll(itemsByTitle.get(title)));
                    }
                }
            }

            // 3. Store (items Wikidata does not know: checked, no articles -> 0)
            List<Outcome> outcomes = decide(pending, sitelinks, views, failed);
            // An OSM sight that only marks an event (its item is a protest, a battle ...): not a place, hidden
            List<Outcome> eventMarkers = outcomes.stream().filter(Outcome::event).toList();
            if (!eventMarkers.isEmpty()) {
                jdbc.batchUpdate(HIDE_EVENT, eventMarkers, 500, (ps, o) -> ps.setLong(1, o.placeId()));
                eventMarkers.stream().limit(10).forEach(o -> log.info(
                        "Popularity: place {} only marks an event (Wikidata {}), hidden", o.placeId(),
                        pending.stream().filter(p -> p.id() == o.placeId()).map(Pending::wikidata).findFirst().orElse("?")));
            }
            jdbc.batchUpdate(WRITE, outcomes, 500, (ps, o) -> {
                // An item about something else: checked, but the place's popularity stays unknown
                if (o.mismatch()) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                    ps.setNull(2, java.sql.Types.INTEGER);
                    ps.setNull(3, java.sql.Types.DOUBLE);
                } else {
                    ps.setInt(1, o.sitelinks());
                    ps.setLong(2, Math.min(Integer.MAX_VALUE, o.pageviews()));
                    ps.setDouble(3, PlacePopularity.score(o.sitelinks(), o.pageviews()));
                }
                ps.setLong(4, o.placeId());
            });

            UpdateResult result = new UpdateResult(pending.size(), outcomes.size(),
                    (int) outcomes.stream().filter(o -> !o.mismatch() && o.sitelinks() > 0).count(),
                    (int) outcomes.stream().filter(Outcome::mismatch).count(), pending.size() - outcomes.size());
            outcomes.stream().filter(Outcome::mismatch).limit(10).forEach(o -> log.info(
                    "Popularity: the Wikidata item of place {} is about something else, ignored", o.placeId()));
            log.info("Popularity finished in {} s (pageviews of the last {} days): {}",
                    (System.currentTimeMillis() - started) / 1000, properties.pageviewDays(), result);
            if (!outcomes.isEmpty()) {
                events.publishEvent(new PlacesChangedEvent("popularity"));
            }
            return result;
        } finally {
            running.set(false);
        }
    }

    static List<Outcome> decide(List<Pending> pending, Map<String, Sitelinks> sitelinks, Map<String, Long> views,
                                Set<String> failed) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Pending place : pending) {
            if (failed.contains(place.wikidata())) {
                continue;
            }
            Sitelinks links = sitelinks.get(place.wikidata());
            int count = links == null ? 0 : links.wikipedias();
            long pageviews = views.getOrDefault(place.wikidata(), 0L);
            Set<String> classes = links == null ? Set.of() : links.instanceOf();
            // The item is a thing that happened: an OSM sight that only marks it is not a place; a real park or
            // museum tagged with it just has the wrong item (its popularity is not the event's)
            boolean eventItem = PlacePopularity.isEvent(classes);
            boolean event = eventItem && "ATTRACTION".equals(place.category())
                    && (place.source() == null || "OSM".equals(place.source()));
            boolean mismatch = links != null
                    && (!PlacePopularity.aboutThePlace(place.name(), links.trTitle(), links.enTitle())
                    || eventItem || PlacePopularity.isPerson(classes));
            outcomes.add(new Outcome(place.id(), count, pageviews, mismatch, event));
        }
        return outcomes;
    }

    // category / source: null when unknown (treated as an OSM place of unknown kind)
    record Pending(long id, String wikidata, String name, String category, String source) {

        Pending(long id, String wikidata, String name) {
            this(id, wikidata, name, null, null);
        }
    }

    // mismatch: the Wikidata item is about something else (PlacePopularity.aboutThePlace, a person, an event);
    // event: the place is only an OSM marker of an event (hidden)
    record Outcome(long placeId, int sitelinks, long pageviews, boolean mismatch, boolean event) {

        Outcome(long placeId, int sitelinks, long pageviews, boolean mismatch) {
            this(placeId, sitelinks, pageviews, mismatch, false);
        }
    }

    /**
     * @param checked        places looked at
     * @param updated        checked and stored
     * @param withWikipedia  of those, with at least one Wikipedia article
     * @param mismatched     of those, whose Wikidata item is about something else (popularity left unknown)
     * @param failed         lookup failed (network / busy servers); retried on the next pass
     */
    public record UpdateResult(int checked, int updated, int withWikipedia, int mismatched, int failed) {
    }
}
