package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StopResponse;
import com.nomi.wayfinder.entity.StopStatus;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.i18n.TurkishSuffix;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Writes the assistant's answers (Turkish, or English for English requests) from real results only
 * (route, changes, recommendations). Later the AI service can rephrase these texts, but the facts in them come from here.
 * Stop lines always start with "HH:MM →" in both languages; the app relies on that format.
 */
@Component
public class ResponseComposer {

    public String planCreated(RouteResponse route) {
        return planCreated(route, null);
    }

    // "ünlü bir rota": one of the area's popular routes (its famous sights, by Wikipedia popularity)
    public String popularPlanCreated(RouteResponse route, String area) {
        String intro = area == null
                ? Texts.t("Buranın en bilinen yerlerinden geçen hazır bir rota seçtim. ",
                "I picked a ready route through the best-known sights here. ")
                : Texts.t(TurkishSuffix.genitive(area) + " en bilinen yerlerinden geçen hazır bir rota seçtim. ",
                "I picked a ready route through the best-known sights of " + area + ". ");
        return intro + planCreated(route, null);
    }

    // Asked for famous sights, but the area has no popular route (its sights are not known well enough yet)
    public String noPopularRoute(String area) {
        return area == null
                ? Texts.t("Burası için hazır bir ünlü yerler rotası bulamadım; tarihi yerlere öncelik veren bir plan yaptım. ",
                "I found no ready route of famous sights here, so I planned a day that favours historic places. ")
                : Texts.t(area + " için hazır bir ünlü yerler rotası bulamadım; tarihi yerlere öncelik veren bir plan yaptım. ",
                "I found no ready route of famous sights for " + area + ", so I planned a day that favours historic places. ");
    }

    /**
     * @param startArea the district / neighbourhood the user named and the route starts at ("Üsküdar"), or null
     */
    public String planCreated(RouteResponse route, String startArea) {
        String intro = startArea == null ? "" : Texts.t(
                TurkishSuffix.ablative(startArea) + " başlayan bir rota hazırladım. ",
                "I made a route starting in " + startArea + ". ");
        if (route.stops().isEmpty()) {
            if (startArea != null) {
                return Texts.t(startArea + " çevresinde bu koşullarla uygun bir rota oluşturamadım. ",
                        "I could not create a suitable route around " + startArea + " with these conditions. ")
                        + String.join(" ", route.notes());
            }
            return Texts.t("Bu koşullarla uygun bir rota oluşturamadım. ",
                    "I could not create a suitable route with these conditions. ") + String.join(" ", route.notes());
        }

        StringBuilder sb = new StringBuilder(intro);
        if (Texts.english()) {
            sb.append(route.title()).append(" is ready! I created a route with ")
                    .append(route.stops().size()).append(route.stops().size() == 1 ? " stop:\n" : " stops:\n");
        } else {
            sb.append(route.title()).append(" hazır! ")
                    .append(route.stops().size()).append(" duraklı bir rota oluşturdum:\n");
        }

        route.stops().forEach(stop -> sb.append(stopLine(stop)).append('\n'));

        // Unknown prices are not in the total, so the total must not look like the full cost
        boolean someUnknown = route.stops().stream().anyMatch(s -> s.place().estimatedCost() == null);
        boolean noneKnown = route.stops().stream().allMatch(s -> s.place().estimatedCost() == null);
        if (noneKnown) {
            // No "~0 TL": the route's note says the prices are unknown and the budget could not be used
            sb.append(Texts.t("\nToplam yürüme: ~" + route.totalWalkingMinutes() + " dk.",
                    "\nTotal walking: ~" + route.totalWalkingMinutes() + " min."));
            appendWeatherAndNotes(sb, route);
            return sb.toString().trim();
        }
        if (Texts.english()) {
            sb.append(String.format(Locale.ROOT, "\nEstimated total spend%s: ~%d TL (%d %s) · Total walking: ~%d min.",
                    someUnknown ? " (stops with known prices)" : "",
                    route.totalEstimatedCost(), route.partySize(), route.partySize() == 1 ? "person" : "people",
                    route.totalWalkingMinutes()));
        } else {
            sb.append(String.format(Locale.ROOT, "\nToplam tahmini harcama%s: ~%d TL (%d kişi) · Toplam yürüme: ~%d dk.",
                    someUnknown ? " (fiyatı bilinen duraklar)" : "",
                    route.totalEstimatedCost(), route.partySize(), route.totalWalkingMinutes()));
        }

        if (route.budget() != null) {
            sb.append(String.format(Locale.ROOT, Texts.t(" Bütçen: %d TL.", " Your budget: %d TL."), route.budget()));
        }
        appendWeatherAndNotes(sb, route);
        return sb.toString().trim();
    }

    public String routeChanged(RouteResponse route, List<String> changes) {
        StringBuilder sb = new StringBuilder(Texts.t("Rotanı güncelledim:\n", "I updated your route:\n"));
        changes.forEach(c -> sb.append("• ").append(c).append('\n'));

        route.stops().stream()
                .filter(s -> s.status() == StopStatus.PLANNED)
                .findFirst()
                .ifPresent(next -> sb.append(Texts.t("\nSıradaki durak: ", "\nNext stop: ")).append(stopLine(next)));

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
        return recommendations(recommendations, List.of(), typeLabel);
    }

    /**
     * Nearby suggestions, then (if any) places that suit the request better but are farther away.
     * A farther item's first reason is its distance line; whyBetter says what makes it the better fit.
     */
    public String recommendations(List<Recommendation> recommendations, List<Recommendation> farther, String typeLabel) {
        StringBuilder sb = new StringBuilder();
        if (recommendations.isEmpty()) {
            sb.append(Texts.english()
                    ? "I could not find a suitable " + typeLabel.toLowerCase(Locale.ROOT) + " place open near you right now."
                    : "Yakınında şu an açık ve uygun bir " + typeLabel.toLowerCase(Locale.forLanguageTag("tr-TR"))
                    + " mekanı bulamadım.");
        } else {
            sb.append(Texts.english()
                    ? "My suggestions for " + typeLabel.toLowerCase(Locale.ROOT) + ":\n"
                    : typeLabel + " için önerilerim:\n");
            for (int i = 0; i < recommendations.size(); i++) {
                Recommendation r = recommendations.get(i);
                sb.append(i + 1).append(". ").append(r.place().getName())
                        .append(" — ").append(String.join(" · ", r.reasons())).append('\n');
            }
        }

        if (!farther.isEmpty()) {
            sb.append(sb.charAt(sb.length() - 1) == '\n' ? "\n" : "\n\n")
                    .append(Texts.t("Daha uygun ama sana yakın değil:\n", "A better fit, but not close to you:\n"));
            for (Recommendation r : farther) {
                sb.append("• ").append(r.place().getName());
                if (!r.reasons().isEmpty()) {
                    sb.append(" — ").append(r.reasons().getFirst());
                }
                if (r.whyBetter() != null) {
                    sb.append(" · ").append(r.whyBetter());
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    public String noRoute() {
        // Each chat changes only its own route; a chat that has not planned one yet has none
        return Texts.t("Bu sohbette henüz bir rota yok. İstersen hemen bir tane oluşturalım: "
                        + "örneğin \"2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz\" yazabilirsin.",
                "There is no route in this chat yet. Let's create one: "
                        + "for example, write \"We are 2 people, our budget is 700 TL, we want breakfast and coffee\".");
    }

    public String help() {
        return Texts.t("Sana Türkiye'nin şehirlerinde gün planlama konusunda yardımcı olabilirim. Örneğin:\n"
                        + "• \"Kadıköy'de 500 TL'ye bir gün planla\"\n"
                        + "• \"Yarın Ankara'da Kızılay'dan başlayan bir rota\"\n"
                        + "• \"Yakında kahve öner\"\n"
                        + "• \"Çok yorulduk\" / \"Yağmur başladı\" / \"Burayı çıkar\"\n"
                        + "• \"Biraz daha tarihi yer ekle\"",
                "I can help you plan your day in cities all over Turkey. For example:\n"
                        + "• \"Plan a day in Kadıköy for 500 TL\"\n"
                        + "• \"Tomorrow in Izmir, a route starting in Alsancak\"\n"
                        + "• \"Recommend coffee nearby\"\n"
                        + "• \"We're tired\" / \"It started raining\" / \"Remove this place\"\n"
                        + "• \"Add some more historical places\"");
    }

    // "HH:MM → Type: Place (cost)" in both languages
    private static String stopLine(StopResponse stop) {
        // null = unknown price (not free), 0 = free
        String cost = stop.place().estimatedCost() == null
                ? Texts.t("fiyat bilgisi yok", "no price info")
                : stop.place().estimatedCost() == 0
                ? Texts.t("ücretsiz", "free")
                : Texts.english()
                ? "~" + stop.place().estimatedCost() + " TL per person"
                : "kişi başı ~" + stop.place().estimatedCost() + " TL";
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
        return status == StopStatus.VISITED ? Texts.t("gidildi", "visited") : Texts.t("atlandı", "skipped");
    }
}
