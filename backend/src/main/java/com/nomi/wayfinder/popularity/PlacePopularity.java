package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.osm.OsmPlaceMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The popularity score of a place (pure logic). Two open signals of real-world interest:
 * - sitelinks: in how many Wikipedia language editions the place has an article (Wikidata). Famous places have
 *   dozens (Ayasofya ~150), a neighbourhood mosque 0-2.
 * - pageviews: how often its Turkish and English Wikipedia articles were read by people (not bots) in the last
 *   PopularityProperties.pageviewDays (60) days.
 *
 * <pre>popularity = 2 * ln(1 + sitelinks) + ln(1 + pageviews)</pre>
 *
 * Both are log-scaled because interest is extremely skewed (a handful of places get most views); the sitelinks
 * term is doubled so a place known worldwide (many languages) outranks one that is only read a lot locally, and
 * both terms end up in the same range (0-12). Examples: 150 sitelinks + 300,000 views = 10.0 + 12.6 = 22.6;
 * 2 sitelinks + 300 views = 2.2 + 5.7 = 7.9; a Wikidata item without any article = 0.
 */
public final class PlacePopularity {

    // Wikimedia projects with sitelinks that are not a Wikipedia language edition
    private static final Set<String> NOT_WIKIPEDIA = Set.of("commonswiki", "specieswiki", "metawiki", "mediawikiwiki",
            "wikidatawiki", "sourceswiki", "outreachwiki", "incubatorwiki", "wikimaniawiki", "foundationwiki",
            "wikifunctionswiki", "testwiki", "test2wiki", "testwikidatawiki", "testcommonswiki", "wikimaniateamwiki");
    // Language Wikipedias: "trwiki", "enwiki", "zh_min_nanwiki" (wikivoyage, wikiquote ... end differently)
    private static final Pattern WIKIPEDIA_SITE = Pattern.compile("[a-z][a-z_]*wiki");

    private PlacePopularity() {
    }

    public static double score(int sitelinks, long pageviews) {
        return 2 * Math.log1p(Math.max(0, sitelinks)) + Math.log1p(Math.max(0, pageviews));
    }

    static final double MIN_NAME_SIMILARITY = 0.25;

    /**
     * Wikidata classes (direct "instance of", P31) of things that happened, not places: an OSM element tagged with
     * such an item ("Gezi Parkı olaylarının gerçekleştiği yer" -> Q13410316, the Gezi Park protests) marks an event.
     */
    static final Set<String> EVENT_CLASSES = Set.of(
            "Q1190554",  // occurrence
            "Q1656682",  // event
            "Q13418847", // historical event
            "Q175331",   // demonstration
            "Q273120",   // protest
            "Q124734",   // riot
            "Q7283",     // terrorism
            "Q2223653",  // terrorist attack
            "Q178561",   // battle
            "Q188055",   // siege
            "Q198",      // war
            "Q645883",   // military operation
            "Q831663",   // military campaign
            "Q3199915",  // massacre
            "Q750215",   // mass murder
            "Q3839081",  // disaster
            "Q8065",     // natural disaster
            "Q7944",     // earthquake
            "Q168983",   // conflagration
            "Q10931",    // revolution
            "Q45382"     // coup d'état
    );
    // A person: the tomb / statue / park is named after them, the item is not about the place
    static final String HUMAN = "Q5";

    static boolean isEvent(Set<String> instanceOf) {
        return instanceOf != null && instanceOf.stream().anyMatch(EVENT_CLASSES::contains);
    }

    static boolean isPerson(Set<String> instanceOf) {
        return instanceOf != null && instanceOf.contains(HUMAN);
    }

    /**
     * Is the Wikidata item (through its Wikipedia titles) about this place? OSM's wikidata tag is sometimes put on
     * a place although it names something else: a tomb tagged with the sultan buried there, a park tagged with an
     * event that happened in it. Then the item's fame is not the place's. At least one title must be similar to
     * the place name: Jaccard similarity of the folded names' letter trigrams >= MIN_NAME_SIMILARITY
     * ("Sultanahmet Camii" ~ "Sultan Ahmed Camii" 0.65, "Sultan Ahmet Türbesi" ~ "I. Ahmed" 0.1).
     * Items without a Turkish or English article cannot be compared and count as matching.
     */
    public static boolean aboutThePlace(String placeName, String trTitle, String enTitle) {
        if (trTitle == null && enTitle == null) {
            return true;
        }
        for (String title : new String[]{trTitle, enTitle}) {
            if (title != null && similarity(placeName, title) >= MIN_NAME_SIMILARITY) {
                return true;
            }
        }
        return false;
    }

    static double similarity(String a, String b) {
        Set<String> x = trigrams(OsmPlaceMapper.fold(a));
        Set<String> y = trigrams(OsmPlaceMapper.fold(b));
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(x);
        common.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) common.size() / union.size();
    }

    private static Set<String> trigrams(String folded) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= folded.length(); i++) {
            grams.add(folded.substring(i, i + 3));
        }
        if (grams.isEmpty() && !folded.isEmpty()) {
            grams.add(folded);
        }
        return grams;
    }

    // Wikipedia language editions among an item's sitelinks
    static int wikipediaCount(Map<String, ?> sitelinks) {
        if (sitelinks == null) {
            return 0;
        }
        return (int) sitelinks.keySet().stream()
                .filter(site -> WIKIPEDIA_SITE.matcher(site).matches() && !NOT_WIKIPEDIA.contains(site))
                .count();
    }
}
