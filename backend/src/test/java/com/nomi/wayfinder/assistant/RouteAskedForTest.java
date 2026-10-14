package com.nomi.wayfinder.assistant;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// "... bir rota öner" is a route even when the LLM calls it a suggestion (FallbackIntentParser.routeAskedFor)
class RouteAskedForTest {

    private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-14T10:13:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final IntentParser.IntentContext NO_ROUTE = new IntentParser.IntentContext(false, List.of());

    private final FallbackIntentParser parser = new FallbackIntentParser(mock(AiServiceIntentParser.class),
            new RuleBasedIntentParser(NOON), mock(AiUsageLimiter.class));

    private static AssistantIntent ai(AssistantIntent.IntentType type) {
        return new AssistantIntent(type, null, List.of(), null, "ai", null, "İstanbul");
    }

    @Test
    void aRouteReadAsASuggestionIsPlanned() {
        AssistantIntent result = parser.routeAskedFor(ai(AssistantIntent.IntentType.RECOMMEND),
                "İstanbul için bir rota öner", NO_ROUTE);

        assertThat(result.type()).isEqualTo(AssistantIntent.IntentType.PLAN_ROUTE);
        assertThat(result.area()).isEqualTo("İstanbul");
    }

    @Test
    void aSuggestionOfPlacesStaysASuggestion() {
        assertThat(parser.routeAskedFor(ai(AssistantIntent.IntentType.RECOMMEND), "yakında kahve öner", NO_ROUTE).type())
                .isEqualTo(AssistantIntent.IntentType.RECOMMEND);
        // A change of the current route is not a new plan
        assertThat(parser.routeAskedFor(ai(AssistantIntent.IntentType.REPLAN), "rotama tatlı ekle", NO_ROUTE).type())
                .isEqualTo(AssistantIntent.IntentType.REPLAN);
    }
}
