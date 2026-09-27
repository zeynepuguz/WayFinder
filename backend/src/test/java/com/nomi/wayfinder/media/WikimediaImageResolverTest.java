package com.nomi.wayfinder.media;

import com.nomi.wayfinder.media.WikimediaImageResolver.Outcome;
import com.nomi.wayfinder.media.WikimediaImageResolver.PendingPlace;
import com.nomi.wayfinder.media.WikimediaParser.CommonsImage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Which photo a place gets, from already fetched API results (no network, no database)
class WikimediaImageResolverTest {

    private static final CommonsImage OSM_PHOTO = new CommonsImage("https://upload.wikimedia.org/osm.jpg", "A", "CC0",
            "https://commons.wikimedia.org/wiki/File:Osm.jpg");
    private static final CommonsImage WIKIDATA_PHOTO = new CommonsImage("https://upload.wikimedia.org/wd.jpg", null,
            "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:Wd.jpg");

    @Test
    void osmFileFirstThenWikidataImageAndOnlyPhotoFormats() {
        Map<String, String> p18 = Map.of("Q1", "Wd.jpg", "Q2", "Map.svg");

        assertThat(WikimediaImageResolver.candidates(new PendingPlace(1, "Q1", "Osm.jpg"), p18))
                .containsExactly("Osm.jpg", "Wd.jpg");
        assertThat(WikimediaImageResolver.candidates(new PendingPlace(2, "Q2", null), p18)).isEmpty();
        assertThat(WikimediaImageResolver.candidates(new PendingPlace(3, "Q1", "Wd.jpg"), p18))
                .containsExactly("Wd.jpg");
    }

    @Test
    void decidesFoundCheckedWithoutImageOrRetryLater() {
        List<PendingPlace> pending = List.of(
                new PendingPlace(1, "Q1", "Osm.jpg"),      // OSM file usable
                new PendingPlace(2, "Q1", "Rejected.jpg"), // OSM file not free -> Wikidata image
                new PendingPlace(3, "Q3", null),           // item has no P18 -> checked, no image
                new PendingPlace(4, "Q4", null),           // Wikidata request failed -> retry
                new PendingPlace(5, null, "Down.jpg"));    // Commons request failed -> retry
        Map<String, String> p18 = Map.of("Q1", "Wd.jpg");
        Map<String, CommonsImage> images = Map.of("Osm.jpg", OSM_PHOTO, "Wd.jpg", WIKIDATA_PHOTO);

        List<Outcome> outcomes = WikimediaImageResolver.decide(pending, p18, Set.of("Q4"), images, Set.of("Down.jpg"));

        assertThat(outcomes).containsExactly(
                new Outcome(1, OSM_PHOTO, false),
                new Outcome(2, WIKIDATA_PHOTO, false),
                new Outcome(3, null, false),
                new Outcome(4, null, true),
                new Outcome(5, null, true));
    }
}
