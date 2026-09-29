package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

// Turns "which stops" + "which hours" into an ordered list of planner slots
public final class DayTemplate {

    // A full day when the user does not list stops: a trip is about the places, so three sights and the meals
    // around them (dessert only when asked for; it made days of five food stops and two sights)
    static final List<PlanningSlot> FULL_DAY = List.of(
            PlanningSlot.at(StopType.BREAKFAST, LocalTime.of(9, 30)),
            PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0)),
            PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0)),
            PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(14, 30)),
            PlanningSlot.at(StopType.COFFEE, LocalTime.of(16, 0)),
            PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(17, 0)),
            PlanningSlot.at(StopType.DINNER, LocalTime.of(20, 0))
    );

    private static final List<LocalTime> SIGHTSEEING_TIMES =
            List.of(LocalTime.of(11, 0), LocalTime.of(14, 30), LocalTime.of(16, 30), LocalTime.of(17, 30));

    // Interests that are about places to see (not food / price): picking them means "show me such places"
    static final Set<String> SIGHT_INTERESTS = Set.of("history", "museum", "art", "architecture", "nature", "view",
            "sea", "street-art", "books");
    // "Gezi" in the form is a toggle, not "one place": at least this many sights, more with several sight interests
    static final int MIN_SIGHTS = 2;
    static final int MAX_SIGHTS = 3;

    private DayTemplate() {
    }

    public static List<PlanningSlot> slotsFor(List<StopType> requested, LocalTime start, LocalTime end) {
        return slotsFor(requested, start, end, List.of());
    }

    /**
     * @param interests the day's interests: with the stops listed, two or more sight interests ("tarih, doğa, mimari")
     *                  make it MAX_SIGHTS sights, and any sight interest adds sights even when "Gezi" was not ticked
     */
    public static List<PlanningSlot> slotsFor(List<StopType> requested, LocalTime start, LocalTime end,
                                              List<String> interests) {
        if (requested == null || requested.isEmpty()) {
            return FULL_DAY.stream()
                    .filter(s -> fitsWindow(s.targetTime(), start, end))
                    .toList();
        }
        requested = withEnoughSights(requested, interests);

        List<PlanningSlot> slots = new ArrayList<>();
        int sightseeingCount = 0;
        List<StopType> seen = new ArrayList<>();

        for (StopType type : requested) {
            if (type == StopType.SIGHTSEEING) {
                LocalTime target = sightseeingCount < SIGHTSEEING_TIMES.size()
                        ? SIGHTSEEING_TIMES.get(sightseeingCount) : null;
                slots.add(PlanningSlot.at(type, target));
                sightseeingCount++;
            } else {
                // A repeated stop type (e.g. two coffees) just follows the previous stop
                slots.add(PlanningSlot.at(type, seen.contains(type) ? null : type.getDefaultTime()));
            }
            seen.add(type);
        }

        // Keep the natural order of a day: breakfast before lunch before dinner
        slots.sort(Comparator.comparing(PlanningSlot::targetTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return slots;
    }

    // The requested stops with MIN_SIGHTS..MAX_SIGHTS sightseeing stops when sights are wanted at all
    static List<StopType> withEnoughSights(List<StopType> requested, List<String> interests) {
        long sightInterests = interests == null ? 0 : interests.stream().filter(SIGHT_INTERESTS::contains).count();
        long asked = requested.stream().filter(t -> t == StopType.SIGHTSEEING).count();
        if (asked == 0 && sightInterests == 0) {
            return requested;
        }
        long wanted = Math.max(asked, sightInterests >= 2 ? MAX_SIGHTS : MIN_SIGHTS);
        List<StopType> result = new ArrayList<>(requested);
        for (long i = asked; i < wanted; i++) {
            result.add(StopType.SIGHTSEEING);
        }
        return result;
    }

    // A template stop is kept if its time is inside the user's window (with a little slack at the start)
    private static boolean fitsWindow(LocalTime target, LocalTime start, LocalTime end) {
        boolean crossesMidnight = !end.isAfter(start);
        boolean afterStart = !target.isBefore(start.minusMinutes(30)) || start.isBefore(LocalTime.of(0, 30));
        boolean beforeEnd = crossesMidnight || target.isBefore(end.minusMinutes(30));
        return afterStart && beforeEnd;
    }
}
