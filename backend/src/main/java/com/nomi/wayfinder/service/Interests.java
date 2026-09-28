package com.nomi.wayfinder.service;

import com.nomi.wayfinder.i18n.Texts;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// Interests are matched against place tags, so they are stored the same way: lowercase, trimmed, unique
public final class Interests {

    // Tags are English keys in the database; users see labels in their language
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("history", "tarih"),
            Map.entry("museum", "müze"),
            Map.entry("sea", "deniz"),
            Map.entry("nature", "doğa"),
            Map.entry("art", "sanat"),
            Map.entry("street-art", "sokak sanatı"),
            Map.entry("view", "manzara"),
            Map.entry("local", "yerel"),
            Map.entry("books", "kitap"),
            Map.entry("architecture", "mimari"),
            Map.entry("seafood", "deniz ürünleri"),
            Map.entry("budget", "uygun fiyat"),
            Map.entry("traditional", "geleneksel"),
            Map.entry("coffee", "kahve"),
            Map.entry("dessert", "tatlı"),
            Map.entry("breakfast", "kahvaltı"),
            Map.entry("walk", "yürüyüş"),
            Map.entry("shopping", "alışveriş"),
            Map.entry("music", "müzik"),
            Map.entry("sports", "spor"),
            Map.entry("quick", "hızlı yemek"),
            Map.entry("bakery", "fırın"),
            Map.entry("tea", "çay"),
            Map.entry("religious", "ibadethane")
    );

    private static final Map<String, String> LABELS_EN = Map.ofEntries(
            Map.entry("history", "history"),
            Map.entry("museum", "museums"),
            Map.entry("sea", "sea"),
            Map.entry("nature", "nature"),
            Map.entry("art", "art"),
            Map.entry("street-art", "street art"),
            Map.entry("view", "views"),
            Map.entry("local", "local"),
            Map.entry("books", "books"),
            Map.entry("architecture", "architecture"),
            Map.entry("seafood", "seafood"),
            Map.entry("budget", "budget-friendly"),
            Map.entry("traditional", "traditional"),
            Map.entry("coffee", "coffee"),
            Map.entry("dessert", "dessert"),
            Map.entry("breakfast", "breakfast"),
            Map.entry("walk", "walking"),
            Map.entry("shopping", "shopping"),
            Map.entry("music", "music"),
            Map.entry("sports", "sports"),
            Map.entry("quick", "quick bites"),
            Map.entry("bakery", "bakery"),
            Map.entry("tea", "tea"),
            Map.entry("religious", "place of worship")
    );

    private Interests() {
    }

    // Turkish / English labels ("deniz", "Uygun fiyat", "seaside") -> the tag key, so every client may send either
    private static final Map<String, String> ALIASES = aliases();

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new java.util.HashMap<>();
        LABELS.forEach((key, label) -> aliases.put(label.toLowerCase(Texts.TURKISH), key));
        LABELS_EN.forEach((key, label) -> aliases.putIfAbsent(label.toLowerCase(Locale.ROOT), key));
        aliases.put("seaside", "sea");
        aliases.put("budget", "budget");
        aliases.put("ucuz", "budget");
        aliases.put("uygun", "budget");
        aliases.put("muze", "museum");
        aliases.put("doga", "nature");
        aliases.put("sokak sanati", "street-art");
        aliases.put("street art", "street-art");
        aliases.put("deniz urunleri", "seafood");
        aliases.put("museums", "museum");
        aliases.put("views", "view");
        return Map.copyOf(aliases);
    }

    public static List<String> normalize(Collection<String> interests) {
        if (interests == null) {
            return List.of();
        }
        return interests.stream()
                .filter(Objects::nonNull)
                .map(Interests::key)
                .filter(i -> !i.isEmpty())
                .distinct()
                .toList();
    }

    // "Deniz" -> "sea"; keys and unknown words stay as they are (lowercase, trimmed)
    static String key(String interest) {
        String trimmed = interest.trim().replaceAll("\\s+", " ");
        String turkish = trimmed.toLowerCase(Texts.TURKISH);
        String root = trimmed.toLowerCase(Locale.ROOT);
        if (LABELS.containsKey(root)) {
            return root;
        }
        return ALIASES.getOrDefault(turkish, ALIASES.getOrDefault(root, root));
    }

    public static String label(String tag) {
        return (Texts.english() ? LABELS_EN : LABELS).getOrDefault(tag, tag);
    }
}
