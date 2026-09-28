package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.i18n.Texts;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * "Popüler rotalar": themed days the planner fills with real places of a city / district (GET /routes/popular).
 * Only the kinds of stops are fixed here; places, times, walks and prices always come from the planner.
 */
public enum PopularTheme {

    HISTORY("Tarihi yerler", "Historic sights",
            "Müzeler ve tarihi yerler arasında, arada bir kahve molası olan bir gün.",
            "A day of museums and historic sights, with a coffee break in between.",
            List.of("history", "museum", "architecture")) {
        @Override
        public List<PlanningSlot> slots() {
            return List.of(
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, HISTORIC, null),
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, HISTORIC, null),
                    PlanningSlot.next(StopType.COFFEE, null),
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, HISTORIC, null));
        }
    },
    FOOD("Lezzet turu", "Food tour",
            "Kahvaltıdan akşam yemeğine: öğle yemeği ve tatlıyla dolu bir gün.",
            "From breakfast to dinner: a day of lunch, dessert and good food.",
            List.of("local", "traditional")) {
        @Override
        public List<PlanningSlot> slots() {
            return List.of(
                    PlanningSlot.at(StopType.BREAKFAST, LocalTime.of(10, 0)),
                    PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0)),
                    PlanningSlot.at(StopType.DESSERT, LocalTime.of(16, 30)),
                    PlanningSlot.at(StopType.DINNER, LocalTime.of(19, 30)));
        }
    },
    COFFEE_DESSERT("Kahve ve tatlı", "Coffee and dessert",
            "Kahveyle başlayan, kısa bir gezi ve tatlıyla devam eden sakin bir gün.",
            "A relaxed day: coffee, a short sightseeing stop, dessert and one more coffee.",
            List.of("coffee", "dessert")) {
        @Override
        public List<PlanningSlot> slots() {
            return List.of(
                    PlanningSlot.next(StopType.COFFEE, null),
                    PlanningSlot.next(StopType.SIGHTSEEING, null),
                    PlanningSlot.next(StopType.DESSERT, null),
                    PlanningSlot.next(StopType.COFFEE, null));
        }
    },
    PARKS_VIEWS("Parklar ve manzara", "Parks and views",
            "Parklar ve manzara noktaları arasında, kahve molalı açık havada bir gün.",
            "An outdoor day of parks and viewpoints, with a coffee break.",
            List.of("nature", "view", "walk")) {
        @Override
        public List<PlanningSlot> slots() {
            return List.of(
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, GREEN, VIEW_TAG),
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, GREEN, VIEW_TAG),
                    PlanningSlot.next(StopType.COFFEE, null),
                    PlanningSlot.restricted(StopType.SIGHTSEEING, null, GREEN, VIEW_TAG));
        }
    };

    // A theme is shown only when the planner found at least this many real stops for it
    public static final int MIN_STOPS = 3;

    // Museums and attractions (monuments, mosques, historic sites). No culture venues: theatres and
    // arts centres are not "historic sights", and no parks
    static final Set<PlaceCategory> HISTORIC = Set.of(PlaceCategory.MUSEUM, PlaceCategory.ATTRACTION);
    // Parks, plus places tagged "view" (OSM viewpoints are attractions with that tag)
    static final Set<PlaceCategory> GREEN = Set.of(PlaceCategory.PARK);
    static final String VIEW_TAG = "view";

    private final String title;
    private final String titleEn;
    private final String description;
    private final String descriptionEn;
    private final List<String> interests;

    PopularTheme(String title, String titleEn, String description, String descriptionEn, List<String> interests) {
        this.title = title;
        this.titleEn = titleEn;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.interests = interests;
    }

    // The stops the planner fills, in order
    public abstract List<PlanningSlot> slots();

    // Place tags the scorer prefers for this theme
    public List<String> interests() {
        return interests;
    }

    public String title() {
        return Texts.t(title, titleEn);
    }

    public String description() {
        return Texts.t(description, descriptionEn);
    }

    // "Ankara: Tarihi yerler" / "Çankaya: Historic sights"
    public String titleFor(String startLabel) {
        return startLabel == null ? title() : startLabel + ": " + title();
    }
}
