package com.nomi.wayfinder.photo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend and the Python AI service (ai-service/app/photos.py, PhotoVerifyRequest / PhotoVerdict) speak
 * snake_case JSON; if either side renames a field, this test breaks.
 */
class PhotoAiContractTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void requestUsesTheAiServiceFieldNames() {
        String body = json.writeValueAsString(new PhotoAiClient.VerifyRequest("AAAA", "PLACE", "Moda Sahili", "PARK",
                "İstanbul", "Kadıköy", null));

        assertThat(body).contains("\"image_base64\":\"AAAA\"", "\"target_type\":\"PLACE\"", "\"name\":\"Moda Sahili\"",
                "\"category\":\"PARK\"", "\"city\":\"İstanbul\"", "\"district\":\"Kadıköy\"",
                "\"reference_image_url\":null");
    }

    @Test
    void backendReadsTheAiServiceVerdict() {
        PhotoAiClient.Verdict verdict = json.readValue("""
                {"relevant":true,"safe":true,"people_focused":false,"confidence":0.82,"reason":"Café interior"}
                """, PhotoAiClient.Verdict.class);

        assertThat(verdict).isEqualTo(new PhotoAiClient.Verdict(true, true, false, 0.82, "Café interior"));
    }
}
