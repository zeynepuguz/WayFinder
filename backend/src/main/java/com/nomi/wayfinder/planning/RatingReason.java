package com.nomi.wayfinder.planning;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answers to "Neden bu puanı verdin?" after rating a food or drink place. Fixed answers instead of free text:
 * nothing to moderate, no LLM needed, every user says the same thing the same way.
 * <p>
 * Some answers stand for a place tag (the interest they serve): when enough users agree, the place gets it as a
 * community tag and the planner matches interests against it ({@link #communityTags}).
 */
public enum RatingReason {

    // Liked (4-5 stars, and 3)
    TASTY(true, null),
    AFFORDABLE(true, "budget"),
    VIEW(true, "view"),
    LOCAL_FLAVOURS(true, "local"),
    GREAT_COFFEE(true, "coffee"),
    GREAT_BREAKFAST(true, "breakfast"),
    GREAT_DESSERT(true, "dessert"),
    FAMILY_FRIENDLY(true, "family"),
    QUIET(true, "quiet"),
    FRIENDLY_STAFF(true, null),
    CLEAN(true, null),

    // Disliked (1-2 stars, and 3)
    NOT_TASTY(false, null),
    EXPENSIVE(false, "pricey"),
    CROWDED_NOISY(false, null),
    SLOW_SERVICE(false, null),
    NOT_CLEAN(false, null),
    UNFRIENDLY_STAFF(false, null);

    // A tag needs this many users more for it than against it ...
    static final int AGREEING_USERS = 3;
    // ... and at least this share of everyone who rated the place
    static final double AGREEING_SHARE = 1.0 / 3;
    public static final int MAX_PER_RATING = 4;
    // Answers that contradict each other: each one's votes count against the other's tag
    private static final Map<RatingReason, RatingReason> OPPOSITE = Map.of(
            AFFORDABLE, EXPENSIVE, EXPENSIVE, AFFORDABLE,
            QUIET, CROWDED_NOISY, CROWDED_NOISY, QUIET);

    private final boolean positive;
    private final String tag;

    RatingReason(boolean positive, String tag) {
        this.positive = positive;
        this.tag = tag;
    }

    public boolean positive() {
        return positive;
    }

    public String tag() {
        return tag;
    }

    /** 4-5 stars: what was good; 1-2: what was not; 3: either. */
    public boolean fits(int stars) {
        return stars == 3 || positive == (stars >= 4);
    }

    public static List<RatingReason> forStars(int stars) {
        return Arrays.stream(values()).filter(r -> r.fits(stars)).toList();
    }

    /**
     * The tags enough users agree on: a reason's votes minus its opposite's reach {@link #AGREEING_USERS} and at
     * least a third of the place's raters chose it.
     *
     * @param votes  users per reason at one place
     * @param raters users who rated the place
     */
    public static Set<String> communityTags(Map<RatingReason, Integer> votes, int raters) {
        Set<String> tags = new HashSet<>();
        for (RatingReason reason : values()) {
            if (reason.tag == null) {
                continue;
            }
            int forIt = votes.getOrDefault(reason, 0);
            RatingReason opposite = OPPOSITE.get(reason);
            int against = opposite == null ? 0 : votes.getOrDefault(opposite, 0);
            if (forIt - against >= AGREEING_USERS && forIt >= raters * AGREEING_SHARE) {
                tags.add(reason.tag);
            }
        }
        return tags;
    }
}
