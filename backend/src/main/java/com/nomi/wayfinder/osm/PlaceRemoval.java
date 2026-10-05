package com.nomi.wayfinder.osm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Removes place rows that should no longer be shown: a row is deleted when nothing references it, else hidden.
 * Kept when a route, a saved place or a user photo points at it (user_photos would be deleted with the place:
 * ON DELETE CASCADE). Used by the OSM import (by osm_id, OSM rows only), PlaceDataNormalizer and
 * PlaceRealismCleanup (by id).
 */
@Component
public class PlaceRemoval {

    private static final String OSM_ROWS = "p.source = 'OSM' AND p.osm_id = ANY (?)";
    private static final String ROWS_BY_ID = "p.id = ANY (?)";

    private static final String UNREFERENCED = """
              AND NOT EXISTS (SELECT 1 FROM route_stops rs WHERE rs.place_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM saved_places sp WHERE sp.place_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM user_photos up WHERE up.place_id = p.id)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PlaceRemoval(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    // Deletes the OSM rows of these elements ("way/123") that nothing references; referenced ones stay as they are
    public int deleteUnreferencedOsm(List<String> osmIds) {
        if (osmIds.isEmpty()) {
            return 0;
        }
        Integer deleted = transactions.execute(status -> delete(OSM_ROWS, "text", osmIds.toArray(String[]::new)));
        return deleted == null ? 0 : deleted;
    }

    // Deletes the OSM rows of these elements that nothing references and hides the others
    public RemovedRows removeOrHideOsm(List<String> osmIds) {
        return osmIds.isEmpty() ? RemovedRows.NONE : removeOrHide(OSM_ROWS, "text", osmIds.toArray(String[]::new));
    }

    // Deletes the rows (any source) that nothing references and hides the others
    public RemovedRows removeOrHideByIds(List<Long> ids) {
        return ids.isEmpty() ? RemovedRows.NONE : removeOrHide(ROWS_BY_ID, "bigint", ids.toArray(Long[]::new));
    }

    private RemovedRows removeOrHide(String rows, String sqlType, Object[] ids) {
        return transactions.execute(status -> {
            int deleted = delete(rows, sqlType, ids);
            int hidden = jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "UPDATE places p SET hidden = TRUE, updated_at = now() WHERE " + rows + " AND NOT p.hidden");
                ps.setArray(1, con.createArrayOf(sqlType, ids));
                return ps;
            });
            return new RemovedRows(deleted, hidden);
        });
    }

    private int delete(String rows, String sqlType, Object[] ids) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM places p WHERE " + rows + "\n" + UNREFERENCED);
            ps.setArray(1, con.createArrayOf(sqlType, ids));
            return ps;
        });
    }

    /**
     * @param removed rows deleted (nothing referenced them)
     * @param hidden  rows hidden (a route stop, saved place or user photo uses them)
     */
    public record RemovedRows(int removed, int hidden) {
        static final RemovedRows NONE = new RemovedRows(0, 0);
    }
}
