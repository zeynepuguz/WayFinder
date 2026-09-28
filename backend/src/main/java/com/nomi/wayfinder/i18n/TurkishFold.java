package com.nomi.wayfinder.i18n;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turkish-aware ASCII folding for matching and URL slugs: "Üsküdar'da" -> "uskudar'da", "Kadıköy" -> "kadikoy".
 * Turkish letters are mapped by hand first, so the JVM's Turkish default locale cannot turn "I" into "ı"
 * (and "İ" becomes a plain "i", not "i̇").
 */
public final class TurkishFold {

    private TurkishFold() {
    }

    // Lowercase ASCII letters; spaces, digits and punctuation are kept as they are
    public static String ascii(String text) {
        if (text == null) {
            return "";
        }
        String mapped = text
                .replace('İ', 'i').replace('I', 'i').replace('ı', 'i')
                .replace('Ş', 's').replace('ş', 's')
                .replace('Ğ', 'g').replace('ğ', 'g')
                .replace('Ü', 'u').replace('ü', 'u')
                .replace('Ö', 'o').replace('ö', 'o')
                .replace('Ç', 'c').replace('ç', 'c')
                .replace('’', '\'').replace('‘', '\'');
        String withoutMarks = Normalizer.normalize(mapped, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutMarks.toLowerCase(Locale.ROOT);
    }

    // "Büyükçekmece" -> "buyukcekmece", "Bahçelievler Merkez" -> "bahcelievler-merkez"
    public static String slug(String text) {
        return ascii(text).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
