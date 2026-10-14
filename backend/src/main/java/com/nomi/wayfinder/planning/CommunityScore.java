package com.nomi.wayfinder.planning;

/**
 * What Nomi's own users think of a place, as numbers the planner and the lists can use.
 * <p>
 * Rating: a Bayesian average. Every place starts as if it already had {@link #PRIOR_VOTES} votes at its prior (its
 * hand-checked rating, else {@link #DEFAULT_RATING}), so one 5-star vote cannot lift a place above one that thirty
 * people liked: 1 vote of 5 -> 4.0, 30 votes of 5 -> 4.8.
 * <p>
 * Places.java sorts lists with the same formula in SQL (Place.communityScore); keep the two in step.
 */
public final class CommunityScore {

    public static final double DEFAULT_RATING = 3.8;
    public static final int PRIOR_VOTES = 5;
    // "Kullanıcı puanı 4.6 (12 oy)" is only shown from this many votes on; below it the number says little
    public static final int SHOWN_FROM_VOTES = 3;
    // A place this many users saved, or this many rated this well, is one of the area's favourites
    public static final int FAVORITE_FROM_SAVES = 5;
    public static final int FAVORITE_FROM_VOTES = 5;
    public static final double FAVORITE_MIN_AVERAGE = 4.3;
    // Planner bonus for saves: grows slowly (5 saves ~4.5, 20 saves ~7.6), never above the cap
    static final double SAVES_WEIGHT = 2.5;
    static final double SAVES_BONUS_CAP = 10;

    private CommunityScore() {
    }

    /** 1..5: the prior pulled towards the users' average as votes come in. */
    public static double rating(Double ownRating, int ratingSum, int ratingCount) {
        double prior = ownRating == null ? DEFAULT_RATING : ownRating;
        int votes = Math.max(0, ratingCount);
        return (prior * PRIOR_VOTES + Math.max(0, ratingSum)) / (PRIOR_VOTES + votes);
    }

    /** The plain average of the users' votes; null without votes. */
    public static Double average(int ratingSum, int ratingCount) {
        return ratingCount <= 0 ? null : Math.round(ratingSum * 10.0 / ratingCount) / 10.0;
    }

    /** "Bölgenin favorisi": at least 5 users rated it 4.3 or better on average, or at least 5 users saved it. */
    public static boolean isFavorite(int ratingSum, int ratingCount, int favoriteCount) {
        boolean wellRated = ratingCount >= FAVORITE_FROM_VOTES
                && (double) ratingSum / ratingCount >= FAVORITE_MIN_AVERAGE;
        return wellRated || favoriteCount >= FAVORITE_FROM_SAVES;
    }

    public static double savesBonus(int favoriteCount) {
        return favoriteCount <= 0 ? 0 : Math.min(SAVES_BONUS_CAP, SAVES_WEIGHT * Math.log1p(favoriteCount));
    }
}
