package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.PlaceOpeningHours;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class OsmOpeningHoursParserTest {

    // 2026-09-28 is a Monday
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

    @Test
    void timeRangeWithoutDaysMeansEveryDay() {
        List<OpeningHoursDto> rows = OsmOpeningHoursParser.parse("09:00-22:00");

        assertThat(rows).hasSize(7).allMatch(r -> r.opensAt().equals(t("09:00")) && r.closesAt().equals(t("22:00")));
        assertThat(rows).extracting(OpeningHoursDto::dayOfWeek).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void dayRangesAndLaterRulesPerDay() {
        List<OpeningHoursDto> rows = OsmOpeningHoursParser.parse("Mo-Fr 08:00-20:00; Sa 12:00-17:00");

        assertThat(rows).containsExactly(
                row(1, "08:00", "20:00"), row(2, "08:00", "20:00"), row(3, "08:00", "20:00"),
                row(4, "08:00", "20:00"), row(5, "08:00", "20:00"), row(6, "12:00", "17:00"));
    }

    @Test
    void moSuAndDayListsAndOff() {
        assertThat(OsmOpeningHoursParser.parse("Mo-Su 09:00-22:00")).hasSize(7);
        assertThat(OsmOpeningHoursParser.parse("Mo,We,Fr 10:00-18:00"))
                .containsExactly(row(1, "10:00", "18:00"), row(3, "10:00", "18:00"), row(5, "10:00", "18:00"));
        // A later rule replaces the earlier one for the days it names
        assertThat(OsmOpeningHoursParser.parse("Mo-Su 09:00-17:00; Mo off"))
                .extracting(OpeningHoursDto::dayOfWeek).containsExactly(2, 3, 4, 5, 6, 7);
        // Ranges may wrap over the end of the week
        assertThat(OsmOpeningHoursParser.parse("Sa-Mo 10:00-14:00"))
                .extracting(OpeningHoursDto::dayOfWeek).containsExactly(1, 6, 7);
    }

    @Test
    void midnightAndAfterMidnight() {
        assertThat(OsmOpeningHoursParser.parse("Mo 18:00-24:00")).containsExactly(row(1, "18:00", "00:00"));
        assertThat(OsmOpeningHoursParser.parse("Fr 18:00-02:00")).containsExactly(row(5, "18:00", "02:00"));
        assertThat(OsmOpeningHoursParser.parse("Tu 00:00-24:00")).containsExactly(row(2, "00:00", "00:00"));
    }

    @Test
    void severalRangesInOneDay() {
        assertThat(OsmOpeningHoursParser.parse("Mo 09:00-12:00,13:00-18:00"))
                .containsExactly(row(1, "09:00", "12:00"), row(1, "13:00", "18:00"));
    }

    @Test
    void anythingComplicatedMeansUnknown() {
        for (String value : List.of(
                "Mo-Fr 09:00-18:00; PH off",
                "Jan-Mar 10:00-16:00",
                "Mo-Fr 09:00-18:00 \"call first\"",
                "sunrise-sunset",
                "Mo-Fr 09:00+",
                "Mo-Fr 09:00-18:00, Sa 10:00-14:00",
                "10:00-10:00",
                "25:00-26:00",
                "Mo-Fr",
                "")) {
            assertThat(OsmOpeningHoursParser.parse(value)).as(value).isEmpty();
        }
        assertThat(OsmOpeningHoursParser.parse(null)).isEmpty();
    }

    @Test
    void twentyFourSevenIsAlwaysOpenEvenAcrossMidnight() {
        Place place = withHours(OsmOpeningHoursParser.parse("24/7"));

        assertThat(place.isOpenDuring(MONDAY, t("03:00"), 60)).isTrue();
        assertThat(place.isOpenDuring(MONDAY, t("23:30"), 90)).isTrue();
        assertThat(place.isOpenDuring(MONDAY, t("00:00"), 24 * 60)).isTrue();
    }

    @Test
    void closingAt24IsOpenUntilMidnightOnly() {
        Place place = withHours(OsmOpeningHoursParser.parse("Mo-Su 18:00-24:00"));

        assertThat(place.isOpenDuring(MONDAY, t("23:00"), 60)).isTrue();
        assertThat(place.isOpenDuring(MONDAY, t("23:30"), 60)).isFalse();
    }

    private static Place withHours(List<OpeningHoursDto> rows) {
        Place place = place(1, "P", PlaceCategory.CAFE, true, 4.0, 0);
        place.replaceOpeningHours(rows.stream()
                .map(r -> new PlaceOpeningHours(r.dayOfWeek(), r.opensAt(), r.closesAt()))
                .toList());
        return place;
    }

    private static OpeningHoursDto row(int day, String opens, String closes) {
        return new OpeningHoursDto(day, t(opens), t(closes));
    }

    private static LocalTime t(String time) {
        return LocalTime.parse(time);
    }
}
