package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
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
    // With a budget, a place of unknown price is slightly less attractive than one known to fit
    static final double UNKNOWN_PRICE_PENALTY = 4;
    // Interest matches: the first counts a lot (it must beat a small distance advantage), a second one a bit more
    static final double INTEREST_BONUS = 30;
    static final double SECOND_INTEREST_BONUS = 12;
    static final double CAFE_MEAL_BUDGET_BONUS = 12;
    static final double KNOWN_PRICE_FITS_BONUS = 8;

    public ScoredPlace score(Candidate c) {
        Place place = c.place();
        List<String> reasons = new ArrayList<>();
        double score = 0;

        // 1) Quality
        double rating = place.getRating() == null ? DEFAULT_RATING : place.getRating();
        score += rating * 20;

        // 2) Distance: closer is better, relative to how far the user is willing to walk.
        // Kept separate so "fit" (everything but distance) can be compared across distances.
        double distancePenalty = (c.distanceMeters() / c.maxLegMeters()) * 25;
        reasons.add(distanceReason(c.distanceMeters(), c.walkingMinutes(), c.firstLeg()));

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

        // 4) Interests (planning/InterestMatcher: tags, category, near the sea). Strong on purpose: a place that
        // matches what the user asked for must beat one that is only a few hundred meters closer
        List<String> matches = InterestMatcher.matching(place, c.interests());
        if (!matches.isEmpty()) {
            score += INTEREST_BONUS + SECOND_INTEREST_BONUS * Math.min(matches.size() - 1, 1);
            reasons.add(Texts.t("İlgi alanına uygun: ", "Matches your interests: ")
                    + String.join(", ", matches.stream().map(Interests::label).toList()));
            if (matches.contains("sea") && place.isNearSea() && !place.hasTag("sea")) {
                reasons.add(Texts.t("Deniz kenarında", "By the sea"));
            }
        }
        // "Uygun fiyat" without known prices: a café is the cheaper meal (never a guessed price)
        boolean budgetInterest = c.interests() != null && c.interests().contains("budget");
        if (budgetInterest && matches.stream().noneMatch("budget"::equals) && c.slotType() != null
                && (c.slotType() == StopType.LUNCH || c.slotType() == StopType.DINNER)
                && place.getCategory() == PlaceCategory.CAFE && place.getEstimatedCost() == null) {
            score += CAFE_MEAL_BUDGET_BONUS;
            reasons.add(Texts.t("Uygun fiyat için restoran yerine kafe", "A café instead of a restaurant, to keep it cheap"));
        }

        // 5) Budget: reward places that leave room for the rest of the day.
        // Unknown price (null, e.g. OpenStreetMap places) is not free: with a budget it gets a small
        // penalty instead of a bonus, because we cannot tell whether it fits.
        Integer cost = c.totalCost();
        if (c.budgetAllowance() != null) {
            if (cost == null) {
                score -= UNKNOWN_PRICE_PENALTY;
            } else if (c.budgetAllowance() <= 0) {
                // Budget already used up: anything that costs money gets the full penalty
                score -= cost > 0 ? 20 : 0;
            } else {
                double ratio = cost / c.budgetAllowance();
                score += Math.max(-20, Math.min(10, 10 * (1 - ratio)));
                // A known price that fits ranks above an unknown one
                if (cost <= c.budgetAllowance()) {
                    score += KNOWN_PRICE_FITS_BONUS;
                }
            }
        }
        reasons.add(cost == null ? Texts.t("Fiyat bilgisi yok", "No price info")
                : cost == 0 ? Texts.t("Ücretsiz", "Free") :
                String.format(Locale.ROOT, Texts.t("Kişi başı ~%d TL", "~%d TL per person"), place.getEstimatedCost()));

        // 6) Unknown opening hours are a small risk
        if (c.openStatus() == null) {
            score -= 3;
        } else {
            reasons.add(Texts.t("Bu saatte açık", "Open at this time"));
        }

        return new ScoredPlace(place, score - distancePenalty, score, reasons);
    }

    // The first reason of every scored place: "Önceki durağa 350 m (~7 dk yürüme)"
    public static String distanceReason(double distanceMeters, int walkingMinutes, boolean firstLeg) {
        if (Texts.english()) {
            return String.format(Locale.ROOT, "%d m from %s (~%d min walk)", Math.round(distanceMeters),
                    firstLeg ? "your starting point" : "the previous stop", walkingMinutes);
        }
        return String.format(Locale.ROOT, "%s %d m (~%d dk yürüme)", firstLeg ? "Başlangıç noktana" : "Önceki durağa",
                Math.round(distanceMeters), walkingMinutes);
    }

    private static boolean isEvening(LocalTime time) {
        return !time.isBefore(LocalTime.of(17, 30));
    }

    /**
     * @param totalCost       TL for the whole party; null = price unknown (0 = free)
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
            Integer totalCost,
            Double budgetAllowance,
            Boolean openStatus,
            // The stop being filled (null = a single recommendation): "uygun fiyat" meals may be cafés
            StopType slotType
    ) {

        public Candidate(Place place, double distanceMeters, double maxLegMeters, int walkingMinutes, boolean firstLeg,
                         LocalTime arrival, WeatherContext weather, List<String> interests, Integer totalCost,
                         Double budgetAllowance, Boolean openStatus) {
            this(place, distanceMeters, maxLegMeters, walkingMinutes, firstLeg, arrival, weather, interests, totalCost,
                    budgetAllowance, openStatus, null);
        }
    }

    /**
     * @param score    everything, including distance; the route planner picks by this
     * @param fitScore the same without the distance term: how well the place itself suits the request
     */
    public record ScoredPlace(Place place, double score, double fitScore, List<String> reasons) {
    }
}
