package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-off (and repeatable) pass over OSM rows already in the database, applying what new imports do on the way in
 * (POST /api/v1/admin/places/normalize):
 * - names: PlaceNames.clean (broken casing, spaces, quotes). OSM name:tr / name:en are not stored for old rows;
 *   the next import of the city takes them from OSM.
 * - non-places: names that describe an event or are a sentence (PlaceNames.describesEventOrSentence), and names
 *   PlaceRealismFilter rejects (checked on the name as stored, before its casing is repaired) for rows without
 *   opening hours / Wikidata (like PlaceRealismCleanup): deleted when nothing references them, else hidden.
 * - interest tags derived from the name, category and stored cuisine (PlaceTags).
 * Verified (non-OSM) places are never touched.
 */
@Service
public class PlaceDataNormalizer {

    private static final Logger log = LoggerFactory.getLogger(PlaceDataNormalizer.class);
    static final int MAX_EXAMPLES = 40;

    private final JdbcTemplate jdbc;
    private final OsmPlaceImporter importer;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PlaceDataNormalizer(JdbcTemplate jdbc, OsmPlaceImporter importer, TransactionTemplate transactions,
                               ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.transactions = transactions;
        this.events = events;
    }

    /**
     * @param dryRun only report what would change
     */
    public NormalizeResult run(boolean dryRun) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "A place normalization is already running");
        }
        try {
            List<Row> rows = jdbc.query("""
                            SELECT p.id, p.osm_id, p.name, p.category, p.tags, p.cuisine,
                                   (p.wikidata IS NOT NULL
                                    OR EXISTS (SELECT 1 FROM place_opening_hours h WHERE h.place_id = p.id)) AS business
                            FROM places p
                            WHERE p.source = 'OSM' AND p.osm_id IS NOT NULL AND NOT p.hidden
                            """,
                    (rs, i) -> {
                        Array tags = rs.getArray("tags");
                        return new Row(rs.getLong("id"), rs.getString("osm_id"), rs.getString("name"),
                                PlaceCategory.valueOf(rs.getString("category")),
                                tags == null ? List.of() : Arrays.asList((String[]) tags.getArray()),
                                rs.getString("cuisine"), rs.getBoolean("business"));
                    });

            Plan plan = plan(rows);
            int removed = 0;
            int hidden = 0;
            if (!dryRun) {
                transactions.executeWithoutResult(status ->
                        jdbc.batchUpdate("UPDATE places SET name = ?, tags = ?, updated_at = now() WHERE id = ? AND source = 'OSM'",
                                plan.updates(), 500, (ps, u) -> {
                                    ps.setString(1, u.name());
                                    ps.setArray(2, ps.getConnection().createArrayOf("text", u.tags().toArray()));
                                    ps.setLong(3, u.id());
                                }));
                OsmPlaceImporter.RemovedRows gone = importer.removeOrHide(plan.notPlaces());
                removed = gone.removed();
                hidden = gone.hidden();
                if (!plan.updates().isEmpty() || !plan.notPlaces().isEmpty()) {
                    events.publishEvent(new PlacesChangedEvent("normalize"));
                }
            }
            NormalizeResult result = new NormalizeResult(dryRun, rows.size(), plan.renamed(), plan.retagged(),
                    plan.notPlaces().size(), removed, hidden, plan.addedTags(), plan.renameExamples(),
                    plan.notPlaceExamples());
            log.info("Place normalization{}: {} checked, {} renamed, {} retagged, {} not places ({} removed, {} hidden), "
                            + "tags added {}", dryRun ? " (dry run)" : "", rows.size(), plan.renamed(), plan.retagged(),
                    plan.notPlaces().size(), removed, hidden, plan.addedTags());
            return result;
        } finally {
            running.set(false);
        }
    }

    // What would change (pure logic, unit tested)
    static Plan plan(List<Row> rows) {
        List<Update> updates = new ArrayList<>();
        List<String> notPlaces = new ArrayList<>();
        List<String> renameExamples = new ArrayList<>();
        List<String> notPlaceExamples = new ArrayList<>();
        Map<String, Integer> addedTags = new TreeMap<>();
        int renamed = 0;
        int retagged = 0;
        for (Row row : rows) {
            String reason = PlaceNames.describesEventOrSentence(row.name()) ? "describes an event / a sentence"
                    : row.business() ? null : PlaceRealismFilter.rejectName(row.name(), row.category());
            if (reason != null) {
                notPlaces.add(row.osmId());
                if (notPlaceExamples.size() < MAX_EXAMPLES) {
                    notPlaceExamples.add(row.name() + " (" + reason + ")");
                }
                continue;
            }
            String name = PlaceNames.clean(row.name());
            if (name == null) {
                name = row.name();
            }
            List<String> cuisines = row.cuisine() == null ? List.of()
                    : Arrays.stream(row.cuisine().toLowerCase(Locale.ROOT).split(";")).map(String::trim).toList();
            List<String> derived = PlaceTags.derive(name, row.category(), cuisines, Map.of(), row.tags());
            List<String> tags = PlaceTags.merge(row.tags(), derived);
            boolean nameChanged = !name.equals(row.name());
            boolean tagsChanged = !new HashSet<>(tags).equals(new HashSet<>(row.tags()));
            if (nameChanged) {
                renamed++;
                if (renameExamples.size() < MAX_EXAMPLES) {
                    renameExamples.add(row.name() + " -> " + name);
                }
            }
            if (tagsChanged) {
                retagged++;
                tags.stream().filter(t -> !row.tags().contains(t)).forEach(t -> addedTags.merge(t, 1, Integer::sum));
            }
            if (nameChanged || tagsChanged) {
                updates.add(new Update(row.id(), name, tags));
            }
        }
        return new Plan(updates, notPlaces, renamed, retagged, addedTags, renameExamples, notPlaceExamples);
    }

    /**
     * @param business has opening hours or a Wikidata item (the realism name rules are not applied, like
     *                 PlaceRealismCleanup)
     */
    record Row(long id, String osmId, String name, PlaceCategory category, List<String> tags, String cuisine,
               boolean business) {
    }

    record Update(long id, String name, List<String> tags) {
    }

    record Plan(List<Update> updates, List<String> notPlaces, int renamed, int retagged, Map<String, Integer> addedTags,
                List<String> renameExamples, List<String> notPlaceExamples) {
    }

    /**
     * @param addedTags        derived tag -> number of places that got it
     * @param renameExamples   "before -> after", up to MAX_EXAMPLES
     * @param notPlaceExamples "name (reason)", up to MAX_EXAMPLES
     */
    public record NormalizeResult(boolean dryRun, int checked, int renamed, int retagged, int notPlaces, int removed,
                                  int hidden, Map<String, Integer> addedTags, List<String> renameExamples,
                                  List<String> notPlaceExamples) {
    }
}
