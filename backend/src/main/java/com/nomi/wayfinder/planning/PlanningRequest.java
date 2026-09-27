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
        boolean assumeWet
) {
}
