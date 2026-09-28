package com.nomi.wayfinder.photo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Python AI service: POST {AI_SERVICE_URL}/v1/photos/verify (same X-API-Key as /v1/intent).
 * It runs OpenAI moderation (safety) and a vision model (does the photo show this place?).
 * Any error means "try again later": the photo stays PENDING, it is never approved without the check.
 */
@Component
public class PhotoAiClient {

    private final NomiProperties.Ai properties;
    private final RestClient restClient;

    public PhotoAiClient(NomiProperties nomiProperties, PhotoProperties photoProperties) {
        this.properties = nomiProperties.ai();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(photoProperties.aiReadTimeout());

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
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

    public Verdict verify(VerifyRequest request) {
        Verdict verdict = restClient.post()
                .uri("/v1/photos/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Verdict.class);
        if (verdict == null || verdict.relevant() == null || verdict.safe() == null
                || verdict.peopleFocused() == null || verdict.confidence() == null) {
            throw new IllegalStateException("AI service returned an incomplete photo verdict");
        }
        return verdict;
    }

    /**
     * @param imageBase64       512 px JPEG, base64 (no data: prefix)
     * @param targetType        "PLACE" or "DISTRICT"
     * @param category          the place's category (CAFE, MUSEUM, ...); null for districts
     * @param referenceImageUrl the place's known Wikimedia photo, null when it has none
     */
    public record VerifyRequest(
            @JsonProperty("image_base64") String imageBase64,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("name") String name,
            @JsonProperty("category") String category,
            @JsonProperty("city") String city,
            @JsonProperty("district") String district,
            @JsonProperty("reference_image_url") String referenceImageUrl
    ) {
    }

    public record Verdict(
            @JsonProperty("relevant") Boolean relevant,
            @JsonProperty("safe") Boolean safe,
            @JsonProperty("people_focused") Boolean peopleFocused,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("reason") String reason
    ) {
    }
}
