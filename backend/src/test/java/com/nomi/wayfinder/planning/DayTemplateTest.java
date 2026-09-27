package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DayTemplateTest {

    @Test
    void fullDayWhenNoStopsRequested() {
        List<PlanningSlot> slots = DayTemplate.slotsFor(null, LocalTime.of(9, 0), LocalTime.of(22, 0));

        assertThat(slots).extracting(PlanningSlot::type).containsExactly(
                StopType.BREAKFAST, StopType.SIGHTSEEING, StopType.LUNCH, StopType.COFFEE,
                StopType.SIGHTSEEING, StopType.DESSERT, StopType.DINNER);
    }

    @Test
    void afternoonStartSkipsMorningStops() {
        List<PlanningSlot> slots = DayTemplate.slotsFor(List.of(), LocalTime.of(14, 0), LocalTime.of(22, 0));

        assertThat(slots).extracting(PlanningSlot::type)
                .doesNotContain(StopType.BREAKFAST, StopType.LUNCH)
                .contains(StopType.COFFEE, StopType.DINNER);
    }

    @Test
    void requestedStopsAreSortedIntoTheNaturalOrderOfADay() {
        List<PlanningSlot> slots = DayTemplate.slotsFor(
                List.of(StopType.DINNER, StopType.COFFEE, StopType.BREAKFAST), LocalTime.of(9, 0), LocalTime.of(22, 0));

        assertThat(slots).extracting(PlanningSlot::type)
                .containsExactly(StopType.BREAKFAST, StopType.COFFEE, StopType.DINNER);
    }
}
