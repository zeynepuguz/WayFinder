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
    // Street food eaten standing or on the go: fine for lunch, not a dinner
    private static final List<String> SNACKS = List.of("cigkofte", "tantuni", "durum", "tost", "kumpir", "waffle",
            "bufe", "kokorec", "midye", "hotdog", "sandvic", "sandwich", "borek", "pogaca", "simit", "gozleme");
    // Places for a drink, not a meal
    private static final List<String> DRINKS_ONLY = List.of("cayevi", "caybahce", "cayocag", "kahve", "coffee",
            "kiraathane", "kahvehane", "nargile", "espresso", "roastery");
    private static final Set<PlaceCategory> LIGHT_FOOD = Set.of(PlaceCategory.CAFE, PlaceCategory.DESSERT,
            PlaceCategory.BREAKFAST);

    private PlaceSuitability() {
    }

    /**
     * Can the place be a lunch / dinner? Restaurants yes; a café only when it is not a tea house or coffee shop (a
     * "budget" day may eat at a börek café, never dinner at "Yıldız Çay Evi"); dessert shops never.
     */
    public static boolean servesMeals(Place place) {
        if (place == null) {
            return false;
        }
        return switch (place.getCategory()) {
            case RESTAURANT, BREAKFAST -> true;
            case CAFE -> {
                String folded = OsmPlaceMapper.fold(place.getName());
                List<String> tags = place.getTags() == null ? List.of() : place.getTags();
                yield !tags.contains("tea") && !tags.contains("coffee")
                        && DRINKS_ONLY.stream().noneMatch(folded::contains);
            }
            default -> false;
        };
    }

    // A dinner is a sit-down meal: never a çiğ köfte / tantuni / dürüm / tost / kumpir / waffle counter
    public static boolean isDinner(Place place) {
        // A restaurant: not a café or börek bakery, even on a budget day
        if (place == null || place.getCategory() != PlaceCategory.RESTAURANT) {
            return false;
        }
        String folded = OsmPlaceMapper.fold(place.getName());
        return SNACKS.stream().noneMatch(folded::contains);
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
