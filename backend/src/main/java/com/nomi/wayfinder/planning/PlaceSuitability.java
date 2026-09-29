package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.osm.OsmPlaceMapper;

import java.util.List;
import java.util.Set;

/**
 * Is a place a stop worth suggesting (route plans, recommendations)? Real places that are not a place to sit down
 * stay in Explore / search but are never suggested:
 * - a kıraathane / kahvehane: a (mostly men's) tea and card-game house
 * - a bread / simit bakery or "unlu mamülleri" shop without a sign of seating in its name: you buy and leave
 *   (a börek / pastry shop, "... Cafe", "Simit Sarayı" have tables and stay)
 * Decided from the name, category and tags only (OSM / Overture have no reliable seating data). Pure logic.
 */
public final class PlaceSuitability {

    private static final List<String> TEA_HOUSE = List.of("kiraathane", "kahvehane");
    // Take-away bakery words (folded, prefixes: "fırını", "ekmekçi", "simitçi", "unlu mamülleri")
    private static final List<String> TAKEAWAY = List.of("firin", "ekmek", "simit", "unlumamul", "pogaca", "gevrek",
            // Shops that sell sweets / dried fruit by the kilo
            "pestil", "kome", "lokum", "sekerleme", "kuruyemis", "sarkuteri");
    // A name that promises tables
    private static final List<String> SEATING = List.of("cafe", "kafe", "kahvalti", "restoran", "restaurant", "bistro",
            "kahve", "coffee", "lounge", "pastane", "patisserie", "cayevi", "caybahce", "simitsarayi", "simitdunyasi",
            "sofra", "lokanta", "salonu",
            // Börek shops almost always have tables
            "borek");
    private static final Set<PlaceCategory> LIGHT_FOOD = Set.of(PlaceCategory.CAFE, PlaceCategory.DESSERT,
            PlaceCategory.BREAKFAST);

    private PlaceSuitability() {
    }

    public static boolean isStop(Place place) {
        return place != null && isStop(place.getName(), place.getCategory(), place.getTags());
    }

    public static boolean isStop(String name, PlaceCategory category, List<String> tags) {
        String folded = OsmPlaceMapper.fold(name);
        if (TEA_HOUSE.stream().anyMatch(folded::contains)) {
            return false;
        }
        boolean seating = SEATING.stream().anyMatch(folded::contains);
        if (seating) {
            return true;
        }
        boolean takeawayName = TAKEAWAY.stream().anyMatch(folded::contains);
        boolean bakery = tags != null && tags.contains("bakery");
        if (LIGHT_FOOD.contains(category)) {
            return !(bakery || takeawayName);
        }
        // "Sıcak Simit", "Çıtır Simit", "Hasat Ekmek": a counter even when filed as a restaurant. "Pide Fırını" is one
        if (category == PlaceCategory.RESTAURANT) {
            return !(folded.contains("simit") || folded.contains("ekmek") || folded.contains("unlumamul"));
        }
        return true;
    }
}
