package com.nomi.wayfinder.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

// Uses the AI service when configured and healthy, otherwise the rule-based parser
@Component
@Primary
public class FallbackIntentParser implements IntentParser {

    private static final Logger log = LoggerFactory.getLogger(FallbackIntentParser.class);

    private final AiServiceIntentParser aiParser;
    private final RuleBasedIntentParser ruleParser;

    public FallbackIntentParser(AiServiceIntentParser aiParser, RuleBasedIntentParser ruleParser) {
        this.aiParser = aiParser;
        this.ruleParser = ruleParser;
    }

    @Override
    public AssistantIntent parse(String message, IntentContext context) {
        if (aiParser.isEnabled()) {
            try {
                return aiParser.parse(message, context);
            } catch (Exception e) {
                log.warn("AI intent parsing failed, falling back to rules: {}", e.getMessage());
            }
        }
        return ruleParser.parse(message, context);
    }
}
