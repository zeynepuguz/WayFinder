package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.osm.OsmPlaceMapper;
import com.nomi.wayfinder.osm.PlaceNames;
import com.nomi.wayfinder.osm.PlaceRealismFilter;
import com.nomi.wayfinder.osm.PlaceTags;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns an Overture place into a Nomi food place, the same way osm/OsmPlaceMapper does for OSM: category from the
 * Overture taxonomy (food_and_drink only), interest tags from the taxonomy's cuisine and the name (osm/PlaceTags),
 * the name cleaned (osm/PlaceNames) and checked (osm/PlaceRealismFilter). Sights are not taken from Overture: OSM +
 * Wikidata know them better. Pure logic, unit tested.
 */
public final class OvertureMapper {

    static final int MAX_NAME = 255;
    static final int MAX_PHONE = 50;
    static final int MAX_WEBSITE = 500;

    // Taxonomy entries that are food and drink but not a place to go to for a meal / coffee
    private static final Set<String> SKIPPED = Set.of("alcoholic_beverage_venue", "cafeteria", "internet_cafe",
            "food_truck_stand", "delicatessen", "candy_store", "food_court_vendor", "catering_service");
    private static final Set<String> DESSERT = Set.of("dessert_shop", "ice_cream_shop", "frozen_yoghurt_shop",
            "chocolatier", "patisserie", "donut_shop", "cupcake_shop");
    private static final Set<String> QUICK = Set.of("fast_food_restaurant", "sandwich_shop", "bagel_shop", "diner",
            "food_court", "burger_restaurant", "pizza_restaurant", "hot_dog_restaurant");
    // Taxonomy leaf -> OSM-style cuisine value, so osm/PlaceTags derives the same interest tags as for OSM
    private static final Map<String, String> CUISINES = Map.of(
            "turkish_restaurant", "turkish",
            "doner_kebab_restaurant", "doner",
            "kebab_restaurant", "kebab",
            "seafood_restaurant", "seafood",
            "fish_restaurant", "fish",
            "soup_restaurant", "soup",
            "tea_room", "tea");
    // Same words as OsmPlaceMapper: a pastane is a dessert place, a börek / simit bakery a café
    private static final List<String> PASTRY_WORDS = List.of("pastane", "pastahane", "patisserie", "patiseri",
            "pasta", "tatli", "baklava", "kunefe", "muhallebi", "cikolata", "chocolate", "cake", "dessert");
    private static final Pattern STUCK_SHIFT = Pattern.compile("\\p{Lu}{2,}\\p{Ll}{2,}");
    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    // A meal in the name (folded prefixes): "döner", "kebap", "köfteci", "pide", "lahmacun", "iskender", "mantı"
    private static final List<String> MEAL_WORDS = List.of("doner", "kebap", "kebab", "kofte", "pide", "lahmacun",
            "iskender", "manti", "lokanta", "tantuni", "kokorec", "durum", "cagkebap");
    private static final List<String> CAFE_WORDS = List.of("cafe", "kafe", "kahve", "coffee", "cayevi", "caybahce");
    private static final Set<String> CLOSED =Set.of("permanently_closed", "temporarily_closed");

    private OvertureMapper() {
    }

    /**
     * One row of Overture's places GeoParquet.
     *
     * @param hierarchy       taxonomy.hierarchy, e.g. [food_and_drink, restaurant, middle_eastern_restaurant,
     *                        turkish_restaurant]
     * @param operatingStatus open / temporarily_closed / permanently_closed; usually null
     */
    public record OvertureRow(String id, String name, List<String> hierarchy, double confidence,
                              String operatingStatus, String phone, String website, double latitude,
                              double longitude) {
    }

    public record OverturePlace(String overtureId, String name, PlaceCategory category, List<String> tags,
                                boolean indoor, double confidence, String phone, String website, double latitude,
                                double longitude) {
    }

    record Kind(PlaceCategory category, List<String> tags) {
    }

    // null = not a food place we show (closed, no name, not food and drink, a canteen, a bar, ...)
    public static OverturePlace map(OvertureRow row) {
        if (row == null || row.id() == null || (row.operatingStatus() != null
                && CLOSED.contains(row.operatingStatus().toLowerCase(Locale.ROOT)))) {
            return null;
        }
        if (!OsmPlaceMapper.plausibleCoordinates(row.latitude(), row.longitude())) {
            return null;
        }
        String name = fixMixedCase(PlaceNames.clean(row.name()));
        if (name == null || name.length() > MAX_NAME) {
            return null;
        }
        String folded = OsmPlaceMapper.fold(name);
        List<String> hierarchy = row.hierarchy() == null ? List.of() : row.hierarchy();
        Kind kind = classify(hierarchy, folded);
        if (kind == null || PlaceRealismFilter.rejectName(name, kind.category()) != null) {
            return null;
        }
        List<String> cuisines = hierarchy.stream().map(CUISINES::get).filter(Objects::nonNull).toList();
        List<String> tags = PlaceTags.merge(kind.tags(),
                PlaceTags.derive(name, kind.category(), cuisines, Map.of(), kind.tags()));
        // A tea garden is outside; everything else here is a shop / restaurant room
        boolean indoor = !(folded.contains("caybahce") || folded.contains("bahcesi") && tags.contains("tea"));
        return new OverturePlace(row.id(), name, kind.category(), tags, indoor, row.confidence(),
                trim(row.phone(), MAX_PHONE), website(row.website()), row.latitude(), row.longitude());
    }

    static Kind classify(List<String> hierarchy, String folded) {
        Kind kind = classifyByTaxonomy(hierarchy, folded);
        // Meta pages are often filed wrongly ("Gözde Cağ Döner" as a coffee shop): a meal in the name wins over a
        // café / dessert taxonomy, unless the name also says café / coffee
        if (kind != null && kind.category() != PlaceCategory.RESTAURANT
                && MEAL_WORDS.stream().anyMatch(folded::contains)
                && CAFE_WORDS.stream().noneMatch(folded::contains)) {
            return new Kind(PlaceCategory.RESTAURANT, List.of("quick"));
        }
        return kind;
    }

    private static Kind classifyByTaxonomy(List<String> hierarchy, String folded) {
        if (hierarchy.isEmpty() || !"food_and_drink".equals(hierarchy.getFirst())) {
            return null;
        }
        Set<String> h = new HashSet<>(hierarchy);
        if (h.stream().anyMatch(SKIPPED::contains)) {
            return null;
        }
        String group = hierarchy.size() > 1 ? hierarchy.get(1) : null;
        if (h.contains("breakfast_and_brunch_restaurant")) {
            return new Kind(PlaceCategory.BREAKFAST, List.of());
        }
        if (h.stream().anyMatch(DESSERT::contains)) {
            return new Kind(PlaceCategory.DESSERT, List.of());
        }
        if (h.contains("bakery")) {
            if (PASTRY_WORDS.stream().anyMatch(folded::contains)) {
                return new Kind(PlaceCategory.DESSERT, List.of());
            }
            // A plain bread oven ("Yıldız Ekmek Fırını") is a shop, not a place to go to
            return folded.contains("ekmekfirin") ? null : new Kind(PlaceCategory.CAFE, List.of("bakery"));
        }
        if (h.contains("coffee_shop")) {
            return new Kind(PlaceCategory.CAFE, List.of("coffee"));
        }
        boolean teaName = folded.contains("caybahce") || folded.contains("cayevi");
        if (h.contains("tea_room") || h.contains("cafe") && teaName) {
            return new Kind(PlaceCategory.CAFE, List.of("tea"));
        }
        if (h.contains("cafe") || "non_alcoholic_beverage_venue".equals(group)) {
            return new Kind(PlaceCategory.CAFE, List.of());
        }
        // Döner, köfte, pide places: real meals, served quickly (as OSM amenity=fast_food)
        if (h.stream().anyMatch(QUICK::contains) || "casual_eatery".equals(group) && hierarchy.size() == 2) {
            return new Kind(PlaceCategory.RESTAURANT, List.of("quick"));
        }
        if ("restaurant".equals(group)) {
            return new Kind(PlaceCategory.RESTAURANT, List.of());
        }
        return null;
    }

    /**
     * Words typed with a stuck shift key on Meta pages ("HalİSbey", "ÇAyirova") become "Halisbey", "Çayirova".
     * Deliberate brand casing keeps: one capital inside ("McDonald's"), all caps ("KFC"), a short tail ("DJs").
     */
    static String fixMixedCase(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder fixed = new StringBuilder();
        for (String part : name.split("(?<= )|(?= )")) {
            if (STUCK_SHIFT.matcher(part).find()) {
                String lower = part.toLowerCase(TURKISH);
                fixed.append(lower.substring(0, 1).toUpperCase(TURKISH)).append(lower.substring(1));
            } else {
                fixed.append(part);
            }
        }
        return fixed.toString();
    }

    // Only plain http(s) links; shortened to the column
    static String website(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") || trimmed.length() > MAX_WEBSITE) {
            return null;
        }
        return trimmed;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() > max ? null : trimmed;
    }
}
