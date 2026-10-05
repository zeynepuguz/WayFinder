package com.nomi.wayfinder.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turkish (and common English) swear words in user text: feedback with one is refused. Words are compared after
 * folding (Turkish letters, case), undoing number / symbol spellings ("4mk", "s1kt1r", "$ik") and repeated letters
 * ("siktiiir"), and also with the letters of the whole message joined ("o r o s p u"). Ordinary words that start
 * like a swear word ("sıkıntı", "sıkışık", "amin") are allowed. Pure logic, unit tested.
 */
public final class ProfanityFilter {

    // The whole word (after folding / collapsing)
    private static final Set<String> WORDS = Set.of("amk", "aq", "amq", "mk", "oc", "pic", "sg", "yarak", "yarag",
            "dalyarak", "ibne", "gavat", "pust", "kahpe", "kaltak", "fahise", "fuck", "fck", "shit", "bitch",
            "asshole", "bastard", "cunt", "dick", "motherfucker", "wtf");
    // Words starting with these (Turkish endings follow: "orospunun", "siktirin", "amına")
    private static final List<String> STEMS = List.of("amina", "amcik", "amcig", "orospu", "orosbu",
            "oruspu", "pezevenk", "pezeveng", "siktir", "sikerim", "sikeyim", "sikiyim", "sikik", "sikis", "sikim",
            "sokarim", "yarak", "gotveren", "gotlek", "gotun", "serefsiz", "kahpe", "kaltak", "fahise",
            "ibne", "gavat", "pust", "fuck", "bitch", "orosp");
    // Ordinary words that start like a swear word once folded ("sıkışık" -> "sikisik")
    private static final List<String> SAFE = List.of("sikisik", "sikism", "sikist", "sikisma", "sikimsi", "aminat",
            "aminal", "amine", "pustul", "yarama", "yaramaz", "yaramiyor", "yarami", "ibnelik");
    // Long stems also found in the letters of the whole message ("o.r.o.s.p.u")
    private static final List<String> JOINED = List.of("orospu", "pezevenk", "siktir", "amcik", "yarak", "serefsiz");

    private ProfanityFilter() {
    }

    public static boolean containsProfanity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> words = words(text);
        for (String word : words) {
            if (WORDS.contains(word)) {
                return true;
            }
            if (SAFE.stream().anyMatch(word::startsWith)) {
                continue;
            }
            if (STEMS.stream().anyMatch(word::startsWith)) {
                return true;
            }
        }
        String joined = collapse(String.join("", words));
        return JOINED.stream().anyMatch(joined::contains) && SAFE.stream().noneMatch(joined::contains);
    }

    static List<String> words(String text) {
        String lower = text.replace('İ', 'i').replace('I', 'ı').toLowerCase(Locale.forLanguageTag("tr"));
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            sb.append(switch (c) {
                case 'ı' -> 'i';
                case 'ş', '$', '5' -> 's';
                case 'ğ' -> 'g';
                case 'ü' -> 'u';
                case 'ö', '0' -> 'o';
                case 'ç' -> 'c';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '7' -> 't';
                default -> Character.isLetter(c) ? c : ' ';
            });
        }
        List<String> words = new ArrayList<>();
        for (String w : sb.toString().trim().split("\s+")) {
            if (!w.isEmpty()) {
                words.add(collapse(w));
            }
        }
        return words;
    }

    // "siiiktir" -> "siktir", "yarrak" -> "yarak"
    static String collapse(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toCharArray()) {
            if (sb.isEmpty() || sb.charAt(sb.length() - 1) != c) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
