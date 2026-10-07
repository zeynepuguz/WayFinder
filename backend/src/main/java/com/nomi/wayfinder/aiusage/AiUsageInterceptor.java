package com.nomi.wayfinder.aiusage;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * On the AI service's RestClients (HttpClients.aiService): records every call with the token counts ai-service
 * sends back, also failed ones (timeouts, 502). The user is the signed-in one of the request thread; photo checks run
 * in the background and have none.
 */
@Component
public class AiUsageInterceptor implements ClientHttpRequestInterceptor {

    private final AiUsageService usage;

    public AiUsageInterceptor(AiUsageService usage) {
        this.usage = usage;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        AiUsageService.Kind kind = kind(request.getURI().getPath());
        Long userId = currentUserId();
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException e) {
            usage.record(kind, userId, new HttpHeaders(), false, millisSince(start));
            throw e;
        }
        boolean ok;
        try {
            ok = response.getStatusCode().is2xxSuccessful();
        } catch (IOException e) {
            ok = false;
        }
        usage.record(kind, userId, response.getHeaders(), ok, millisSince(start));
        return response;
    }

    static AiUsageService.Kind kind(String path) {
        if (path == null) {
            return AiUsageService.Kind.OTHER;
        }
        if (path.endsWith("/v1/intent")) {
            return AiUsageService.Kind.INTENT;
        }
        if (path.endsWith("/v1/photos/verify")) {
            return AiUsageService.Kind.PHOTO;
        }
        return AiUsageService.Kind.OTHER;
    }

    // JWT subject = user id (JwtService)
    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
