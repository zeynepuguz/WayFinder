package com.nomi.wayfinder.billing;

import com.google.auth.oauth2.GoogleCredentials;
import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * Asks Google whether an in-app purchase is real (Google Play Developer API, purchases.products).
 * The app never decides that a purchase succeeded; only this server-side check unlocks access.
 *
 * Needs a Google Cloud service account that has access to the app in Play Console,
 * with its JSON key file path in GOOGLE_PLAY_SERVICE_ACCOUNT_FILE.
 */
@Component
public class GooglePlayClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlayClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";

    private final String packageName;
    private final GoogleCredentials credentials;
    private final RestClient restClient;

    public GooglePlayClient(NomiProperties properties) {
        NomiProperties.Billing billing = properties.billing();
        this.packageName = billing.googlePlayPackageName();
        this.credentials = loadCredentials(billing.googlePlayServiceAccountFile());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl("https://androidpublisher.googleapis.com/androidpublisher/v3")
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return credentials != null;
    }

    public ProductPurchase getProductPurchase(String productId, String purchaseToken) {
        return restClient.get()
                .uri("/applications/{package}/purchases/products/{product}/tokens/{token}",
                        packageName, productId, purchaseToken)
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(ProductPurchase.class);
    }

    // Google refunds purchases that are not acknowledged within 3 days
    public void acknowledge(String productId, String purchaseToken) {
        restClient.post()
                .uri("/applications/{package}/purchases/products/{product}/tokens/{token}:acknowledge",
                        packageName, productId, purchaseToken)
                .header("Authorization", "Bearer " + accessToken())
                .body("{}")
                .retrieve()
                .toBodilessEntity();
    }

    private String accessToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("Could not get a Google access token", e);
        }
    }

    private static GoogleCredentials loadCredentials(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return GoogleCredentials.fromStream(in).createScoped(List.of(SCOPE));
        } catch (IOException e) {
            log.error("Could not read Google Play service account file {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Subset of https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products
     *
     * @param purchaseState        0 = purchased, 1 = canceled, 2 = pending
     * @param acknowledgementState 0 = not acknowledged, 1 = acknowledged
     */
    public record ProductPurchase(
            Integer purchaseState,
            Integer consumptionState,
            Integer acknowledgementState,
            String orderId,
            String purchaseTimeMillis,
            String obfuscatedExternalAccountId,
            String regionCode
    ) {
    }
}
