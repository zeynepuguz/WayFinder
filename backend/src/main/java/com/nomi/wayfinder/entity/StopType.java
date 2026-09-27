package com.nomi.wayfinder.entity;

import java.time.LocalTime;
import java.util.Set;

// What a route stop is for. Each type knows which place categories/tags can fill it,
// its usual time of day and how long it usually takes.
public enum StopType {
    BREAKFAST(LocalTime.of(9, 30), 60, 0.20, Set.of(PlaceCategory.BREAKFAST), "breakfast", "Kahvaltı"),
    SIGHTSEEING(LocalTime.of(11, 0), 60, 0.05,
            Set.of(PlaceCategory.ATTRACTION, PlaceCategory.MUSEUM, PlaceCategory.PARK, PlaceCategory.CULTURE),
            null, "Gezi"),
    LUNCH(LocalTime.of(13, 0), 60, 0.25, Set.of(PlaceCategory.RESTAURANT), null, "Öğle yemeği"),
    COFFEE(LocalTime.of(15, 0), 45, 0.10, Set.of(PlaceCategory.CAFE), "coffee", "Kahve"),
    DESSERT(LocalTime.of(18, 30), 30, 0.10, Set.of(PlaceCategory.DESSERT), "dessert", "Tatlı"),
    DINNER(LocalTime.of(20, 0), 75, 0.30, Set.of(PlaceCategory.RESTAURANT), null, "Akşam yemeği");

    private final LocalTime defaultTime;
    private final int defaultMinutes;
    private final double budgetWeight;
    private final Set<PlaceCategory> categories;
    // A place from another category can still fill this stop if it has this tag
    private final String matchingTag;
    private final String label;

    StopType(LocalTime defaultTime, int defaultMinutes, double budgetWeight,
             Set<PlaceCategory> categories, String matchingTag, String label) {
        this.defaultTime = defaultTime;
        this.defaultMinutes = defaultMinutes;
        this.budgetWeight = budgetWeight;
        this.categories = categories;
        this.matchingTag = matchingTag;
        this.label = label;
    }

    public LocalTime getDefaultTime() {
        return defaultTime;
    }

    public int getDefaultMinutes() {
        return defaultMinutes;
    }

    public double getBudgetWeight() {
        return budgetWeight;
    }

    public Set<PlaceCategory> getCategories() {
        return categories;
    }

    public String getMatchingTag() {
        return matchingTag;
    }

    public String getLabel() {
        return label;
    }

    public boolean isMeal() {
        return this == BREAKFAST || this == LUNCH || this == DINNER;
    }
}
