package com.nomi.wayfinder.photo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nomi.wayfinder.aiusage.AiUsageInterceptor;
import com.nomi.wayfinder.aiusage.AiUsageService;
import com.nomi.wayfinder.config.HttpClients;
import com.nomi.wayfinder.config.NomiProperties;
import org.springframework.http.MediaType;
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
    private final AiUsageService usage;

    public PhotoAiClient(NomiProperties nomiProperties, PhotoProperties photoProperties, AiUsageService usage,
                         AiUsageInterceptor interceptor) {
        this.properties = nomiProperties.ai();
        this.usage = usage;
        // Vision checks take longer than intent parsing: the photo read timeout
        this.restClient = HttpClients.aiService(properties, photoProperties.aiReadTimeout(), interceptor).build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    // This month's OpenAI budget (AI_MONTHLY_BUDGET_USD) is used up
    public boolean budgetReached() {
        return usage.budgetReached();
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
