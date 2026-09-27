package com.nomi.wayfinder.weather;

import com.nomi.wayfinder.i18n.Texts;
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

    // One or two sentences that explain how the weather changes the plan (in the request's language)
    public String advice(WeatherForecast.DaySummary summary) {
        List<String> parts = new ArrayList<>();

        if (summary.wet()) {
            parts.add(String.format(Locale.ROOT, Texts.t(
                            "Yağış bekleniyor (olasılık %%%d). Kapalı mekanlara öncelik verdim.",
                            "Rain is expected (%d%% chance). I prioritized indoor places."),
                    summary.maxPrecipitationProbability()));
        }
        if (summary.hot()) {
            parts.add(String.format(Locale.ROOT, Texts.t(
                            "Sıcaklık %.0f°C (hissedilen %.0f°C). Öğle saatlerinde uzun süre güneş altında kalmamanızı öneririm; açık alanları serin saatlere kaydırdım.",
                            "It will be %.0f°C (feels like %.0f°C). I suggest not staying in the sun for long around midday; I moved outdoor places to cooler hours."),
                    summary.maxTemperature(), summary.maxApparentTemperature()));
        }
        if (summary.windy()) {
            parts.add(String.format(Locale.ROOT, Texts.t(
                            "Rüzgar %.0f km/s'ye çıkabilir. Sahil ve açık alanları azalttım.",
                            "Wind may reach %.0f km/h. I reduced seaside and open-air places."),
                    summary.maxWindSpeed()));
        }
        if (summary.cold()) {
            parts.add(String.format(Locale.ROOT, Texts.t(
                            "Hava soğuk (hissedilen %.0f°C). Kapalı mekanlar daha rahat olacaktır.",
                            "It is cold (feels like %.0f°C). Indoor places will be more comfortable."),
                    summary.minApparentTemperature()));
        }
        if (parts.isEmpty()) {
            parts.add(String.format(Locale.ROOT, Texts.t(
                            "Hava %s, en fazla %.0f°C. Gezmek için uygun.",
                            "The weather is %s, up to %.0f°C. Good for exploring."),
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
