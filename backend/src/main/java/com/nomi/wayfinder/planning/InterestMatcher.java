package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;

import java.util.*;

/**
 * Which places match a user interest, from real data only: place tags (derived at import from OSM tourism / historic /
 * leisure / cuisine tags and name words, see osm/PlaceTags), the category, and near_sea (within ~300 m of the OSM
 * sea coastline). Pure logic, unit tested; the planner, the scorer and the route notes all use it.
 */
public final class InterestMatcher {

    // Interest -> place tags that count for it
    static final Map<String, Set<String>> TAGS = Map.ofEntries(
            Map.entry("sea", Set.of("sea")),
            Map.entry("nature", Set.of("nature")),
            Map.entry("budget", Set.of("budget", "quick", "bakery", "tea")),
            Map.entry("history", Set.of("history")),
            Map.entry("museum", Set.of("museum")),
            Map.entry("art", Set.of("art", "street-art")),
            Map.entry("architecture", Set.of("architecture")),
            Map.entry("local", Set.of("local", "traditional")),
            Map.entry("seafood", Set.of("seafood")),
            Map.entry("view", Set.of("view")),
            Map.entry("books", Set.of("books")),
            Map.entry("street-art", Set.of("street-art"))
    );

    private InterestMatcher() {
    }

    // An interest we can match against our data
    public static boolean known(String interest) {
        return TAGS.containsKey(interest);
    }

    public static boolean matches(Place place, String interest) {
        if (place == null || interest == null) {
            return false;
        }
        Set<String> tags = TAGS.get(interest);
        if (tags == null) {
            // Other place tags ("coffee", "dessert", ...) still match themselves
            return place.hasTag(interest);
        }
        if (tags.stream().anyMatch(place::hasTag)) {
            return true;
        }
        PlaceCategory category = place.getCategory();
        return switch (interest) {
            // By the sea: tagged (beach, aquarium, lighthouse, "sahil") or within ~300 m of the coastline
            case "sea" -> place.isNearSea();
            // Parks, gardens, nature reserves, beaches, viewpoints
            case "nature" -> category == PlaceCategory.PARK || place.hasTag("view") && !place.isIndoor();
            case "museum" -> category == PlaceCategory.MUSEUM;
            // A city / archaeology museum is history too (and dry when it rains)
            case "history" -> category == PlaceCategory.MUSEUM;
            case "art" -> category == PlaceCategory.CULTURE;
            // A park or sight on the shore has a sea view
            case "view" -> place.isNearSea() && !place.isIndoor()
                    && (category == PlaceCategory.PARK || category == PlaceCategory.ATTRACTION);
            default -> false;
        };
    }

    // The interests (in the user's order) this place matches
    public static List<String> matching(Place place, Collection<String> interests) {
        if (interests == null) {
            return List.of();
        }
        return interests.stream().filter(i -> matches(place, i)).toList();
    }

    // Tags to look for in the database for these interests ("" = none): places.tags && these
    public static String tagsCsv(Collection<String> interests) {
        Set<String> tags = new TreeSet<>();
        if (interests != null) {
            for (String interest : interests) {
                tags.addAll(TAGS.getOrDefault(interest, Set.of()));
            }
        }
        return String.join(",", tags);
    }

    // near_sea counts for these interests
    public static boolean wantsSea(Collection<String> interests) {
        return interests != null && (interests.contains("sea") || interests.contains("view"));
    }
}
