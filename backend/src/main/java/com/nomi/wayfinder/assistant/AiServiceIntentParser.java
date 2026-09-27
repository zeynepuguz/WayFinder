package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls the Python FastAPI AI service: POST {AI_SERVICE_URL}/v1/intent
 *
 * Request:  { "message": "...", "context": { "hasRoute": true, "remainingStops": [...] } }
 * Response: an AssistantIntent JSON object
 */
@Component
public class AiServiceIntentParser implements IntentParser {

    private final NomiProperties.Ai properties;
    private final RestClient restClient;

    public AiServiceIntentParser(NomiProperties nomiProperties) {
        this.properties = nomiProperties.ai();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                // Forward our request id so one user request can be followed in both services' logs
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get("requestId");
                    if (requestId != null) {
                        request.getHeaders().add("X-Request-Id", requestId);
                    }
                    return execution.execute(request, body);
                });
        if (properties.enabled()) {
            builder.baseUrl(properties.baseUrl());
        }
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.defaultHeader("X-API-Key", properties.apiKey());
        }
        this.restClient = builder.build();
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
                "ai"
        );
    }

    record IntentRequest(String message, IntentContext context) {
    }
}
