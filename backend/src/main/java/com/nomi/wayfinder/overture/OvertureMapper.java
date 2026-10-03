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
     * @param brand           the chain ("A101"), null for most places
     * @param brandWikidata   the chain's Wikidata item ("Q6034496"): a brand Overture is sure about
     */
    public record OvertureRow(String id, String name, List<String> hierarchy, double confidence,
                              String operatingStatus, String phone, String website, double latitude,
                              double longitude, String brand, String brandWikidata) {

        public OvertureRow(String id, String name, List<String> hierarchy, double confidence, String operatingStatus,
                           String phone, String website, double latitude, double longitude) {
            this(id, name, hierarchy, confidence, operatingStatus, phone, website, latitude, longitude, null, null);
        }
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
        // A chain's branch page named after its street or quarter ("Yeni Bağdat Gebze Kocaeli", brand A101 with
        // its Wikidata item) is that chain's shop: "A101 Yeni Bağdat Gebze"
        String branded = withBrand(name, row.brand(), row.brandWikidata());
        if (branded != null) {
            name = branded;
        } else if (ADDRESS_NAME.matcher(name).find() || endsWithProvince(name)) {
            // A Meta page named after its address ("Hocaalizade Mah Osmangazi Bursa") is not a place name (a chain
            // branch "Migros Gebze Kocaeli" is)
            return null;
        }
        String folded = OsmPlaceMapper.fold(name);
        List<String> hierarchy = row.hierarchy() == null ? List.of() : row.hierarchy();
        // A market chain is a market however its page is filed ("Mimar Sinan Hakmar" as a shopping mall)
        Kind kind = isMarketChain(name, hierarchy) ? new Kind(PlaceCategory.MARKET, List.of())
                : classify(hierarchy, folded, name);
        // Sights from Meta pages are often something else ("Ünal Sahil Sitesi" as a beach): only certain ones
        if (kind != null && SIGHTS.contains(kind.category()) && row.confidence() < SIGHT_MIN_CONFIDENCE) {
            return null;
        }
        if (kind == null || PlaceRealismFilter.rejectName(name, kind.category()) != null) {
            return null;
        }
        List<String> cuisines = hierarchy.stream().map(CUISINES::get).filter(Objects::nonNull).toList();
        // Börek shops / savoury bakeries are breakfast places; breakfast places that are cafés are listed under both
        List<String> kindTags = new ArrayList<>(kind.tags());
        if (h(hierarchy, "breakfast_and_brunch_restaurant") && (h(hierarchy, "cafe") || h(hierarchy, "coffee_shop"))) {
            kindTags.add("cafe");
        }
        PlaceCategory category = PlaceTags.breakfastAware(kind.category(), folded, kindTags);
        // Explore > Market > BİM / A101 / ŞOK / Migros / Hakmar / Diğer
        if (category == PlaceCategory.MARKET) {
            kindTags.add(PlaceTags.marketKind(name));
        }
        kind = new Kind(category, List.copyOf(kindTags));
        List<String> tags = PlaceTags.merge(kind.tags(),
                PlaceTags.derive(name, kind.category(), cuisines, Map.of(), kind.tags()));
        // Parks, beaches and historic sites are outside, museums / culture venues inside; a tea garden is outside;
        // everything else here is a shop / restaurant room
        boolean indoor = switch (kind.category()) {
            case PARK, ATTRACTION -> false;
            case MUSEUM, CULTURE -> true;
            default -> !(folded.contains("caybahce") || folded.contains("bahcesi") && tags.contains("tea"));
        };
        return new OverturePlace(row.id(), name, kind.category(), tags, indoor, row.confidence(),
                trim(row.phone(), MAX_PHONE), website(row.website()), row.latitude(), row.longitude());
    }

    static final double SIGHT_MIN_CONFIDENCE = 0.7;
    private static final Set<PlaceCategory> SIGHTS = Set.of(PlaceCategory.MUSEUM, PlaceCategory.CULTURE,
            PlaceCategory.ATTRACTION, PlaceCategory.PARK);
    // Folded word prefixes of historic buildings / sites: a "historic_site" page must name one ("Tarihi Taş Köprü",
    // "Kocaeli Saat Kulesi"; not "Samanlı Tüneli" or a village)
    private static final List<String> HISTORIC_WORDS = List.of("kale", "kopru", "cesme", "hamam", "kervansaray",
            "kosk", "konak", "antik", "harabe", "kule", "anit", "medrese", "kulliye", "sarnic", "bedesten", "arasta",
            "saray", "tarihi", "surlar", "orenyeri", "sukemer", "kemeri", "turbe", "hisar", "castle", "bridge",
            "fountain", "monument", "ancient", "ruins", "tower", "palace");
    private static final Set<String> HISTORIC_EXACT = Set.of("han", "hani", "sur", "oren");
    private static final List<String> PARK_WORDS = List.of("park", "bahce", "tabiat", "orman", "mesire", "garden");
    private static final List<String> BEACH_WORDS = List.of("plaj", "beach");
    // Folded word prefixes of what Meta pages file as sights but is not one ("Elit Park Evleri", "Nesli-Han
    // Apartmanı", "Park ve Bahçeler Müdürlüğü", "Sel Auto Galeri", "Göktaş Mutfak Banyo Kapı")
    private static final List<String> NOT_A_SIGHT_STEMS = List.of("apartman", "evleri", "sitesi", "siteler", "konut",
            "rezidans", "residence", "tower", "plaza", "mudurlug", "mudurluk", "baskanlig", "ticaret", "sanayi",
            "insaat", "emlak", "gayrimenkul", "mobilya", "mutfak", "banyo", "otomotiv", "dekorasyon", "matbaa",
            "reklam", "organizasyon", "dugun", "cemev", "kursu", "akademi", "okulu", "lisesi", "dernek", "konaklar",
            "saraylar", "adliye", "yikama", "halisaha", "saha", "tesis", "otopark", "temsilcilig", "subesi", "dernegi",
            "kafe", "cafe", "kahve");
    // A culture venue says so: "Kültür Merkezi", "Sanat Galerisi", "Bölge Tiyatrosu", "Bilim Merkezi"
    private static final List<String> CULTURE_WORDS = List.of("kultur", "sanat", "tiyatro", "theat", "galeri",
            "gallery", "konser", "sahne", "opera", "konservatuvar", "bilim", "akm", "performing", "sergi", "art");
    private static final Set<String> NOT_A_SIGHT_EXACT = Set.of("oto", "auto", "ltd", "sti", "kapi", "as");

    /**
     * Museums, culture venues, historic sites and parks Overture knows (OSM has most; matching keeps them once).
     * Meta pages are filed loosely, so historic sites, parks and beaches must say what they are in their name.
     */
    static Kind sight(List<String> hierarchy, String name) {
        List<String> words = PlaceRealismFilter.words(name == null ? "" : name);
        // Housing estates, offices, directorates and shops filed as parks / galleries / historic sites
        boolean museumName = words.stream().anyMatch(w -> w.startsWith("muze") || w.startsWith("museum"));
        if (!museumName && words.stream().anyMatch(w -> NOT_A_SIGHT_EXACT.contains(w)
                || NOT_A_SIGHT_STEMS.stream().anyMatch(w::startsWith))) {
            return null;
        }
        boolean historicName = words.stream().anyMatch(w -> HISTORIC_EXACT.contains(w)
                || HISTORIC_WORDS.stream().anyMatch(w::startsWith));
        if (hierarchy.contains("museum") || hierarchy.stream().anyMatch(c -> c.endsWith("_museum"))) {
            // "Umut Gözleme ve Mantı Evi" is filed as a museum too: a museum says so, a mansion is a sight
            return museumName ? new Kind(PlaceCategory.MUSEUM, List.of("museum"))
                    : historicName ? new Kind(PlaceCategory.ATTRACTION, List.of("history")) : null;
        }
        if (hierarchy.contains("art_gallery") || hierarchy.contains("theatre_venue")
                || hierarchy.contains("cultural_center")) {
            boolean cultureName = words.stream().anyMatch(w -> CULTURE_WORDS.stream().anyMatch(w::startsWith));
            return cultureName ? new Kind(PlaceCategory.CULTURE, List.of("art")) : null;
        }
        if (hierarchy.contains("historic_site") || hierarchy.contains("castle") || hierarchy.contains("monument")
                || hierarchy.contains("memorial_site")) {
            return historicName ? new Kind(PlaceCategory.ATTRACTION, List.of("history")) : null;
        }
        if (hierarchy.contains("beach")) {
            boolean named = words.stream().anyMatch(w -> BEACH_WORDS.stream().anyMatch(w::startsWith));
            return named ? new Kind(PlaceCategory.PARK, List.of("sea", "nature")) : null;
        }
        if (hierarchy.contains("park") || hierarchy.contains("national_park") || hierarchy.contains("botanical_garden")
                || hierarchy.contains("nature_reserve")) {
            // A tea garden ("Çınaraltı Çay Bahçesi") is a café
            boolean teaGarden = words.contains("cay") && words.stream().anyMatch(w -> w.startsWith("bahce"));
            boolean named = words.stream().anyMatch(w -> PARK_WORDS.stream().anyMatch(w::startsWith));
            return named && !teaGarden ? new Kind(PlaceCategory.PARK, List.of("nature")) : null;
        }
        return null;
    }

    private static boolean h(List<String> hierarchy, String value) {
        return hierarchy.contains(value);
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
        Kind sight = sight(hierarchy, name);
        if (sight != null) {
            return sight;
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

    /**
     * The chain's name in front of a branch page's address name ("A101 Yeni Bağdat Gebze"), without the province;
     * null when the brand is unsure (no Wikidata item) or the name already says it ("BİM Gebze").
     */
    static String withBrand(String name, String brand, String brandWikidata) {
        if (brand == null || brand.isBlank() || brandWikidata == null || brandWikidata.isBlank()) {
            return null;
        }
        String cleanBrand = brand.trim();
        if (OsmPlaceMapper.fold(name).contains(OsmPlaceMapper.fold(cleanBrand))) {
            return null;
        }
        List<String> words = new ArrayList<>(List.of(WORD_SPLIT.split(name.trim())));
        while (!words.isEmpty() && PROVINCES.contains(OsmPlaceMapper.fold(words.getLast()))) {
            words.removeLast();
        }
        String branch = String.join(" ", words).trim();
        String result = branch.isEmpty() ? cleanBrand : cleanBrand + " " + branch;
        return result.length() > MAX_NAME ? cleanBrand : result;
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
