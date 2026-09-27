package com.nomi.wayfinder.weather;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    // Open-Meteo forecasts ~16 days ahead
    private static final int MAX_FORECAST_DAYS = 15;

    private final OpenMeteoClient client;
    private final Clock clock;

    public WeatherService(OpenMeteoClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * Empty when the date is out of forecast range or the weather API is down.
     * Planning must keep working without weather, so errors are logged, not thrown.
     */
    public Optional<WeatherForecast> getForecast(double latitude, double longitude, LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_FORECAST_DAYS))) {
            return Optional.empty();
        }

        try {
            // ~1 km grid: users in the same area share one cache entry
            return Optional.of(client.fetch(round(latitude), round(longitude), date));
        } catch (Exception e) {
            log.warn("Weather forecast unavailable for {},{} on {}: {}", latitude, longitude, date, e.getMessage());
            return Optional.empty();
        }
    }

    // One or two sentences that explain how the weather changes the plan
    public String advice(WeatherForecast.DaySummary summary) {
        List<String> parts = new ArrayList<>();

        if (summary.wet()) {
            parts.add(String.format(Locale.ROOT,
                    "Yağış bekleniyor (olasılık %%%d). Kapalı mekanlara öncelik verdim.",
                    summary.maxPrecipitationProbability()));
        }
        if (summary.hot()) {
            parts.add(String.format(Locale.ROOT,
                    "Sıcaklık %.0f°C (hissedilen %.0f°C). Öğle saatlerinde uzun süre güneş altında kalmamanızı öneririm; açık alanları serin saatlere kaydırdım.",
                    summary.maxTemperature(), summary.maxApparentTemperature()));
        }
        if (summary.windy()) {
            parts.add(String.format(Locale.ROOT,
                    "Rüzgar %.0f km/s'ye çıkabilir. Sahil ve açık alanları azalttım.", summary.maxWindSpeed()));
        }
        if (summary.cold()) {
            parts.add(String.format(Locale.ROOT,
                    "Hava soğuk (hissedilen %.0f°C). Kapalı mekanlar daha rahat olacaktır.",
                    summary.minApparentTemperature()));
        }
        if (parts.isEmpty()) {
            parts.add(String.format(Locale.ROOT,
                    "Hava %s, en fazla %.0f°C. Gezmek için uygun.",
                    summary.condition().getLabel(), summary.maxTemperature()));
        }

        return String.join(" ", parts);
    }

    public String advice(WeatherForecast forecast, LocalTime from, LocalTime to) {
        return advice(forecast.summarize(from, to));
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
