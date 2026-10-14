package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.WalkingTolerance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Everything the planner needs. Built by RouteService from a user request, a replan or the assistant.
 *
 * @param budget     remaining budget in TL for the whole party (null = no limit)
 * @param assumeWet  treat the whole plan as rainy even if the forecast says otherwise ("Yağmur başladı")
 * @param savedPlaceIds places the user saved: preferred when they fit (PlaceScorer); empty for nobody's plan
 */
public record PlanningRequest(
        double startLatitude,
        double startLongitude,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        int partySize,
        Integer budget,
        WalkingTolerance walkingTolerance,
        List<String> interests,
        List<PlanningSlot> slots,
        Set<Long> excludedPlaceIds,
        boolean assumeWet,
        Set<Long> savedPlaceIds
) {

    public PlanningRequest(double startLatitude, double startLongitude, LocalDate date, LocalTime startTime,
                           LocalTime endTime, int partySize, Integer budget, WalkingTolerance walkingTolerance,
                           List<String> interests, List<PlanningSlot> slots, Set<Long> excludedPlaceIds,
                           boolean assumeWet) {
        this(startLatitude, startLongitude, date, startTime, endTime, partySize, budget, walkingTolerance, interests,
                slots, excludedPlaceIds, assumeWet, Set.of());
    }

    public PlanningRequest withSaved(Set<Long> saved) {
        return new PlanningRequest(startLatitude, startLongitude, date, startTime, endTime, partySize, budget,
                walkingTolerance, interests, slots, excludedPlaceIds, assumeWet, saved == null ? Set.of() : saved);
    }
}
