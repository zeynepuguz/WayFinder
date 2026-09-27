package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;

import java.time.LocalTime;

/**
 * One stop the planner has to fill.
 *
 * @param targetTime      preferred time of day (null = right after the previous stop)
 * @param pinnedPlaceId   keep this place if it still works (used when replanning); null = planner chooses
 * @param durationMinutes override the visit length (e.g. a short rest stop); null = place/stop default
 * @param exactTime       targetTime is an already planned time, so no "start a bit earlier" flexibility
 *                        (otherwise every replan would move the stop earlier again)
 */
public record PlanningSlot(
        StopType type,
        LocalTime targetTime,
        Long pinnedPlaceId,
        Integer durationMinutes,
        boolean exactTime
) {

    public static PlanningSlot of(StopType type) {
        return new PlanningSlot(type, type.getDefaultTime(), null, null, false);
    }

    public static PlanningSlot at(StopType type, LocalTime targetTime) {
        return new PlanningSlot(type, targetTime, null, null, false);
    }

    public static PlanningSlot next(StopType type, Integer durationMinutes) {
        return new PlanningSlot(type, null, null, durationMinutes, false);
    }

    // A stop that already exists in a route: keep its place and time if still possible
    public static PlanningSlot existing(StopType type, LocalTime plannedStart, Long placeId) {
        return new PlanningSlot(type, plannedStart, placeId, null, true);
    }

    public PlanningSlot unpinned() {
        return new PlanningSlot(type, targetTime, null, durationMinutes, exactTime);
    }
}
