package com.nomi.wayfinder.config;

import com.nomi.wayfinder.i18n.Texts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AccessInterceptor accessInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, AccessInterceptor accessInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.accessInterceptor = accessInterceptor;
    }

    /**
     * Texts follow Accept-Language ("For tourists" mode sends en). Turkish when the header is
     * missing or asks for a language we do not have; never the server's JVM locale.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Texts.TURKISH, Locale.forLanguageTag("tr"), Locale.ENGLISH));
        resolver.setDefaultLocale(Texts.TURKISH);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                // auth: slows down password guessing and reset-code spam
                .addPathPatterns("/api/v1/routes", "/api/v1/routes/*/replan", "/api/v1/routes/popular/start",
                        "/api/v1/assistant/**", "/api/v1/auth/**");

        // Paid features
        registry.addInterceptor(accessInterceptor)
                .addPathPatterns("/api/v1/routes", "/api/v1/routes/**", "/api/v1/assistant/**", "/api/v1/saved/**");
    }
}
