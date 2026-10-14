package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.geo.GeoMath;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Is a route someone liked a good answer to a new request? Compared by what the request means (where, which stops,
 * when, how much walking, budget, interests), never by its wording, so it costs no LLM call.
 * <p>
 * Must hold: starts within {@link #MAX_START_METERS}, the same kinds of stops, starts within {@link #MAX_START_SHIFT}
 * of the asked time, walks no more than the user wants, and does not cost more per person than their budget.
 * Then the score is how much the interests overlap (Jaccard), at least {@link #MIN_INTEREST_OVERLAP}.
 */
public final class RouteSimilarity {

    public static final double MAX_START_METERS = 1500;
    static final Duration MAX_START_SHIFT = Duration.ofHours(2);
    static final double MIN_INTEREST_OVERLAP = 0.5;

    /**
     * One day plan in comparable terms.
     *
     * @param budgetPerPerson TL per person; for a request the user's limit, for a liked route its known cost
     *                        (null = no limit / unknown)
     */
    public record Shape(double latitude, double longitude, Collection<StopType> stops, Collection<String> interests,
                        Integer budgetPerPerson, WalkingTolerance walking, LocalTime start) {
    }

    private RouteSimilarity() {
    }

    /** 0..1, higher = closer; empty when the liked route does not answer the request. */
    public static OptionalDouble match(Shape wanted, Shape liked) {
        if (GeoMath.meters(wanted.latitude(), wanted.longitude(), liked.latitude(), liked.longitude()) > MAX_START_METERS) {
            return OptionalDouble.empty();
        }
        if (!kinds(wanted.stops()).equals(kinds(liked.stops()))) {
            return OptionalDouble.empty();
        }
        if (wanted.start() != null && liked.start() != null
                && Duration.between(wanted.start(), liked.start()).abs().compareTo(MAX_START_SHIFT) > 0) {
            return OptionalDouble.empty();
        }
        if (wanted.walking() != null && liked.walking() != null
                && liked.walking().ordinal() > wanted.walking().ordinal()) {
            return OptionalDouble.empty();
        }
        if (wanted.budgetPerPerson() != null && liked.budgetPerPerson() != null
                && liked.budgetPerPerson() > wanted.budgetPerPerson()) {
            return OptionalDouble.empty();
        }
        double overlap = overlap(wanted.interests(), liked.interests());
        return overlap < MIN_INTEREST_OVERLAP ? OptionalDouble.empty() : OptionalDouble.of(overlap);
    }

    private static Set<StopType> kinds(Collection<StopType> stops) {
        return stops == null || stops.isEmpty() ? EnumSet.noneOf(StopType.class) : EnumSet.copyOf(stops);
    }

    // Jaccard; no interests on either side = the same day
    static double overlap(Collection<String> a, Collection<String> b) {
        Set<String> left = a == null ? Set.of() : new HashSet<>(a);
        Set<String> right = b == null ? Set.of() : new HashSet<>(b);
        if (left.isEmpty() && right.isEmpty()) {
            return 1;
        }
        Set<String> both = new HashSet<>(left);
        both.retainAll(right);
        Set<String> either = new HashSet<>(left);
        either.addAll(right);
        return (double) both.size() / either.size();
    }
}
