package com.nomi.wayfinder.config;

import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.photo.PhotoStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AccessInterceptor accessInterceptor;
    private final PhotoStorage photoStorage;

    // Where Spring serves stored user photos in development (nomi.photos.public-base should point here)
    public static final String PHOTOS_PATH = "/media/photos";

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, AccessInterceptor accessInterceptor,
                     PhotoStorage photoStorage) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.accessInterceptor = accessInterceptor;
        this.photoStorage = photoStorage;
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
                        "/api/v1/assistant/**", "/api/v1/auth/**",
                        // Each call is a paid Google Places request
                        "/api/v1/places/*/availability");

        // Paid features
        registry.addInterceptor(accessInterceptor)
                .addPathPatterns("/api/v1/routes", "/api/v1/routes/**", "/api/v1/assistant/**", "/api/v1/saved/**");
    }

    /**
     * Development: stored user photos under /media/photos/** (production: Caddy serves the same volume).
     * File names are random and never change, so browsers may cache them for a year.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PHOTOS_PATH + "/**")
                .addResourceLocations(photoStorage.rootLocation())
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
