package com.nomi.wayfinder.google;

import com.nomi.wayfinder.config.HttpClients;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Places API (New) Text Search: the places Google knows by this name around a point, with whether they are still
 * open. Only the fields asked for are billed (X-Goog-FieldMask). Nothing Google returns is stored except what the
 * place page shows at that moment and our own decision to hide a closed place.
 */
@Component
public class GooglePlacesClient {

    static final String SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";
    static final String FIELD_MASK = "places.id,places.displayName,places.businessStatus,places.location";

    /**
     * @param status OPERATIONAL, CLOSED_TEMPORARILY, CLOSED_PERMANENTLY or null (unknown)
     */
    public record GooglePlace(String id, String name, String status, double latitude, double longitude) {
    }

    private final GooglePlacesProperties properties;
    private final RestClient restClient;

    public GooglePlacesClient(GooglePlacesProperties properties) {
        this.properties = properties;
        Duration timeout = properties.timeout() == null ? Duration.ofSeconds(5) : properties.timeout();
        this.restClient = HttpClients.restClient(timeout, timeout).build();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    @SuppressWarnings("unchecked")
    public List<GooglePlace> search(String name, double latitude, double longitude) {
        Map<String, Object> body = Map.of(
                "textQuery", name,
                "languageCode", "tr",
                "maxResultCount", 5,
                "locationBias", Map.of("circle", Map.of(
                        "center", Map.of("latitude", latitude, "longitude", longitude),
                        "radius", (double) properties.radiusMeters())));
        Map<String, Object> response = restClient.post()
                .uri(SEARCH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", FIELD_MASK)
                .body(body)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> places = response == null || response.get("places") == null ? List.of()
                : (List<Map<String, Object>>) response.get("places");
        return places.stream().map(GooglePlacesClient::toPlace).filter(p -> p.id() != null).toList();
    }

    @SuppressWarnings("unchecked")
    static GooglePlace toPlace(Map<String, Object> place) {
        Map<String, Object> displayName = (Map<String, Object>) place.getOrDefault("displayName", Map.of());
        Map<String, Object> location = (Map<String, Object>) place.getOrDefault("location", Map.of());
        return new GooglePlace((String) place.get("id"), (String) displayName.get("text"),
                (String) place.get("businessStatus"),
                ((Number) location.getOrDefault("latitude", 0)).doubleValue(),
                ((Number) location.getOrDefault("longitude", 0)).doubleValue());
    }
}
