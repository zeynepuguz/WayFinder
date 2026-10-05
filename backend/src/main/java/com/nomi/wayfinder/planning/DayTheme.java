package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.i18n.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made kinds of day. A theme only sets defaults (interests, stops, walking, rain) that the user did not choose
 * and adds an honest note: prices and child-friendliness are not known for every place, so nothing is promised.
 */
public enum DayTheme {
    // Indoor places all day; replans keep treating the day as wet
    RAINY(List.of("museum", "art"), null, null),
    // Free sights (parks, viewpoints) and cheap meals (planning/PlaceScorer "budget"); never a guessed price
    LOW_BUDGET(List.of("budget", "nature", "view"), null, null),
    // Fewer, closer stops with a break for dessert; parks (one sight interest, so DayTemplate keeps two sights)
    FAMILY(List.of("nature"),
            List.of(StopType.SIGHTSEEING, StopType.LUNCH, StopType.SIGHTSEEING, StopType.DESSERT), WalkingTolerance.LOW);

    private final List<String> interests;
    private final List<StopType> stops;
    private final WalkingTolerance walking;

    DayTheme(List<String> interests, List<StopType> stops, WalkingTolerance walking) {
        this.interests = interests;
        this.stops = stops;
        this.walking = walking;
    }

    // The user's interests plus the theme's
    public List<String> interests(List<String> chosen) {
        List<String> all = new ArrayList<>(chosen);
        interests.stream().filter(i -> !all.contains(i)).forEach(all::add);
        return all;
    }

    // The theme's stops when the user did not list any (null = the full day template)
    public List<StopType> stops(List<StopType> chosen) {
        return chosen != null && !chosen.isEmpty() ? chosen : stops;
    }

    public WalkingTolerance walking(WalkingTolerance chosen) {
        return chosen != null || walking == null ? chosen : walking;
    }

    public boolean assumeWet() {
        return this == RAINY;
    }

    public String note() {
        return switch (this) {
            case RAINY -> Texts.t("Yağmurlu gün planı: açık alanlar yerine müze, kafe gibi kapalı mekanlar seçildi; "
                            + "rotayı değiştirirken de kapalı mekanlar tercih edilir.",
                    "Rainy day plan: indoor places such as museums and cafés instead of outdoor ones; changes to the "
                            + "route keep preferring indoor places.");
            case LOW_BUDGET -> Texts.t("Düşük bütçe planı: ücretsiz gezilecek yerler ve uygun fiyatlı mekanlar öne "
                            + "alındı. Fiyat her mekan için bilinmiyor; tutarlar yaklaşık, gitmeden kontrol etmeni öneririm.",
                    "Low budget plan: free sights and inexpensive places first. Prices are not known for every place; "
                            + "amounts are rough, so check before you go.");
            case FAMILY -> Texts.t("Aile günü planı: daha az yürüyüş ve arada bir tatlı molası. Mekanların çocuklara "
                            + "uygunluğu her zaman bilinmiyor; gitmeden kontrol etmeni öneririm.",
                    "Family day plan: less walking and a dessert break. Whether a place suits children is not always "
                            + "known, so check before you go.");
        };
    }
}
