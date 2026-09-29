package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.entity.PlaceCategory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    static final int MAX_COMMONS_FILE = 255;
    static final int MAX_CUISINE = 255;

    private static final Pattern WIKIDATA_ID = Pattern.compile("Q\\d{1,15}");
    private static final Pattern COMMONS_TAG = Pattern.compile("(?i)(?:file|image):(.+)");
    private static final Pattern COMMONS_PAGE_URL =
            Pattern.compile("(?i)https?://commons\\.(?:m\\.)?wikimedia\\.org/wiki/(?:file|image):([^?#]+)");
    // Only the Commons repository: /wikipedia/en/... files are local to one wiki and may be fair use
    private static final Pattern COMMONS_UPLOAD_URL = Pattern.compile(
            "(?i)https?://(?:upload|thumb)\\.wikimedia\\.org/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?#]+)(?:/[^/?#]*)?(?:\\?.*)?");
    // Characters MediaWiki titles cannot contain ("|" would also break the API's title lists)
    private static final Pattern INVALID_TITLE_CHARS = Pattern.compile("[|#<>\\[\\]{}\\x00-\\x1f]");

    private OsmPlaceMapper() {
    }

    // Turkey (35.8-42.1 N, 25.6-44.8 E) padded: coordinates outside are mapping errors (0/0, swapped lat/lon)
    static final double MIN_LATITUDE = 35.0;
    static final double MAX_LATITUDE = 42.8;
    static final double MIN_LONGITUDE = 25.0;
    static final double MAX_LONGITUDE = 45.5;

    /**
     * @return null when the element is not a place we can show (no name, no or impossible coordinates, unknown kind)
     */
    public static OsmPlace map(OverpassResponse.Element element) {
        // name:tr when OSM has it, else name; obviously broken casing repaired (PlaceNames)
        String name = PlaceNames.choose(element.tag("name"), element.tag("name:tr"));
        Double latitude = element.latitude();
        Double longitude = element.longitude();
        if (name == null || latitude == null || longitude == null || element.type() == null
                || !plausibleCoordinates(latitude, longitude)) {
            return null;
        }

        // OSM values are lowercase ASCII keys like "coffee_shop;breakfast"
        String cuisine = element.tag("cuisine") == null ? "" : element.tag("cuisine").toLowerCase(Locale.ROOT);
        List<String> cuisines = Arrays.stream(cuisine.split(";")).map(String::trim).toList();
        String foldedName = fold(name);

        Kind kind = classify(element, foldedName, cuisines);
        if (kind == null) {
            return null;
        }
        PlaceCategory category = kind.category();
        if ((category == PlaceCategory.CAFE || category == PlaceCategory.RESTAURANT)
                && (cuisines.contains("breakfast") || foldedName.contains("kahvalti"))) {
            category = PlaceCategory.BREAKFAST;
        }

        String tourism = element.tag("tourism");
        List<String> tags = new ArrayList<>(kind.tags());
        if (cuisines.contains("coffee_shop")) {
            tags.add("coffee");
        }
        if (cuisines.contains("seafood") || cuisines.contains("fish")) {
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
        if ("artwork".equals(tourism)) {
            tags.add("art");
        }
        if (category == PlaceCategory.MUSEUM) {
            tags.add("museum");
        }
        if (category == PlaceCategory.PARK) {
            tags.add("nature");
            tags.add("walk");
        }
        if ("arts_centre".equals(element.tag("amenity"))) {
            tags.add("art");
        }
        if (category == PlaceCategory.DESSERT) {
            tags.add("dessert");
        }

        // Interest tags from cuisine, OSM tags and name words (local, seafood, budget, view, books, ...)
        List<String> allTags = PlaceTags.merge(tags.stream().distinct().toList(),
                PlaceTags.derive(name, category, cuisines, element.tags(), tags));

        List<OpeningHoursDto> hours = OsmOpeningHoursParser.parse(element.tag("opening_hours"));

        String osmId = element.type() + "/" + element.id();
        return new OsmPlace(
                osmId,
                "https://www.openstreetmap.org/" + osmId,
                truncate(name, MAX_NAME),
                latitude,
                longitude,
                category,
                kind.indoor() != null ? kind.indoor() : indoor(category),
                allTags,
                truncate(address(element), MAX_ADDRESS),
                truncate(neighborhood(element), MAX_NEIGHBORHOOD),
                hours,
                wikidata(element.tag("wikidata")),
                commonsFile(element.tag("wikimedia_commons"), element.tag("image")),
                truncate(PlaceNames.english(element.tag("name:en"), name), MAX_NAME),
                truncate(trimToNull(cuisine), MAX_CUISINE)
        );
    }

    public static boolean plausibleCoordinates(double latitude, double longitude) {
        return latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE
                && longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE;
    }

    // historic=* values that are sights by themselves; any other historic=* (memorial plaques, milestones,
    // boundary stones, "yes") only counts when it has a Wikidata item, i.e. it is notable
    static final Set<String> HISTORIC_SIGHTS = Set.of("castle", "fort", "monument", "ruins", "archaeological_site",
            "city_gate", "tower", "citywalls", "aqueduct", "palace", "mosque", "church", "monastery", "caravanserai",
            "bath");
    // Named gardens of houses / roofs / community plots are not public places
    private static final Set<String> PRIVATE_GARDEN_TYPES = Set.of("residential", "private", "roof_garden", "community");
    // shop=bakery: a pastry shop (sit-down pastane) vs a börek / simit / poğaça bakery vs a bread oven
    private static final List<String> PASTRY_WORDS = List.of("pastane", "pastahane", "patisserie", "patiseri",
            "pasta", "tatli", "baklava", "kunefe", "muhallebi", "cikolata", "chocolate", "cake", "dessert");
    private static final List<String> SAVOURY_BAKERY_WORDS = List.of("borek", "simit", "pogaca", "gevrek", "acma",
            "katmer", "bakery", "cafe", "kafe");

    /**
     * The category (and extra tags) of an element; null when it is nothing we show. Keys are checked in order
     * amenity, shop, tourism, leisure, historic, natural, man_made: a cafe that is also tagged tourism=attraction
     * is still a cafe; a place of worship without Wikidata / heritage / historic tags falls through to the next key.
     */
    static Kind classify(OverpassResponse.Element element, String foldedName, List<String> cuisines) {
        String amenity = element.tag("amenity");
        boolean notable = wikidata(element.tag("wikidata")) != null;
        if ("cafe".equals(amenity)) {
            // Tea gardens (çay bahçesi) are cafes where people mostly drink tea
            boolean tea = cuisines.contains("tea") || foldedName.contains("caybahce") || foldedName.contains("cayevi");
            return new Kind(PlaceCategory.CAFE, tea ? List.of("tea") : List.of(), null);
        }
        if ("restaurant".equals(amenity) || "food_court".equals(amenity)) {
            return new Kind(PlaceCategory.RESTAURANT, List.of(), null);
        }
        if ("fast_food".equals(amenity)) {
            // Döner, köfte, pide, lahmacun places: real meals, served quickly
            return cuisines.contains("ice_cream")
                    ? new Kind(PlaceCategory.DESSERT, List.of(), null)
                    : new Kind(PlaceCategory.RESTAURANT, List.of("quick"), null);
        }
        if ("ice_cream".equals(amenity)) {
            return new Kind(PlaceCategory.DESSERT, List.of(), null);
        }
        if ("theatre".equals(amenity) || "arts_centre".equals(amenity)) {
            return new Kind(PlaceCategory.CULTURE, List.of(), null);
        }
        if ("place_of_worship".equals(amenity)
                && (notable || trimToNull(element.tag("historic")) != null || trimToNull(element.tag("heritage")) != null)) {
            // Only famous / historic mosques, churches and synagogues, not every neighbourhood mescit
            return new Kind(PlaceCategory.ATTRACTION, List.of("history", "religious"), true);
        }
        if ("marketplace".equals(amenity)) {
            return new Kind(PlaceCategory.ATTRACTION, List.of("shopping", "local"), false);
        }

        String shop = element.tag("shop");
        if ("pastry".equals(shop) || "confectionery".equals(shop)) {
            return new Kind(PlaceCategory.DESSERT, List.of(), null);
        }
        if ("coffee".equals(shop)) {
            return new Kind(PlaceCategory.CAFE, List.of("coffee"), null);
        }
        if ("bakery".equals(shop)) {
            if (cuisines.contains("pastry") || cuisines.contains("cake") || containsAny(foldedName, PASTRY_WORDS)) {
                return new Kind(PlaceCategory.DESSERT, List.of(), null);
            }
            if (containsAny(foldedName, SAVOURY_BAKERY_WORDS)) {
                return new Kind(PlaceCategory.CAFE, List.of("bakery"), null);
            }
            // A plain bread oven ("Yıldız Ekmek Fırını") is a shop, not a place to go to
            return null;
        }

        String tourism = element.tag("tourism");
        if ("museum".equals(tourism)) {
            return new Kind(PlaceCategory.MUSEUM, List.of(), null);
        }
        if ("gallery".equals(tourism)) {
            return new Kind(PlaceCategory.CULTURE, List.of("art"), null);
        }
        if ("attraction".equals(tourism) || "viewpoint".equals(tourism) || "zoo".equals(tourism)
                || "theme_park".equals(tourism)) {
            return new Kind(PlaceCategory.ATTRACTION, List.of(), null);
        }
        if ("aquarium".equals(tourism)) {
            return new Kind(PlaceCategory.ATTRACTION, List.of("sea"), true);
        }
        // Named murals / graffiti (street art); statues and other artworks only count through historic=* / Wikidata
        String artworkType = element.tag("artwork_type");
        if ("artwork".equals(tourism) && artworkType != null
                && (artworkType.contains("mural") || artworkType.contains("graffiti"))) {
            return new Kind(PlaceCategory.ATTRACTION, List.of("art", "street-art"), false);
        }

        String leisure = element.tag("leisure");
        if ("park".equals(leisure) || "nature_reserve".equals(leisure)) {
            return new Kind(PlaceCategory.PARK, List.of(), null);
        }
        if ("garden".equals(leisure)) {
            String gardenType = element.tag("garden:type");
            return gardenType != null && PRIVATE_GARDEN_TYPES.contains(gardenType)
                    ? null : new Kind(PlaceCategory.PARK, List.of(), null);
        }

        String historic = trimToNull(element.tag("historic"));
        if (historic != null && (HISTORIC_SIGHTS.contains(historic) || notable)) {
            return new Kind(PlaceCategory.ATTRACTION, List.of("history"), null);
        }

        if ("beach".equals(element.tag("natural"))) {
            return new Kind(PlaceCategory.PARK, List.of("sea"), false);
        }
        if ("lighthouse".equals(element.tag("man_made")) && notable) {
            return new Kind(PlaceCategory.ATTRACTION, List.of("sea", "view"), false);
        }
        return null;
    }

    private static boolean containsAny(String folded, List<String> words) {
        return words.stream().anyMatch(folded::contains);
    }

    /**
     * @param indoor null = the category's default (see indoor(PlaceCategory))
     */
    record Kind(PlaceCategory category, List<String> tags, Boolean indoor) {
    }

    // "Q12506"; anything else (lists like "Q1;Q2", typos) -> null
    static String wikidata(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null && WIKIDATA_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * The Commons file title (without "File:") from wikimedia_commons ("File:X.jpg"), else from image when
     * it is a Commons file page or an upload.wikimedia.org/wikipedia/commons URL. Other image URLs are
     * ignored: their license is unknown. Categories ("Category:...") are not a single photo and are ignored too.
     */
    static String commonsFile(String wikimediaCommons, String image) {
        String commons = trimToNull(wikimediaCommons);
        if (commons != null) {
            Matcher m = COMMONS_TAG.matcher(commons);
            if (m.matches()) {
                String title = cleanFileTitle(m.group(1), false);
                if (title != null) {
                    return title;
                }
            }
        }

        String url = trimToNull(image);
        if (url == null) {
            return null;
        }
        for (Pattern pattern : List.of(COMMONS_PAGE_URL, COMMONS_UPLOAD_URL)) {
            Matcher m = pattern.matcher(url);
            if (m.matches()) {
                return cleanFileTitle(m.group(1), true);
            }
        }
        return null;
    }

    // Underscores are spaces in wiki titles; null when the title cannot be a valid file name
    private static String cleanFileTitle(String raw, boolean urlEncoded) {
        String title = raw;
        if (urlEncoded) {
            try {
                // A literal "+" in a URL path is a plus sign, not a space
                title = URLDecoder.decode(title.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        title = title.replace('_', ' ').trim().replaceAll("\\s+", " ");
        if (title.isEmpty() || title.length() > MAX_COMMONS_FILE || INVALID_TITLE_CHARS.matcher(title).find()
                || title.lastIndexOf('.') <= 0) {
            return null;
        }
        // Wiki titles start with a capital letter (Character.toUpperCase ignores the JVM's Turkish locale)
        return Character.toUpperCase(title.charAt(0)) + title.substring(1);
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
            List<OpeningHoursDto> openingHours,
            // Wikidata item id ("Q123") or null
            String wikidata,
            // Commons file title without "File:" or null
            String commonsFile,
            // OSM name:en when it differs from name; null = none
            String nameEn,
            // OSM cuisine as tagged (lowercase), null = none
            String cuisine
    ) {

        public boolean hasMedia() {
            return wikidata != null || commonsFile != null;
        }
    }
}
