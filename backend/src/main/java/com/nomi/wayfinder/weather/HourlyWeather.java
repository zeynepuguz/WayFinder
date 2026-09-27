package com.nomi.wayfinder.weather;

import java.io.Serializable;
import java.time.LocalDateTime;

// One hour of forecast. Serializable because forecasts are cached in Redis.
public record HourlyWeather(
        LocalDateTime time,
        double temperature,
        double apparentTemperature,
        int precipitationProbability,
        double precipitation,
        double windSpeed,
        int weatherCode
) implements Serializable {

    // Thresholds used by the planner to decide what "bad for outdoor" means
    public static final double HOT_APPARENT_C = 30;
    public static final double COLD_APPARENT_C = 5;
    public static final double WINDY_KMH = 35;
    public static final int WET_PROBABILITY = 60;

    public WeatherCondition condition() {
        return WeatherCondition.fromWmoCode(weatherCode);
    }

    public boolean isWet() {
        return condition().isWet() || precipitation >= 0.3 || precipitationProbability >= WET_PROBABILITY;
    }

    public boolean isHot() {
        return apparentTemperature >= HOT_APPARENT_C;
    }

    public boolean isCold() {
        return apparentTemperature <= COLD_APPARENT_C;
    }

    public boolean isWindy() {
        return windSpeed >= WINDY_KMH;
    }
}
