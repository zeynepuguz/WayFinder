package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.RouteDtos.RouteSummary;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.HourlyWeather;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Everything the home screen shows in one call: weather, what to do now, the current route
@RestController
@RequestMapping("/api/v1/home")
public class HomeController {

    private static final List<String> PROMPTS = List.of(
            "Bugün Kadıköy'de uygun bütçeli bir gün planla",
            "Yakında iyi bir kahveci öner",
            "2 kişiyiz, 700 TL bütçemiz var, çok yürümek istemiyoruz",
            "Tarihi yerler ve tatlı içeren bir rota oluştur"
    );

    // Same prompts for English requests; the rule-based parser understands each of them
    private static final List<String> PROMPTS_EN = List.of(
            "Plan a budget-friendly day in Kadıköy today",
            "Recommend a good coffee place nearby",
            "We are 2 people, our budget is 700 TL, we don't want to walk much",
            "Create a route with historical places and dessert"
    );

    private final WeatherService weatherService;
    private final RecommendationService recommendationService;
    private final RouteService routeService;
    private final Clock clock;

    public HomeController(
            WeatherService weatherService,
            RecommendationService recommendationService,
            RouteService routeService,
            Clock clock
    ) {
        this.weatherService = weatherService;
        this.recommendationService = recommendationService;
        this.routeService = routeService;
        this.clock = clock;
    }

    @GetMapping
    public HomeResponse home(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon
    ) {
        Long userId = CurrentUser.idOrNull(jwt);
        LocalTime now = LocalTime.now(clock);

        WeatherNow weather = weatherService.getForecast(lat, lon, LocalDate.now(clock))
                .map(f -> toWeatherNow(f, now))
                .orElse(null);

        StopType suggestedType = RecommendationService.suggestedTypeAt(now);
        // Nearby only; "better but farther" places are offered by the assistant when the user asks for suggestions
        List<Recommendation> suggestions = recommendationService.recommend(lat, lon, suggestedType, userId, 3);

        // Only a route of today is "Aktif rotan"; past days never are. Without one, tomorrow's plan is shown instead
        RouteSummary currentRoute = userId == null ? null
                : routeService.findCurrentRouteSummary(userId).orElse(null);
        RouteSummary tomorrowRoute = userId == null || currentRoute != null ? null
                : routeService.findTomorrowRouteSummary(userId).orElse(null);

        return new HomeResponse(weather, suggestedType, suggestions, currentRoute, tomorrowRoute,
                Texts.english() ? PROMPTS_EN : PROMPTS);
    }

    private WeatherNow toWeatherNow(WeatherForecast forecast, LocalTime now) {
        HourlyWeather current = forecast.current() != null ? forecast.current() : forecast.at(now);
        LocalTime until = now.isBefore(LocalTime.of(21, 0)) ? LocalTime.of(22, 0) : LocalTime.of(23, 0);

        return new WeatherNow(
                current.temperature(),
                current.apparentTemperature(),
                current.condition().name(),
                current.condition().getLabel(),
                weatherService.advice(forecast, now, until)
        );
    }

    public record WeatherNow(
            double temperature,
            double apparentTemperature,
            String condition,
            String conditionLabel,
            String advice
    ) {
    }

    public record HomeResponse(
            WeatherNow weather,
            StopType suggestedStopType,
            List<Recommendation> suggestions,
            // Today's unfinished route ("Aktif rotan"), or null
            RouteSummary currentRoute,
            // "Yarınki rotan": only when there is no route today
            RouteSummary tomorrowRoute,
            List<String> prompts
    ) {
    }
}
