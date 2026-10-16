package com.nomi.wayfinder.admin;

import com.nomi.wayfinder.admin.PlaceReviewService.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Bu yer var": the owner confirms a place that was warned as "may have closed". Against the dev database; the place
 * gets its review fields back afterwards.
 */
@SpringBootTest
class PlaceConfirmTest {

    @Autowired
    private PlaceReviewService reviews;

    @Autowired
    private JdbcTemplate jdbc;

    private Long placeId;
    private Map<String, Object> kept;

    @AfterEach
    void restore() {
        if (placeId != null) {
            jdbc.update("""
                    UPDATE places SET review = ?, reviewed_at = ?, unconfirmed = ?, presence_confirmed_at = ?, hidden = ?
                    WHERE id = ?""", kept.get("review"), kept.get("reviewed_at"), kept.get("unconfirmed"),
                    kept.get("presence_confirmed_at"), kept.get("hidden"), placeId);
        }
    }

    @Test
    void confirmingAPlaceEndsTheWarningAndKeepsItPlannable() {
        placeId = jdbc.queryForObject("SELECT id FROM places WHERE NOT hidden AND review IS NULL LIMIT 1", Long.class);
        kept = jdbc.queryForMap("""
                SELECT review, reviewed_at, unconfirmed, presence_confirmed_at, hidden FROM places WHERE id = ?""", placeId);
        reviews.review(placeId, Action.SUSPECT);
        jdbc.update("UPDATE places SET unconfirmed = TRUE WHERE id = ?", placeId);

        reviews.review(placeId, Action.CONFIRM);

        Map<String, Object> now = jdbc.queryForMap("""
                SELECT review, unconfirmed, presence_confirmed_at, hidden FROM places WHERE id = ?""", placeId);
        // No "may have closed" of any kind, and back among the places routes use (review IS NULL)
        assertThat(now.get("review")).isNull();
        assertThat(now.get("unconfirmed")).isEqualTo(false);
        assertThat(now.get("presence_confirmed_at")).isNotNull();
        assertThat(now.get("hidden")).isEqualTo(false);
    }
}
