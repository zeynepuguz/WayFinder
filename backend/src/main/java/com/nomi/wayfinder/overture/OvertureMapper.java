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
    // Address words as whole words: "Mah", "Mah.", "Mahallesi", "Sok", "Sokak", "Cad", "Caddesi", "Cd", "Sk", "No:"
    private static final Pattern ADDRESS_NAME = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:mah\\.?|mahallesi|sok\\.?|sokak|sokağı|cad\\.?|caddesi|cd\\.?|sk\\.?|no\\s*:)(?!\\p{L})");
    // Turkish grocery chains (folded first word, or first two words joined: "A 101", "Şok Market", "Tarım Kredi")
    private static final Set<String> MARKET_CHAINS = Set.of("bim", "a101", "sokmarket", "hakmar", "migros",
            "mmmigros", "carrefoursa", "carrefour", "tarimkredi", "macrocenter", "bizimtoptan", "happycenter",
            "cagrimarket", "altunbilekler", "hakmarexpress");
    // Chain names that are also ordinary words / names ("Şok", "Onur Pastanesi", "Yunus Emre Camii"): a chain only
    // when Overture files the page as a shop
    private static final Set<String> AMBIGUOUS_CHAINS = Set.of("sok", "file", "metro", "onur", "kiler", "yunus",
            "gimsa", "ozdilek");
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s\\-]+");
    // The province names (folded): a page named "<street> <district> <province>" is an address, not a place
    private static final Set<String> PROVINCES = Set.of("adana", "adiyaman", "afyonkarahisar", "agri", "amasya",
            "ankara", "antalya", "artvin", "aydin", "balikesir", "bilecik", "bingol", "bitlis", "bolu",
            "burdur", "bursa", "canakkale", "cankiri", "corum", "denizli", "diyarbakir", "edirne", "elazig",
            "erzincan", "erzurum", "eskisehir", "gaziantep", "giresun", "gumushane", "hakkari", "hatay",
            "isparta", "mersin", "istanbul", "izmir", "kars", "kastamonu", "kayseri", "kirklareli",
            "kirsehir", "kocaeli", "konya", "kutahya", "malatya", "manisa", "kahramanmaras", "mardin",
            "mugla", "mus", "nevsehir", "nigde", "ordu", "rize", "sakarya", "samsun", "siirt", "sinop",
            "sivas", "tekirdag", "tokat", "trabzon", "tunceli", "sanliurfa", "usak", "van", "yozgat",
            "zonguldak", "aksaray", "bayburt", "karaman", "kirikkale", "batman", "sirnak", "bartin",
            "ardahan", "igdir", "yalova", "karabuk", "kilis", "osmaniye", "duzce");
    private static final Set<PlaceCategory> LIGHT_FOOD = Set.of(PlaceCategory.CAFE, PlaceCategory.DESSERT,
            PlaceCategory.BREAKFAST);
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
        // A Meta page named after its address ("Hocaalizade Mah Osmangazi Bursa", "Yeni Bağdat Gebze Kocaeli") is
        // not a place name (a chain branch "Migros Gebze Kocaeli" is)
        if (ADDRESS_NAME.matcher(name).find() || endsWithProvince(name)) {
            return null;
        }
        String folded = OsmPlaceMapper.fold(name);
        List<String> hierarchy = row.hierarchy() == null ? List.of() : row.hierarchy();
        // A market chain is a market however its page is filed ("Mimar Sinan Hakmar" as a shopping mall)
        Kind kind = isMarketChain(name, hierarchy) ? new Kind(PlaceCategory.MARKET, List.of())
                : classify(hierarchy, folded, name);
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

    static Kind classify(List<String> hierarchy, String folded, String name) {
        // Places to pray at and to buy groceries (Explore only)
        if (hierarchy.contains("place_of_worship")) {
            // Meta pages file villages, tombs, cemeteries, Quran courses and cafés as places of worship, with a
            // religion picked at random ("Şair Ahmet Paşa Türbesi" as christian): only names that say "Camii",
            // "Kilisesi", "Cemevi", ... count, and the kind comes from the name alone
            if (!PlaceTags.placeToPray(name, true)) {
                return null;
            }
            String worship = PlaceTags.worshipKind(name, null, null);
            return new Kind(PlaceCategory.WORSHIP,
                    worship == null ? List.of("religious") : List.of("religious", worship));
        }
        if (hierarchy.contains("grocery_store") || hierarchy.contains("supermarket")
                || hierarchy.contains("convenience_store") || hierarchy.contains("discount_store")) {
            return new Kind(PlaceCategory.MARKET, List.of());
        }
        // Malls and superstores only as market chains (above); otherwise they are not a food / worship place
        if (hierarchy.contains("shopping_mall") || hierarchy.contains("superstore")) {
            return null;
        }
        Kind kind = classifyByTaxonomy(hierarchy, folded);
        // Meta pages are often filed wrongly ("Gözde Cağ Döner" as a coffee shop): a meal in the name wins over a
        // café / dessert taxonomy, unless the name also says café / coffee
        if (kind != null && LIGHT_FOOD.contains(kind.category())
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

    static boolean endsWithProvince(String name) {
        String[] words = WORD_SPLIT.split(name.trim());
        return words.length >= 2 && PROVINCES.contains(OsmPlaceMapper.fold(words[words.length - 1]))
                && !isMarketChain(name, List.of("shopping"));
    }

    // The first word (or the first two joined) is a grocery chain; ambiguous names only for pages filed as shops
    static boolean isMarketChain(String name, List<String> hierarchy) {
        String[] words = WORD_SPLIT.split(name.trim());
        if (words.length == 0) {
            return false;
        }
        String first = OsmPlaceMapper.fold(words[0]);
        // Any word, or two words together ("Mimar Sinan Hakmar", "BİM Gebze", "A 101 Çayırova", "Şok Market")
        for (int i = 0; i < words.length; i++) {
            String word = OsmPlaceMapper.fold(words[i]);
            String pair = i + 1 < words.length ? word + OsmPlaceMapper.fold(words[i + 1]) : word;
            if (MARKET_CHAINS.contains(word) || MARKET_CHAINS.contains(pair)) {
                return true;
            }
        }
        boolean shop = !hierarchy.isEmpty() && "shopping".equals(hierarchy.getFirst());
        return shop && AMBIGUOUS_CHAINS.contains(first);
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
