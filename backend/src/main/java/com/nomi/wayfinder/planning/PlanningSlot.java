package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;

import java.time.LocalTime;
import java.util.Set;

/**
 * One stop the planner has to fill.
 *
 * @param targetTime      preferred time of day (null = right after the previous stop)
 * @param pinnedPlaceId   keep this place if it still works (used when replanning); null = planner chooses
 * @param durationMinutes override the visit length (e.g. a short rest stop); null = place/stop default
 * @param exactTime       targetTime is an already planned time, so no "start a bit earlier" flexibility
 *                        (otherwise every replan would move the stop earlier again)
 * @param categories      narrower place categories than the stop type's (a popular route's "history" stop:
 *                        museums, attractions, culture but no parks); null = the stop type's
 * @param matchingTag     with categories: a place of another category may still fill the slot if it has this tag
 *                        (e.g. "view" for viewpoints); ignored when categories is null
 */
public record PlanningSlot(
        StopType type,
        LocalTime targetTime,
        Long pinnedPlaceId,
        Integer durationMinutes,
        boolean exactTime,
        Set<PlaceCategory> categories,
        String matchingTag
) {

    public PlanningSlot(StopType type, LocalTime targetTime, Long pinnedPlaceId, Integer durationMinutes,
                        boolean exactTime) {
        this(type, targetTime, pinnedPlaceId, durationMinutes, exactTime, null, null);
    }

    public static PlanningSlot of(StopType type) {
        return new PlanningSlot(type, type.getDefaultTime(), null, null, false);
    }

    public static PlanningSlot at(StopType type, LocalTime targetTime) {
        return new PlanningSlot(type, targetTime, null, null, false);
    }

    public static PlanningSlot next(StopType type, Integer durationMinutes) {
        return new PlanningSlot(type, null, null, durationMinutes, false);
    }

    // A stop filled only from these categories (or places with the tag), at targetTime (null = after the previous)
    public static PlanningSlot restricted(StopType type, LocalTime targetTime, Set<PlaceCategory> categories,
                                          String matchingTag) {
        return new PlanningSlot(type, targetTime, null, null, false, Set.copyOf(categories), matchingTag);
    }

    // A stop that already exists in a route: keep its place and time if still possible
    public static PlanningSlot existing(StopType type, LocalTime plannedStart, Long placeId) {
        return new PlanningSlot(type, plannedStart, placeId, null, true);
    }

    public PlanningSlot unpinned() {
        return new PlanningSlot(type, targetTime, null, durationMinutes, exactTime, categories, matchingTag);
    }

    // The categories the planner searches
    public Set<PlaceCategory> searchCategories() {
        return categories != null ? categories : type.getCategories();
    }

    public String searchTag() {
        return categories != null ? matchingTag : type.getMatchingTag();
    }
}
