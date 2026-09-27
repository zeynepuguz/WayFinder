package com.nomi.wayfinder.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

// dayOfWeek: 1 = Monday ... 7 = Sunday. closesAt <= opensAt means it closes after midnight.
public record OpeningHoursDto(
        @Min(1) @Max(7) int dayOfWeek,
        @NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt
) {
}
