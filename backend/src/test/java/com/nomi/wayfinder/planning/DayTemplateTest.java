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

        // Three sights, not five food stops and two sights
        assertThat(slots).extracting(PlanningSlot::type).containsExactly(
                StopType.BREAKFAST, StopType.SIGHTSEEING, StopType.LUNCH, StopType.SIGHTSEEING,
                StopType.COFFEE, StopType.SIGHTSEEING, StopType.DINNER);
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

    @Test
    void sightInterestsMakeADayOfSeveralSightsNotOne() {
        // The form's "Gezi" is a toggle: breakfast, sights, dessert, dinner with history / nature / view / architecture
        List<PlanningSlot> slots = DayTemplate.slotsFor(
                List.of(StopType.BREAKFAST, StopType.SIGHTSEEING, StopType.DESSERT, StopType.DINNER),
                LocalTime.of(10, 0), LocalTime.of(22, 0), List.of("history", "nature", "view", "architecture", "budget"));

        assertThat(slots).filteredOn(s -> s.type() == StopType.SIGHTSEEING).hasSize(DayTemplate.MAX_SIGHTS);
        // Still in the order of a day
        assertThat(slots.getFirst().type()).isEqualTo(StopType.BREAKFAST);
        assertThat(slots.getLast().type()).isEqualTo(StopType.DINNER);
    }

    @Test
    void aTickedSightseeingIsAtLeastTwoSightsAndInterestsAddSightsWhenNotTicked() {
        assertThat(DayTemplate.withEnoughSights(List.of(StopType.SIGHTSEEING, StopType.LUNCH), List.of()))
                .filteredOn(t -> t == StopType.SIGHTSEEING).hasSize(DayTemplate.MIN_SIGHTS);
        assertThat(DayTemplate.withEnoughSights(List.of(StopType.LUNCH), List.of("museum")))
                .filteredOn(t -> t == StopType.SIGHTSEEING).hasSize(DayTemplate.MIN_SIGHTS);
        // Food only, no sight interests: as asked
        assertThat(DayTemplate.withEnoughSights(List.of(StopType.BREAKFAST, StopType.COFFEE), List.of("budget")))
                .containsExactly(StopType.BREAKFAST, StopType.COFFEE);
    }

    @Test
    void anEveningWalkIsOneSightNotADayTrip() {
        // "Akşamüstü yürüyüş, tatlı ve akşam yemeği" in Of, 17:15-22:00: the second sight was 500 m the other way
        List<PlanningSlot> evening = DayTemplate.slotsFor(List.of(StopType.SIGHTSEEING, StopType.DESSERT, StopType.DINNER),
                LocalTime.of(17, 15), LocalTime.of(22, 0), List.of());
        assertThat(evening).extracting(PlanningSlot::type)
                .containsExactly(StopType.SIGHTSEEING, StopType.DESSERT, StopType.DINNER);
        // A sight interest still brings one sight into a short window
        assertThat(DayTemplate.slotsFor(List.of(StopType.DESSERT), LocalTime.of(17, 0), LocalTime.of(21, 0), List.of("history")))
                .filteredOn(s -> s.type() == StopType.SIGHTSEEING).hasSize(1);
        // A whole day keeps the day-trip rule
        assertThat(DayTemplate.slotsFor(List.of(StopType.SIGHTSEEING, StopType.DINNER), LocalTime.of(10, 0), LocalTime.of(22, 0), List.of()))
                .filteredOn(s -> s.type() == StopType.SIGHTSEEING).hasSize(DayTemplate.MIN_SIGHTS);
        assertThat(DayTemplate.minutesBetween(LocalTime.of(20, 0), LocalTime.of(1, 0))).isEqualTo(300);
    }
}
