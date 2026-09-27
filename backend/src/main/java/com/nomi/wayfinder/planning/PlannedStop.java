package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.StopType;

import java.time.LocalTime;
import java.util.List;

public record PlannedStop(
        Place place,
        StopType type,
        LocalTime start,
        LocalTime end,
        int distanceFromPreviousMeters,
        int walkingMinutes,
        List<String> reasons
) {

    // Unknown price (null) adds nothing to totals; the plan gets a note about it instead
    public int totalCost(int partySize) {
        Integer cost = place.getEstimatedCost();
        return cost == null ? 0 : cost * partySize;
    }
}
