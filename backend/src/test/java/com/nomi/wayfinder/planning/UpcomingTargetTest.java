package com.nomi.wayfinder.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class UpcomingTargetTest {

    private static int at(int hour, int minute) {
        return hour * 60 + minute;
    }

    @Test
    void futureTargetIsKept() {
        assertThat(RoutePlanner.upcomingTarget(LocalTime.of(20, 0), at(12, 45))).isEqualTo(at(20, 0));
    }

    @Test
    void recentlyPassedTargetIsKeptSoTheStopHappensNow() {
        // 12:00 lunch asked at 12:45 -> target stays today (arrival = now)
        assertThat(RoutePlanner.upcomingTarget(LocalTime.of(12, 0), at(12, 45))).isEqualTo(at(12, 0));
    }

    @Test
    void longPassedTargetMeansNoTargetInsteadOfTomorrow() {
        // Breakfast (09:30) asked for at 12:45 must not move to tomorrow
        assertThat(RoutePlanner.upcomingTarget(LocalTime.of(9, 30), at(12, 45))).isNull();
    }

    @Test
    void targetJustAfterMidnightWrapsForLatePlans() {
        assertThat(RoutePlanner.upcomingTarget(LocalTime.of(0, 30), at(23, 0))).isEqualTo(at(24, 30));
    }
}
