package com.nomi.wayfinder.config;

import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builders for the RestClients the app calls other services with (Overpass, Wikimedia, Overture, Google, Open-Meteo,
 * the Python AI service). Every one has its own timeouts: a slow outside service must never make a request hang.
 */
public final class HttpClients {

    private HttpClients() {
    }

    // JDK HttpURLConnection with these timeouts; the caller adds base URL / headers and builds it
    public static RestClient.Builder restClient(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * The Python AI service: its base URL (when enabled) and X-API-Key (when set). Our request id is forwarded as
     * X-Request-Id, so one user request can be followed in both services' logs. usage records every call's
     * OpenAI tokens (AiUsageInterceptor).
     */
    public static RestClient.Builder aiService(NomiProperties.Ai ai, Duration readTimeout,
                                               ClientHttpRequestInterceptor usage) {
        RestClient.Builder builder = restClient(ai.connectTimeout(), readTimeout)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get("requestId");
                    if (requestId != null) {
                        request.getHeaders().add("X-Request-Id", requestId);
                    }
                    return execution.execute(request, body);
                })
                .requestInterceptor(usage);
        if (ai.enabled()) {
            builder.baseUrl(ai.baseUrl());
        }
        if (ai.apiKey() != null && !ai.apiKey().isBlank()) {
            builder.defaultHeader("X-API-Key", ai.apiKey());
        }
        return builder;
    }
}
