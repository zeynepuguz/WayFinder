package com.nomi.wayfinder.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceOpeningHoursTest {

    // 2026-09-28 is a Monday
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

    @Test
    void openWhenWholeVisitIsInsideOpeningHours() {
        Place cafe = openEveryDay(place(1, "Cafe", PlaceCategory.CAFE, true, 4.5, 100), "09:00", "18:00");

        assertThat(cafe.isOpenDuring(MONDAY, LocalTime.of(10, 0), 60)).isTrue();
    }

    @Test
    void closedWhenVisitEndsAfterClosing() {
        Place cafe = openEveryDay(place(1, "Cafe", PlaceCategory.CAFE, true, 4.5, 100), "09:00", "18:00");

        assertThat(cafe.isOpenDuring(MONDAY, LocalTime.of(17, 30), 60)).isFalse();
        assertThat(cafe.isOpenDuring(MONDAY, LocalTime.of(8, 30), 30)).isFalse();
    }

    @Test
    void placeClosingAfterMidnightIsOpenLateAndEarlyNextDay() {
        Place bar = openEveryDay(place(1, "Bar", PlaceCategory.RESTAURANT, true, 4.5, 100), "12:00", "01:00");

        assertThat(bar.isOpenDuring(MONDAY, LocalTime.of(23, 30), 60)).isTrue();
        // 00:15 on Monday belongs to Sunday's opening (12:00 -> 01:00)
        assertThat(bar.isOpenDuring(MONDAY, LocalTime.of(0, 15), 30)).isTrue();
        assertThat(bar.isOpenDuring(MONDAY, LocalTime.of(2, 0), 30)).isFalse();
    }

    @Test
    void closedOnDaysWithoutHours() {
        Place museum = place(1, "Museum", PlaceCategory.MUSEUM, true, 4.5, 100);
        // Tuesday..Sunday only
        museum.replaceOpeningHours(List.of(
                new PlaceOpeningHours(2, LocalTime.of(10, 0), LocalTime.of(17, 0)),
                new PlaceOpeningHours(3, LocalTime.of(10, 0), LocalTime.of(17, 0))));

        assertThat(museum.isOpenDuring(MONDAY, LocalTime.of(11, 0), 60)).isFalse();
        assertThat(museum.isOpenDuring(MONDAY.plusDays(1), LocalTime.of(11, 0), 60)).isTrue();
    }

    @Test
    void unknownWhenNoOpeningHours() {
        Place park = place(1, "Park", PlaceCategory.PARK, false, 4.5, 0);

        assertThat(park.isOpenDuring(MONDAY, LocalTime.of(3, 0), 60)).isNull();
    }
}
