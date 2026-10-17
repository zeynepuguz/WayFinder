package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.i18n.TurkishFold;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What kind of eating / drinking place it is, finer than its category: "FISH" (balık restoranı), "KEBAB", "PASTRY",
 * "COFFEE", ... shown under the name in lists and on the place page instead of the plain category. Decided from the
 * name (most places have nothing else), the OSM / Overture cuisine and the interest tags; null = only the category is
 * known. The first matching kind wins, so the more telling one comes first (a "Çiğ Köfte" is not a köfteci). Pure logic.
 */
public final class PlaceKinds {

    private static final Set<PlaceCategory> FOOD = Set.of(PlaceCategory.RESTAURANT, PlaceCategory.CAFE,
            PlaceCategory.DESSERT, PlaceCategory.BREAKFAST);

    private record Kind(String code, Pattern name, Set<String> cuisines, String tag) {
    }

    private static Kind kind(String code, String nameRegex, String... cuisines) {
        return new Kind(code, nameRegex == null ? null : Pattern.compile(nameRegex), Set.of(cuisines), null);
    }

    private static Kind tagged(String code, String nameRegex, String tag) {
        return new Kind(code, Pattern.compile(nameRegex), Set.of(), tag);
    }

    // On the lowercase ASCII name, words kept apart ("cig kofte", "cay bahcesi")
    private static final List<Kind> KINDS = List.of(
            kind("KIRAATHANE", "kiraathane|kahvehane"),
            kind("HOOKAH", "nargile|hookah|shisha"),
            kind("CIGKOFTE", "cig ?kofte", "çiğköfte", "cigkofte"),
            // "Balıkesir" is a city, not a fish
            kind("FISH", "\\bbalik(?!esir)|\\bseafood|\\bfish\\b", "fish", "seafood", "fish_and_chips"),
            kind("KEBAB", "\\bkebap|\\bkebab|ocakbasi|\\bcag\\b", "kebab"),
            kind("DONER", "\\bdoner", "doner"),
            kind("PIDE", "\\bpide|lahmacun"),
            kind("TANTUNI", "tantuni"),
            kind("KOFTE", "kofte"),
            kind("LOKANTA", "lokanta|ev yemek|esnaf"),
            // "Romantik" is not a mantı place
            kind("MANTI", "\\bmanti"),
            kind("SOUP", "corba|iskembe|kelle paca", "soup"),
            kind("CHICKEN", "\\btavuk|chicken|\\bpilic", "chicken"),
            kind("STEAK", "steak|\\bet (lokanta|restoran)|\\bkasap", "steak_house", "barbecue"),
            kind("PIZZA", "pizza", "pizza"),
            kind("BURGER", "burger", "burger"),
            kind("ASIAN", "sushi|\\bnoodle|\\bwok\\b|\\bcin lokanta|chinese", "chinese", "japanese", "sushi", "asian"),
            kind("ITALIAN", "\\bpasta (bar|house)|trattoria|\\bitalian", "italian"),
            kind("BAKLAVA", "baklava|kunefe"),
            kind("MUHALLEBI", "muhallebi|sutlac"),
            kind("ICE_CREAM", "dondurma|ice ?cream|gelato", "ice_cream"),
            kind("CHOCOLATE", "cikolata|chocolate"),
            kind("PASTRY", "pastane|pastahane|patisserie|patiseri"),
            kind("BOREK", "borek"),
            kind("GOZLEME", "gozleme"),
            kind("SIMIT", "simit"),
            tagged("BAKERY", "\\bfirin|unlu mamul|\\bekmek", "bakery"),
            kind("TEA_HOUSE", "\\bcay (bahce|evi|ocag)|cayevi|caybahce", "tea"),
            tagged("BOOK_CAFE", "\\bkitap", "books"),
            kind("COFFEE", "coffee|\\bkahve|espresso|roaster", "coffee_shop", "coffee"));

    private PlaceKinds() {
    }

    public static String of(Place place) {
        return place == null ? null : of(place.getName(), place.getCategory(), place.getCuisine(), place.getTags());
    }

    public static String of(String name, PlaceCategory category, String cuisine, List<String> tags) {
        if (!FOOD.contains(category)) {
            return null;
        }
        // An internet / game café or billiard hall first: that is what it really is
        String play = PlaceSuitability.cafeKind(name, category);
        if (play != null) {
            return play;
        }
        String lower = TurkishFold.ascii(name);
        // "turkish;kebab" -> turkish, kebab
        List<String> cuisines = cuisine == null ? List.of() : List.of(cuisine.toLowerCase().split("[;,]\\s*"));
        for (Kind kind : KINDS) {
            boolean byName = kind.name() != null && kind.name().matcher(lower).find();
            boolean byCuisine = cuisines.stream().anyMatch(kind.cuisines()::contains);
            boolean byTag = kind.tag() != null && tags != null && tags.contains(kind.tag());
            if (byName || byCuisine || byTag) {
                return kind.code();
            }
        }
        return null;
    }
}
