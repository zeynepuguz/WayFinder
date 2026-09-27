package com.nomi.wayfinder.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// Interests are matched against place tags, so they are stored the same way: lowercase, trimmed, unique
public final class Interests {

    // Tags are English keys in the database; users see Turkish labels
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
            Map.entry("sports", "spor")
    );

    private Interests() {
    }

    public static List<String> normalize(Collection<String> interests) {
        if (interests == null) {
            return List.of();
        }
        return interests.stream()
                .filter(Objects::nonNull)
                .map(i -> i.trim().toLowerCase(Locale.ROOT))
                .filter(i -> !i.isEmpty())
                .distinct()
                .toList();
    }

    public static String label(String tag) {
        return LABELS.getOrDefault(tag, tag);
    }
}
