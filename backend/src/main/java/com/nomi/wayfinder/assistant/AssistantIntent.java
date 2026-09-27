package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;

import java.time.LocalTime;
import java.util.List;

/**
 * What the user wants, in a structured form the backend can execute.
 * This is also the JSON contract of the Python AI service (POST /v1/intent), so the LLM
 * only has to fill this object; it never picks places, prices or distances itself.
 */
public record AssistantIntent(
        IntentType type,
        PlanParams plan,
        List<RouteEdit> edits,
        StopType recommendType,
        String source
) {

    public enum IntentType {
        PLAN_ROUTE,
        REPLAN,
        RECOMMEND,
        WEATHER,
        SHOW_ROUTE,
        UNKNOWN
    }

    public record PlanParams(
            Integer partySize,
            Integer budget,
            WalkingTolerance walkingTolerance,
            List<StopType> stops,
            List<String> interests,
            LocalTime startTime
    ) {
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
