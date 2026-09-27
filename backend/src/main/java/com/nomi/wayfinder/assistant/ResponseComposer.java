package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StopResponse;
import com.nomi.wayfinder.entity.StopStatus;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Writes the assistant's Turkish answers from real results only (route, changes, recommendations).
 * Later the AI service can rephrase these texts, but the facts in them come from here.
 */
@Component
public class ResponseComposer {

    public String planCreated(RouteResponse route) {
        if (route.stops().isEmpty()) {
            return "Bu koşullarla uygun bir rota oluşturamadım. " + String.join(" ", route.notes());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(route.title()).append(" hazır! ")
                .append(route.stops().size()).append(" duraklı bir rota oluşturdum:\n");

        route.stops().forEach(stop -> sb.append(stopLine(stop)).append('\n'));

        sb.append(String.format(Locale.ROOT, "\nToplam tahmini harcama: ~%d TL (%d kişi) · Toplam yürüme: ~%d dk.",
                route.totalEstimatedCost(), route.partySize(), route.totalWalkingMinutes()));

        if (route.budget() != null) {
            sb.append(String.format(Locale.ROOT, " Bütçen: %d TL.", route.budget()));
        }
        appendWeatherAndNotes(sb, route);
        return sb.toString().trim();
    }

    public String routeChanged(RouteResponse route, List<String> changes) {
        StringBuilder sb = new StringBuilder("Rotanı güncelledim:\n");
        changes.forEach(c -> sb.append("• ").append(c).append('\n'));

        route.stops().stream()
                .filter(s -> s.status() == StopStatus.PLANNED)
                .findFirst()
                .ifPresent(next -> sb.append("\nSıradaki durak: ").append(stopLine(next)));

        if (!route.notes().isEmpty()) {
            sb.append("\n\n").append(String.join(" ", route.notes()));
        }
        return sb.toString().trim();
    }

    public String showRoute(RouteResponse route) {
        StringBuilder sb = new StringBuilder(route.title()).append(":\n");
        route.stops().forEach(stop -> sb.append(stopLine(stop))
                .append(stop.status() == StopStatus.PLANNED ? "" : " (" + statusLabel(stop.status()) + ")")
                .append('\n'));
        return sb.toString().trim();
    }

    public String recommendations(List<Recommendation> recommendations, String typeLabel) {
        if (recommendations.isEmpty()) {
            return "Yakınında şu an açık ve uygun bir " + typeLabel.toLowerCase(Locale.forLanguageTag("tr-TR"))
                    + " mekanı bulamadım.";
        }

        StringBuilder sb = new StringBuilder(typeLabel + " için önerilerim:\n");
        for (int i = 0; i < recommendations.size(); i++) {
            Recommendation r = recommendations.get(i);
            sb.append(i + 1).append(". ").append(r.place().getName())
                    .append(" — ").append(String.join(" · ", r.reasons())).append('\n');
        }
        return sb.toString().trim();
    }

    public String noRoute() {
        return "Şu an aktif bir rotan yok. İstersen hemen bir tane oluşturalım: "
                + "örneğin \"2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz\" yazabilirsin.";
    }

    public String help() {
        return "Sana şehirde gün planlama konusunda yardımcı olabilirim. Örneğin:\n"
                + "• \"Kadıköy'de 500 TL'ye bir gün planla\"\n"
                + "• \"Yakında kahve öner\"\n"
                + "• \"Çok yorulduk\" / \"Yağmur başladı\" / \"Burayı çıkar\"\n"
                + "• \"Biraz daha tarihi yer ekle\"";
    }

    private static String stopLine(StopResponse stop) {
        String cost = stop.place().estimatedCost() == null || stop.place().estimatedCost() == 0
                ? "ücretsiz" : "kişi başı ~" + stop.place().estimatedCost() + " TL";
        return String.format(Locale.ROOT, "%s → %s: %s (%s)",
                stop.plannedStart(), stop.typeLabel(), stop.place().name(), cost);
    }

    private static void appendWeatherAndNotes(StringBuilder sb, RouteResponse route) {
        if (route.weather() != null && route.weather().advice() != null) {
            sb.append("\n\n").append(route.weather().advice());
        }
        if (!route.notes().isEmpty()) {
            sb.append("\n\n").append(String.join(" ", route.notes()));
        }
    }

    private static String statusLabel(StopStatus status) {
        return status == StopStatus.VISITED ? "gidildi" : "atlandı";
    }
}
