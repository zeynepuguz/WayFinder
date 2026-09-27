package com.nomi.wayfinder.i18n;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * Picks the Turkish or English version of a user-facing text for the current request.
 * Spring MVC fills the locale from Accept-Language (see WebConfig#localeResolver).
 * Turkish is the default: only an explicit English request gets English. Outside a request
 * (tests, scheduled jobs) there is no locale context, so the answer is Turkish, whatever the JVM default is.
 */
public final class Texts {

    public static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Texts() {
    }

    public static boolean english() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale != null && "en".equals(locale.getLanguage());
    }

    public static String t(String turkish, String english) {
        return english() ? english : turkish;
    }

    // Lowercase a label for use inside a sentence, with the rules of the request's language
    public static String lower(String text) {
        return text.toLowerCase(locale());
    }

    // For dates and numbers written into user-facing texts
    public static Locale locale() {
        return english() ? Locale.ENGLISH : TURKISH;
    }
}
