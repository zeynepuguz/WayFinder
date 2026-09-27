package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.weather.HourlyWeather;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/weather")
public class WeatherController {

    private final WeatherService weatherService;
    private final Clock clock;

    public WeatherController(WeatherService weatherService, Clock clock) {
        this.weatherService = weatherService;
        this.clock = clock;
    }

    @GetMapping
    public WeatherResponse getWeather(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam(required = false) LocalDate date
    ) {
        LocalDate day = date != null ? date : LocalDate.now(clock);

        WeatherForecast forecast = weatherService.getForecast(lat, lon, day)
                .orElseThrow(() -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Weather forecast is not available for this date"));

        WeatherForecast.DaySummary summary = forecast.summarize(LocalTime.of(9, 0), LocalTime.of(22, 0));

        return new WeatherResponse(day, forecast.current(), summary, weatherService.advice(summary), forecast.hourly());
    }

    public record WeatherResponse(
            LocalDate date,
            HourlyWeather current,
            WeatherForecast.DaySummary summary,
            String advice,
            List<HourlyWeather> hourly
    ) {
    }
}
