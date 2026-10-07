package com.nomi.wayfinder.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
                return aiParser.parse(message, context);
            } catch (Exception e) {
                log.warn("AI intent parsing failed, falling back to rules: {}", e.getMessage());
            }
        }
        return ruleParser.parse(message, context);
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? "user:" + auth.getName() : "anonymous";
    }
}
