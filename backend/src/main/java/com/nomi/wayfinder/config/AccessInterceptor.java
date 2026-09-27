package com.nomi.wayfinder.config;

import com.nomi.wayfinder.billing.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Paid features (assistant, creating/changing routes, saving) need an active pass.
 * Reading your own past routes stays free, so an expired user does not lose their history.
 * Answers 402 Payment Required; the app shows the paywall on 402.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private final BillingService billingService;

    public AccessInterceptor(BillingService billingService) {
        this.billingService = billingService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("GET".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod())) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            // Security already rejected anonymous calls; nothing to check
            return true;
        }
        boolean admin = jwt.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin || billingService.hasAccess(Long.valueOf(jwt.getToken().getSubject()))) {
            return true;
        }

        response.setStatus(402);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":402,\"message\":\"An active Nomi pass is required\",\"errors\":{}}");
        return false;
    }
}
