package com.nomi.wayfinder.weather;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

// Hourly forecast for one day at one location. "current" is only present for today.
public record WeatherForecast(
        LocalDate date,
        HourlyWeather current,
        List<HourlyWeather> hourly
) implements Serializable {

    // Weather at the hour closest to the given time
    public HourlyWeather at(LocalTime time) {
        return hourly.stream()
                .min(Comparator.comparingLong(h ->
                        Math.abs(java.time.Duration.between(h.time().toLocalTime(), time).toMinutes())))
                .orElse(current);
    }

    public List<HourlyWeather> between(LocalTime from, LocalTime to) {
        List<HourlyWeather> window = hourly.stream()
                .filter(h -> !h.time().toLocalTime().isBefore(from.withMinute(0))
                        && !h.time().toLocalTime().isAfter(to))
                .toList();
        return window.isEmpty() ? hourly : window;
    }

    // Worst-case view of a time window; used to write the advice sentence
    public DaySummary summarize(LocalTime from, LocalTime to) {
        List<HourlyWeather> window = between(from, to);

        return new DaySummary(
                window.stream().mapToDouble(HourlyWeather::temperature).max().orElse(Double.NaN),
                window.stream().mapToDouble(HourlyWeather::apparentTemperature).max().orElse(Double.NaN),
                window.stream().mapToDouble(HourlyWeather::apparentTemperature).min().orElse(Double.NaN),
                window.stream().mapToInt(HourlyWeather::precipitationProbability).max().orElse(0),
                window.stream().mapToDouble(HourlyWeather::windSpeed).max().orElse(0),
                window.stream().anyMatch(HourlyWeather::isWet),
                window.stream().anyMatch(HourlyWeather::isHot),
                window.stream().anyMatch(HourlyWeather::isWindy),
                window.stream().allMatch(HourlyWeather::isCold),
                dominant(window)
        );
    }

    private static WeatherCondition dominant(List<HourlyWeather> window) {
        return window.stream()
                .map(HourlyWeather::condition)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(WeatherCondition.CLEAR);
    }

    public record DaySummary(
            double maxTemperature,
            double maxApparentTemperature,
            double minApparentTemperature,
            int maxPrecipitationProbability,
            double maxWindSpeed,
            boolean wet,
            boolean hot,
            boolean windy,
            boolean cold,
            WeatherCondition condition
    ) implements Serializable {
    }
}
