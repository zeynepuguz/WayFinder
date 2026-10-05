package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.config.HttpClients;
import com.nomi.wayfinder.config.NomiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls the Python FastAPI AI service: POST {AI_SERVICE_URL}/v1/intent
 *
 * Request:  { "message": "...", "context": { "hasRoute": true, "remainingStops": [...] } }
 * Response: an AssistantIntent JSON object (date = "yyyy-MM-dd" or null, area = district / neighbourhood text or null)
 */
@Component
public class AiServiceIntentParser implements IntentParser {

    private final NomiProperties.Ai properties;
    private final RestClient restClient;

    public AiServiceIntentParser(NomiProperties nomiProperties) {
        this.properties = nomiProperties.ai();
        this.restClient = HttpClients.aiService(properties, properties.readTimeout()).build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    @Override
    public AssistantIntent parse(String message, IntentContext context) {
        AssistantIntent intent = restClient.post()
                .uri("/v1/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new IntentRequest(message, context))
                .retrieve()
                .body(AssistantIntent.class);

        if (intent == null || intent.type() == null) {
            throw new IllegalStateException("AI service returned an empty intent");
        }

        return new AssistantIntent(
                intent.type(),
                intent.plan(),
                intent.edits() == null ? List.of() : intent.edits(),
                intent.recommendType(),
                "ai",
                intent.date(),
                intent.area() == null || intent.area().isBlank() ? null : intent.area().trim()
        );
    }

    record IntentRequest(String message, IntentContext context) {
    }
}
