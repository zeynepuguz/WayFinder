package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.service.Interests;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scores one candidate place for one stop. Pure logic (no database), so it is easy to unit test.
 * Every bonus/penalty that matters also produces a human readable reason from real data.
 */
@Component
public class PlaceScorer {

    static final double DEFAULT_RATING = 3.8;

    public ScoredPlace score(Candidate c) {
        Place place = c.place();
        List<String> reasons = new ArrayList<>();
        double score = 0;

        // 1) Quality
        double rating = place.getRating() == null ? DEFAULT_RATING : place.getRating();
        score += rating * 20;

        // 2) Distance: closer is better, relative to how far the user is willing to walk
        score -= (c.distanceMeters() / c.maxLegMeters()) * 25;
        if (Texts.english()) {
            reasons.add(String.format(Locale.ROOT, "%d m from %s (~%d min walk)",
                    Math.round(c.distanceMeters()),
                    c.firstLeg() ? "your starting point" : "the previous stop", c.walkingMinutes()));
        } else {
            reasons.add(String.format(Locale.ROOT, "%s %d m (~%d dk yürüme)",
                    c.firstLeg() ? "Başlangıç noktana" : "Önceki durağa",
                    Math.round(c.distanceMeters()), c.walkingMinutes()));
        }

        if (place.getRating() != null) {
            reasons.add(String.format(Locale.ROOT, Texts.t("Puanı %.1f", "Rated %.1f"), place.getRating()));
        }

        // 3) Weather at the time of the visit
        WeatherContext weather = c.weather();
        if (!place.isIndoor()) {
            if (weather.wet()) {
                score -= 60;
            } else if (weather.windy()) {
                score -= place.hasTag("sea") ? 40 : 30;
            } else if (weather.hot()) {
                score -= 35;
            } else if (weather.cold()) {
                score -= 15;
            } else if (isEvening(c.arrival()) && (place.hasTag("sea") || place.hasTag("view"))) {
                score += 10;
                reasons.add(Texts.t("Akşam serinliğinde deniz/manzara keyfi", "Sea and views in the cool of the evening"));
            }
        } else if (weather.badForOutdoor()) {
            score += 10;
            reasons.add(Texts.t("Hava " + weather.reasonLabel() + " olduğu için kapalı mekan seçildi",
                    "Indoor place chosen because the weather is " + weather.reasonLabel()));
        }

        // 4) Interests (matched against place tags)
        List<String> matches = c.interests().stream().filter(place::hasTag).toList();
        if (!matches.isEmpty()) {
            score += 12 * Math.min(matches.size(), 2);
            reasons.add(Texts.t("İlgi alanına uygun: ", "Matches your interests: ") + String.join(", ", matches.stream().map(Interests::label).toList()));
        }

        // 5) Budget: reward places that leave room for the rest of the day
        int cost = c.totalCost();
        if (c.budgetAllowance() != null) {
            if (c.budgetAllowance() <= 0) {
                // Budget already used up: anything that costs money gets the full penalty
                score -= cost > 0 ? 20 : 0;
            } else {
                double ratio = cost / c.budgetAllowance();
                score += Math.max(-20, Math.min(10, 10 * (1 - ratio)));
            }
        }
        reasons.add(cost == 0 ? Texts.t("Ücretsiz", "Free") :
                String.format(Locale.ROOT, Texts.t("Kişi başı ~%d TL", "~%d TL per person"), place.getEstimatedCost()));

        // 6) Unknown opening hours are a small risk
        if (c.openStatus() == null) {
            score -= 3;
        } else {
            reasons.add(Texts.t("Bu saatte açık", "Open at this time"));
        }

        return new ScoredPlace(place, score, reasons);
    }

    private static boolean isEvening(LocalTime time) {
        return !time.isBefore(LocalTime.of(17, 30));
    }

    /**
     * @param budgetAllowance TL this stop may use for the whole party (null = no budget)
     * @param openStatus      true = open, null = unknown (false is filtered out before scoring)
     */
    public record Candidate(
            Place place,
            double distanceMeters,
            double maxLegMeters,
            int walkingMinutes,
            boolean firstLeg,
            LocalTime arrival,
            WeatherContext weather,
            List<String> interests,
            int totalCost,
            Double budgetAllowance,
            Boolean openStatus
    ) {
    }

    public record ScoredPlace(Place place, double score, List<String> reasons) {
    }
}
