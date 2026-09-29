package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * What the user wants, in a structured form the backend can execute.
 * This is also the JSON contract of the Python AI service (POST /v1/intent), so the LLM
 * only has to fill this object; it never picks places, prices or distances itself.
 *
 * @param date the day the user means ("yarın", "cumartesi", "28 Eylül"); null = today
 * @param area the city, district or neighbourhood the user named, as written ("üsküdarda", "Ankara Kızılay"); null = none.
 *             AreaResolver turns it into a point
 */
public record AssistantIntent(
        IntentType type,
        PlanParams plan,
        List<RouteEdit> edits,
        StopType recommendType,
        String source,
        LocalDate date,
        String area
) {

    public AssistantIntent(IntentType type, PlanParams plan, List<RouteEdit> edits, StopType recommendType,
                           String source) {
        this(type, plan, edits, recommendType, source, null, null);
    }

    public AssistantIntent withDateAndArea(LocalDate date, String area) {
        return new AssistantIntent(type, plan, edits, recommendType, source, date, area);
    }

    public enum IntentType {
        PLAN_ROUTE,
        REPLAN,
        RECOMMEND,
        WEATHER,
        SHOW_ROUTE,
        UNKNOWN
    }

    /**
     * @param budget  TL for the whole group (a per-person budget is already multiplied by partySize)
     * @param popular the user asked for the famous / must-see sights ("ünlü bir rota"): a popular route of the area
     */
    public record PlanParams(
            Integer partySize,
            Integer budget,
            WalkingTolerance walkingTolerance,
            List<StopType> stops,
            List<String> interests,
            LocalTime startTime,
            Boolean popular
    ) {

        public PlanParams(Integer partySize, Integer budget, WalkingTolerance walkingTolerance, List<StopType> stops,
                          List<String> interests, LocalTime startTime) {
            this(partySize, budget, walkingTolerance, stops, interests, startTime, null);
        }

        public boolean wantsPopular() {
            return Boolean.TRUE.equals(popular);
        }
    }

    /**
     * One change to the current route.
     *
     * @param targetText      free text that names a stop ("Çiya", "burayı")
     * @param targetStopType  stop named by its type ("akşam yemeğini çıkar")
     * @param targetIsCurrent "burayı" / "bunu" = the next planned stop
     */
    public record RouteEdit(
            ReplanType type,
            StopType stopType,
            String interest,
            String targetText,
            StopType targetStopType,
            boolean targetIsCurrent
    ) {

        public static RouteEdit of(ReplanType type) {
            return new RouteEdit(type, null, null, null, null, false);
        }
    }
}
