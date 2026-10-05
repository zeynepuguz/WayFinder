package com.nomi.wayfinder.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nomi.wayfinder.config.HttpClients;
import com.nomi.wayfinder.config.NomiProperties;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// HTTP client for https://open-meteo.com (free, no API key)
@Component
public class OpenMeteoClient {

    private static final String FIELDS =
            "temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m";

    private final RestClient restClient;
    private final String timezone;

    public OpenMeteoClient(NomiProperties properties) {
        NomiProperties.Weather weather = properties.weather();

        // Timeouts: a slow weather API must not make route planning hang
        this.restClient = HttpClients.restClient(weather.connectTimeout(), weather.readTimeout())
                .baseUrl(weather.baseUrl())
                .build();
        this.timezone = properties.timezone();
    }

    // Cached in Redis (TTL in application.yml). Callers round coordinates so nearby users share entries.
    @Cacheable(cacheNames = "weather", key = "#latitude + ':' + #longitude + ':' + #date")
    public WeatherForecast fetch(double latitude, double longitude, LocalDate date) {
        OpenMeteoResponse response = restClient.get()
                .uri(uri -> uri.path("/v1/forecast")
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("current", FIELDS)
                        .queryParam("hourly", FIELDS)
                        .queryParam("timezone", timezone)
                        .queryParam("start_date", date)
                        .queryParam("end_date", date)
                        .build())
                .retrieve()
                .body(OpenMeteoResponse.class);

        if (response == null || response.hourly() == null) {
            throw new IllegalStateException("Empty response from Open-Meteo");
        }

        return new WeatherForecast(date, toCurrent(response.current(), date), toHourly(response.hourly()));
    }

    private static HourlyWeather toCurrent(Current current, LocalDate date) {
        if (current == null || !LocalDateTime.parse(current.time()).toLocalDate().equals(date)) {
            return null;
        }
        return new HourlyWeather(
                LocalDateTime.parse(current.time()),
                orZero(current.temperature()),
                orZero(current.apparentTemperature()),
                current.precipitationProbability() == null ? 0 : current.precipitationProbability(),
                orZero(current.precipitation()),
                orZero(current.windSpeed()),
                current.weatherCode() == null ? 0 : current.weatherCode()
        );
    }

    private static List<HourlyWeather> toHourly(Hourly hourly) {
        List<HourlyWeather> result = new ArrayList<>();
        for (int i = 0; i < hourly.time().size(); i++) {
            result.add(new HourlyWeather(
                    LocalDateTime.parse(hourly.time().get(i)),
                    valueAt(hourly.temperature(), i),
                    valueAt(hourly.apparentTemperature(), i),
                    (int) valueAt(hourly.precipitationProbability(), i),
                    valueAt(hourly.precipitation(), i),
                    valueAt(hourly.windSpeed(), i),
                    (int) valueAt(hourly.weatherCode(), i)
            ));
        }
        return List.copyOf(result);
    }

    private static double valueAt(List<? extends Number> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) {
            return 0;
        }
        return values.get(index).doubleValue();
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    record OpenMeteoResponse(Current current, Hourly hourly) {
    }

    record Current(
            String time,
            @JsonProperty("temperature_2m") Double temperature,
            @JsonProperty("apparent_temperature") Double apparentTemperature,
            @JsonProperty("precipitation_probability") Integer precipitationProbability,
            Double precipitation,
            @JsonProperty("weather_code") Integer weatherCode,
            @JsonProperty("wind_speed_10m") Double windSpeed
    ) {
    }

    record Hourly(
            List<String> time,
            @JsonProperty("temperature_2m") List<Double> temperature,
            @JsonProperty("apparent_temperature") List<Double> apparentTemperature,
            @JsonProperty("precipitation_probability") List<Integer> precipitationProbability,
            List<Double> precipitation,
            @JsonProperty("weather_code") List<Integer> weatherCode,
            @JsonProperty("wind_speed_10m") List<Double> windSpeed
    ) {
    }
}
