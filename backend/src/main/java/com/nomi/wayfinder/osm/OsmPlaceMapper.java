package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.entity.PlaceCategory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One Overpass element -> one place row, using only what OSM says. OSM has no prices, ratings or
 * descriptions, so those stay NULL; tags are only added for facts OSM states (cuisine, historic=*, ...)
 * and always come from the interest vocabulary in service/Interests.
 * Pure logic (no database / network), so it is unit tested with a sample Overpass answer.
 */
public final class OsmPlaceMapper {

    static final int MAX_NAME = 255;
    static final int MAX_ADDRESS = 255;
    static final int MAX_NEIGHBORHOOD = 100;

    private OsmPlaceMapper() {
    }

    /**
     * @return null when the element is not a place we can show (no name, no coordinates, unknown kind)
     */
    public static OsmPlace map(OverpassResponse.Element element) {
        String name = trimToNull(element.tag("name"));
        Double latitude = element.latitude();
        Double longitude = element.longitude();
        if (name == null || latitude == null || longitude == null || element.type() == null) {
            return null;
        }

        String amenity = element.tag("amenity");
        String shop = element.tag("shop");
        String tourism = element.tag("tourism");
        String leisure = element.tag("leisure");
        // OSM values are lowercase ASCII keys like "coffee_shop;breakfast"
        String cuisine = element.tag("cuisine") == null ? "" : element.tag("cuisine").toLowerCase(Locale.ROOT);
        List<String> cuisines = Arrays.asList(cuisine.split(";"));

        PlaceCategory category = category(amenity, shop, tourism, leisure);
        if (category == null) {
            return null;
        }
        String foldedName = fold(name);
        if ((category == PlaceCategory.CAFE || category == PlaceCategory.RESTAURANT)
                && (cuisine.contains("breakfast") || foldedName.contains("kahvalti"))) {
            category = PlaceCategory.BREAKFAST;
        }

        List<String> tags = new ArrayList<>();
        if (cuisines.stream().anyMatch(c -> c.trim().equals("coffee_shop"))) {
            tags.add("coffee");
        }
        if (cuisines.stream().anyMatch(c -> c.trim().equals("seafood") || c.trim().equals("fish"))) {
            tags.add("seafood");
        }
        if (category == PlaceCategory.BREAKFAST) {
            tags.add("breakfast");
        }
        if ("viewpoint".equals(tourism)) {
            tags.add("view");
        }
        if (trimToNull(element.tag("historic")) != null) {
            tags.add("history");
        }
        if (category == PlaceCategory.MUSEUM) {
            tags.add("museum");
        }
        if (category == PlaceCategory.PARK) {
            tags.add("nature");
            tags.add("walk");
        }
        if ("arts_centre".equals(amenity)) {
            tags.add("art");
        }
        if (category == PlaceCategory.DESSERT) {
            tags.add("dessert");
        }

        List<OpeningHoursDto> hours = OsmOpeningHoursParser.parse(element.tag("opening_hours"));

        String osmId = element.type() + "/" + element.id();
        return new OsmPlace(
                osmId,
                "https://www.openstreetmap.org/" + osmId,
                truncate(name, MAX_NAME),
                latitude,
                longitude,
                category,
                indoor(category),
                tags.stream().distinct().toList(),
                truncate(address(element), MAX_ADDRESS),
                truncate(neighborhood(element), MAX_NEIGHBORHOOD),
                hours
        );
    }

    // amenity first: a cafe that is also tagged tourism=attraction is still a cafe
    static PlaceCategory category(String amenity, String shop, String tourism, String leisure) {
        if (amenity != null) {
            switch (amenity) {
                case "cafe":
                    return PlaceCategory.CAFE;
                case "restaurant":
                    return PlaceCategory.RESTAURANT;
                case "ice_cream":
                    return PlaceCategory.DESSERT;
                case "theatre", "arts_centre":
                    return PlaceCategory.CULTURE;
                default:
                    break;
            }
        }
        if ("pastry".equals(shop) || "confectionery".equals(shop)) {
            return PlaceCategory.DESSERT;
        }
        if ("museum".equals(tourism)) {
            return PlaceCategory.MUSEUM;
        }
        if ("attraction".equals(tourism) || "viewpoint".equals(tourism)) {
            return PlaceCategory.ATTRACTION;
        }
        if ("park".equals(leisure)) {
            return PlaceCategory.PARK;
        }
        return null;
    }

    // Parks, viewpoints and attractions are treated as outdoors (worse in rain / heat)
    static boolean indoor(PlaceCategory category) {
        return switch (category) {
            case PARK, ATTRACTION -> false;
            default -> true;
        };
    }

    private static String address(OverpassResponse.Element element) {
        String street = trimToNull(element.tag("addr:street"));
        if (street == null) {
            return null;
        }
        String number = trimToNull(element.tag("addr:housenumber"));
        return number == null ? street : street + " " + number;
    }

    private static String neighborhood(OverpassResponse.Element element) {
        for (String key : List.of("addr:suburb", "addr:neighbourhood", "addr:district")) {
            String value = trimToNull(element.tag(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Case and diacritic insensitive form for comparing names: "Çiya Sofrası" -> "ciyasofrasi".
     * Turkish letters are mapped by hand first, so the JVM's Turkish locale cannot turn "I" into "ı".
     * Spaces and punctuation are dropped, so "Moda Çay Bahçesi" matches "Moda Çay-Bahçesi".
     */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String mapped = text
                .replace('İ', 'i').replace('I', 'i').replace('ı', 'i')
                .replace('Ş', 's').replace('ş', 's')
                .replace('Ğ', 'g').replace('ğ', 'g')
                .replace('Ü', 'u').replace('ü', 'u')
                .replace('Ö', 'o').replace('ö', 'o')
                .replace('Ç', 'c').replace('ç', 'c');
        String withoutMarks = Normalizer.normalize(mapped, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutMarks.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record OsmPlace(
            String osmId,
            String sourceUrl,
            String name,
            double latitude,
            double longitude,
            PlaceCategory category,
            boolean indoor,
            List<String> tags,
            String address,
            String neighborhood,
            List<OpeningHoursDto> openingHours
    ) {
    }
}
