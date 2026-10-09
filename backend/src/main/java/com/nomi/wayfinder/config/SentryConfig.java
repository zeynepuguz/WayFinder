package com.nomi.wayfinder.config;

import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

/**
 * Nothing personal leaves for Sentry: e-mail addresses in log messages (lock warnings, mail errors) are masked in
 * events and breadcrumbs. The SDK itself sends no IP, user or request body (application.yml).
 */
@Configuration
public class SentryConfig {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    static String mask(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("[email]");
    }

    @Bean
    public SentryOptions.BeforeSendCallback maskPersonalDataInEvents() {
        return (event, hint) -> {
            Message message = event.getMessage();
            if (message != null) {
                message.setMessage(mask(message.getMessage()));
                message.setFormatted(mask(message.getFormatted()));
                if (message.getParams() != null) {
                    message.setParams(message.getParams().stream().map(SentryConfig::mask).toList());
                }
            }
            if (event.getExceptions() != null) {
                event.getExceptions().forEach(e -> e.setValue(mask(e.getValue())));
            }
            return event;
        };
    }

    @Bean
    public SentryOptions.BeforeBreadcrumbCallback maskPersonalDataInBreadcrumbs() {
        return (breadcrumb, hint) -> {
            breadcrumb.setMessage(mask(breadcrumb.getMessage()));
            return breadcrumb;
        };
    }
}
