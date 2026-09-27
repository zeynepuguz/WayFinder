package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.weather.WeatherForecast;

import java.util.List;
import java.util.Optional;

public record PlanResult(
        List<PlannedStop> stops,
        List<String> notes,
        Optional<WeatherForecast> forecast,
        String weatherAdvice
) {
}
