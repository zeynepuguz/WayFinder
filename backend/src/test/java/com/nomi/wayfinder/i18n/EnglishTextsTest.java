package com.nomi.wayfinder.i18n;

import com.nomi.wayfinder.assistant.ResponseComposer;
import com.nomi.wayfinder.config.WebConfig;
import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StopPlace;
import com.nomi.wayfinder.dto.RouteDtos.StopResponse;
import com.nomi.wayfinder.dto.RouteDtos.WeatherSnapshot;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.service.Interests;
import com.nomi.wayfinder.service.PlaceMapper;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherCondition;
import com.nomi.wayfinder.weather.WeatherForecast.DaySummary;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

// English texts for "For tourists" mode, and Turkish staying the default
class EnglishTextsTest {

    private final ResponseComposer composer = new ResponseComposer();
    private final WeatherService weatherService = new WeatherService(null, Clock.systemUTC());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void acceptLanguageSelectsEnglishAndEverythingElseIsTurkish() {
        LocaleResolver resolver = new WebConfig(null, null, null).localeResolver();

        assertThat(resolver.resolveLocale(new MockHttpServletRequest()).getLanguage()).isEqualTo("tr");
        assertThat(resolver.resolveLocale(withLanguage("en")).getLanguage()).isEqualTo("en");
        assertThat(resolver.resolveLocale(withLanguage("en-US,en;q=0.9")).getLanguage()).isEqualTo("en");
        assertThat(resolver.resolveLocale(withLanguage("tr")).getLanguage()).isEqualTo("tr");
        assertThat(resolver.resolveLocale(withLanguage("de")).getLanguage()).isEqualTo("tr");
    }

    @Test
    void turkishWithoutALocaleContextWhateverTheJvmDefault() {
        Locale jvm = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertThat(Texts.english()).isFalse();
            assertThat(Texts.t("Merhaba", "Hello")).isEqualTo("Merhaba");
        } finally {
            Locale.setDefault(jvm);
        }
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(Texts.t("Merhaba", "Hello")).isEqualTo("Hello");
    }

    @Test
    void routeTitleUsesTheRequestLanguage() {
        LocalDate date = LocalDate.of(2026, 9, 27);
        assertThat(RouteService.defaultTitle(date)).isEqualTo("27 Eylül Pazar Rotası");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(RouteService.defaultTitle(date)).isEqualTo("Sunday, 27 September route");
    }

    @Test
    void planReplyInEnglishKeepsTheStopLineFormat() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        String reply = composer.planCreated(route("Sunday, 27 September route", StopStatus.PLANNED));

        assertThat(reply).isEqualTo("""
                Sunday, 27 September route is ready! I created a route with 2 stops:
                09:30 → Breakfast: Çiya (~200 TL per person)
                11:00 → Sightseeing: Moda Sahili (free)

                Estimated total spend: ~400 TL (2 people) · Total walking: ~12 min. Your budget: 700 TL.

                The weather is clear, up to 24°C. Good for exploring.""");
    }

    @Test
    void planReplyInTurkishIsUnchanged() {
        String reply = composer.planCreated(route("27 Eylül Pazar Rotası", StopStatus.PLANNED));

        assertThat(reply).isEqualTo("""
                27 Eylül Pazar Rotası hazır! 2 duraklı bir rota oluşturdum:
                09:30 → Kahvaltı: Çiya (kişi başı ~200 TL)
                11:00 → Gezi: Moda Sahili (ücretsiz)

                Toplam tahmini harcama: ~400 TL (2 kişi) · Toplam yürüme: ~12 dk. Bütçen: 700 TL.

                The weather is clear, up to 24°C. Good for exploring.""");
    }

    @Test
    void otherAssistantAnswersInEnglish() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(composer.routeChanged(route("R", StopStatus.PLANNED), List.of("Removed: X")))
                .isEqualTo("I updated your route:\n• Removed: X\n\nNext stop: 09:30 → Breakfast: Çiya (~200 TL per person)");
        assertThat(composer.showRoute(route("R", StopStatus.VISITED)))
                .contains("09:30 → Breakfast: Çiya (~200 TL per person) (visited)");
        assertThat(composer.recommendations(List.of(), StopType.COFFEE.getLabel()))
                .isEqualTo("I could not find a suitable coffee place open near you right now.");
        assertThat(composer.noRoute()).startsWith("There is no route in this chat yet.");
        assertThat(composer.help()).startsWith("I can help you plan your day in cities all over Turkey.");
    }

    @Test
    void weatherAdviceInEnglish() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(weatherService.advice(new DaySummary(24, 25, 18, 80, 10, true, false, false, false, WeatherCondition.RAIN)))
                .isEqualTo("Rain is expected (80% chance). I prioritized indoor places.");
        assertThat(weatherService.advice(new DaySummary(34, 36, 25, 0, 40, false, true, true, false, WeatherCondition.CLEAR)))
                .isEqualTo("It will be 34°C (feels like 36°C). I suggest not staying in the sun for long around midday; "
                        + "I moved outdoor places to cooler hours. Wind may reach 40 km/h. I reduced seaside and open-air places.");
        assertThat(weatherService.advice(new DaySummary(22, 22, 18, 0, 5, false, false, false, false, WeatherCondition.CLOUDY)))
                .isEqualTo("The weather is cloudy, up to 22°C. Good for exploring.");
    }

    @Test
    void weatherAdviceInTurkishIsUnchanged() {
        assertThat(weatherService.advice(new DaySummary(24, 25, 18, 80, 10, true, false, false, false, WeatherCondition.RAIN)))
                .isEqualTo("Yağış bekleniyor (olasılık %80). Kapalı mekanlara öncelik verdim.");
    }

    @Test
    void labelsFollowTheRequestLanguage() {
        assertThat(StopType.DINNER.getLabel()).isEqualTo("Akşam yemeği");
        assertThat(AccessPlan.WEEKLY.getLabel()).isEqualTo("Haftalık");
        assertThat(Interests.label("history")).isEqualTo("tarih");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(StopType.DINNER.getLabel()).isEqualTo("Dinner");
        assertThat(AccessPlan.WEEKLY.getLabel()).isEqualTo("Weekly");
        assertThat(Interests.label("history")).isEqualTo("history");
        assertThat(WeatherCondition.STORM.getLabel()).isEqualTo("stormy");
    }

    @Test
    void placeDescriptionInEnglishWithTurkishFallback() {
        PlaceMapper mapper = new PlaceMapper(Clock.systemUTC());
        Place translated = place(1, "Moda Sahili", PlaceCategory.PARK, false, 4.7, 0);
        translated.setDescription("Deniz kenarında yürüyüş ve gün batımı.");
        translated.setDescriptionEn("Walks by the sea and sunsets.");
        Place untranslated = place(2, "Yeni Yer", PlaceCategory.CAFE, true, 4.0, 100);
        untranslated.setDescription("Yeni bir kafe.");

        assertThat(mapper.toResponse(translated).getDescription()).isEqualTo("Deniz kenarında yürüyüş ve gün batımı.");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(mapper.toResponse(translated).getDescription()).isEqualTo("Walks by the sea and sunsets.");
        assertThat(mapper.toResponse(untranslated).getDescription()).isEqualTo("Yeni bir kafe.");
    }

    // ---------- helpers ----------

    private static MockHttpServletRequest withLanguage(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", header);
        request.setPreferredLocales(List.of(Locale.forLanguageTag(header.split(",")[0])));
        return request;
    }

    // Built while the test's locale is active, like RouteMapper does for a real request.
    // The stored advice is always the English sentence here, as if the route was created in English.
    private static RouteResponse route(String title, StopStatus firstStatus) {
        StopResponse breakfast = new StopResponse(1L, 0, StopType.BREAKFAST, StopType.BREAKFAST.getLabel(),
                LocalTime.of(9, 30), LocalTime.of(10, 30), 300, 5, List.of(), firstStatus,
                new StopPlace(10L, "Çiya", PlaceCategory.BREAKFAST, null, null, 40.99, 29.02, 200, 4.5, true, null));
        StopResponse sights = new StopResponse(2L, 1, StopType.SIGHTSEEING, StopType.SIGHTSEEING.getLabel(),
                LocalTime.of(11, 0), LocalTime.of(12, 0), 500, 7, List.of(), StopStatus.PLANNED,
                new StopPlace(11L, "Moda Sahili", PlaceCategory.PARK, null, null, 40.98, 29.02, 0, 4.7, false, null));
        List<StopResponse> stops = firstStatus == StopStatus.PLANNED ? List.of(breakfast, sights) : List.of(breakfast);

        return new RouteResponse(1L, title, LocalDate.of(2026, 9, 27), RouteStatus.DRAFT, false, 40.99, 29.02, null,
                LocalTime.of(9, 0), LocalTime.of(22, 0), 2, 700, 400, 800, 12, WalkingTolerance.MEDIUM, List.of(),
                new WeatherSnapshot("CLEAR", 24.0, "The weather is clear, up to 24°C. Good for exploring."),
                List.of(), stops, null, null);
    }

    @Test
    void unknownPriceStopLineAndTotalInBothLanguages() {
        assertThat(composer.planCreated(osmRoute()))
                .contains("15:00 → Kahve: Kahve Durağı (fiyat bilgisi yok)")
                .contains("Toplam tahmini harcama (fiyatı bilinen duraklar): ~0 TL");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(composer.planCreated(osmRoute()))
                .contains("15:00 → Coffee: Kahve Durağı (no price info)")
                .contains("Estimated total spend (stops with known prices): ~0 TL");
    }

    // A one-stop route whose place has no known price (e.g. from OpenStreetMap)
    private static RouteResponse osmRoute() {
        StopResponse osmStop = new StopResponse(3L, 0, StopType.COFFEE, StopType.COFFEE.getLabel(),
                LocalTime.of(15, 0), LocalTime.of(15, 45), 300, 5, List.of(), StopStatus.PLANNED,
                new StopPlace(12L, "Kahve Durağı", PlaceCategory.CAFE, null, null, 40.99, 29.02, null, null, true, null));
        return new RouteResponse(1L, "R", LocalDate.of(2026, 9, 27), RouteStatus.DRAFT, false, 40.99, 29.02, null,
                LocalTime.of(15, 0), LocalTime.of(22, 0), 1, null, 0, 300, 5, WalkingTolerance.MEDIUM, List.of(),
                null, List.of(), List.of(osmStop), null, null);
    }

    @Test
    void fartherRecommendationsGetTheirOwnSection() {
        Recommendation near = recommendation("Yakın Kafe", List.of("Başlangıç noktana 300 m (~6 dk yürüme)"), null);
        Recommendation far = recommendation("Uzak Kafe",
                List.of("1,8 km uzakta (yürüyerek ~32 dk)", "Puanı 4.7"), "Puanı daha yüksek (4.7)");

        assertThat(composer.recommendations(List.of(near), List.of(far), StopType.COFFEE.getLabel()))
                .isEqualTo("""
                        Kahve için önerilerim:
                        1. Yakın Kafe — Başlangıç noktana 300 m (~6 dk yürüme)

                        Daha uygun ama sana yakın değil:
                        • Uzak Kafe — 1,8 km uzakta (yürüyerek ~32 dk) · Puanı daha yüksek (4.7)""");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(composer.recommendations(List.of(), List.of(far), StopType.COFFEE.getLabel()))
                .startsWith("I could not find a suitable coffee place open near you right now.")
                .contains("\n\nA better fit, but not close to you:\n• Uzak Kafe — ");
    }

    private static Recommendation recommendation(String name, List<String> reasons, String whyBetter) {
        NearbyPlaceResponse place = new NearbyPlaceResponse();
        place.setName(name);
        return new Recommendation(place, StopType.COFFEE, 80, reasons, whyBetter);
    }
}
