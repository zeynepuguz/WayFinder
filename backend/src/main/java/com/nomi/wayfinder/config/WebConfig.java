package com.nomi.wayfinder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AccessInterceptor accessInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, AccessInterceptor accessInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.accessInterceptor = accessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/routes", "/api/v1/routes/*/replan", "/api/v1/assistant/**");

        // Paid features
        registry.addInterceptor(accessInterceptor)
                .addPathPatterns("/api/v1/routes", "/api/v1/routes/**", "/api/v1/assistant/**", "/api/v1/saved/**");
    }
}
