package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.i18n.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

// Uses the AI service when configured, healthy, within the monthly budget and the user's daily quota,
// otherwise the rule-based parser
@Component
@Primary
public class FallbackIntentParser implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(FallbackIntentParser.class);

    private final AiServiceIntentParser aiParser;
    private final RuleBasedIntentParser ruleParser;
    private final AiUsageLimiter usageLimiter;

    public FallbackIntentParser(AiServiceIntentParser aiParser, RuleBasedIntentParser ruleParser,
                                AiUsageLimiter usageLimiter) {
        this.aiParser = aiParser;
        this.ruleParser = ruleParser;
        this.usageLimiter = usageLimiter;
    }

    @Override
    public AssistantIntent parse(String message, IntentContext context) {
        if (aiParser.isEnabled() && !aiParser.budgetReached() && usageLimiter.tryAcquire(currentUser())) {
            try {
                return routeAskedFor(aiParser.parse(message, context), message, context);
            } catch (Exception e) {
                log.warn("AI intent parsing failed, falling back to rules: {}", e.getMessage());
            }
        }
        return ruleParser.parse(message, context);
    }

    /**
     * "İstanbul için bir rota öner" is a route, not a suggestion of places: when the LLM reads a message naming a
     * route as RECOMMEND / UNKNOWN and the rules see a new plan in it, it is planned (with the LLM's area and date).
     */
    AssistantIntent routeAskedFor(AssistantIntent ai, String message, IntentContext context) {
        boolean notAPlan = ai.type() == AssistantIntent.IntentType.RECOMMEND
                || ai.type() == AssistantIntent.IntentType.UNKNOWN;
        if (!notAPlan || !namesARoute(message)) {
            return ai;
        }
        AssistantIntent rules = ruleParser.parse(message, context);
        if (rules.type() != AssistantIntent.IntentType.PLAN_ROUTE) {
            return ai;
        }
        return new AssistantIntent(AssistantIntent.IntentType.PLAN_ROUTE, ai.plan() != null ? ai.plan() : rules.plan(),
                List.of(), null, ai.source(), ai.date() != null ? ai.date() : rules.date(),
                ai.area() != null ? ai.area() : rules.area());
    }

    static boolean namesARoute(String message) {
        String text = message == null ? "" : message.toLowerCase(Texts.TURKISH);
        return text.contains("rota") || text.contains("route") || text.contains("itinerary");
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? "user:" + auth.getName() : "anonymous";
    }
}
