package com.nomi.wayfinder.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How OSM names become the names we show. Pure logic, unit tested.
 *
 * - {@link #choose}: OSM name:tr when present, else name (name:en is kept separately for English requests).
 * - {@link #clean}: trims, collapses spaces, strips quotes around the whole name, and repairs obviously broken
 *   casing only: an all-lowercase name ("köfteci yusuf") or an ALL-CAPS name of more than 4 letters
 *   ("KARDEŞLER LOKANTASI") becomes Turkish title case ("Köfteci Yusuf", "Kardeşler Lokantası"; particles such as
 *   "ve", "ile", "de" stay lowercase). Mixed-case names keep their brand styling ("sushiCo", "KahveDünyası").
 *   ALL-CAPS names with a dotless "I" but no "İ" and no other Turkish letter are left alone: "ISTANBUL'74" or
 *   "BIG CHEFS" could be English or Turkish written without Turkish capitals, and guessing would make them wrong.
 *   Short all-caps words (up to 3 letters: "KFC", "İBB", "AVM") keep their capitals unless they are common words.
 * - {@link #describesEventOrSentence}: names that describe something that happened there, or a whole sentence,
 *   instead of naming a place ("Gezi Parkı olaylarının gerçekleştiği yer").
 */
public final class PlaceNames {

    static final Locale TR = Locale.forLanguageTag("tr");
    static final int MAX_NAME_LENGTH = 60;

    // Stay lowercase inside a title-cased name (Turkish and English particles)
    private static final Set<String> PARTICLES = Set.of("ve", "ile", "de", "da", "ki", "veya", "ya",
            "and", "of", "the", "in", "on", "at", "by", "for", "to", "a", "an", "&");
    // All-caps words of up to 3 letters that are words, not acronyms
    private static final Set<String> SHORT_WORDS = Set.of("ve", "ile", "de", "da", "ki", "bar", "pub", "ev", "evi",
            "çay", "su", "et", "bal", "tat", "nar", "can", "ay", "yol", "han", "köy", "kör", "baba", "ana", "ata",
            "gül", "dağ", "göl", "the", "of", "and", "by", "cafe", "cup", "art", "sun", "sea", "bay", "hot", "top",
            "new", "old", "big", "mix", "bir", "iki", "üç", "tek", "yer", "kız", "bey", "ağa", "usta", "ali", "veli");

    private static final Pattern SPACES = Pattern.compile("\\s+");
    // Quotes around the whole name: "Kahve Durağı", «Moda», “Çınaraltı”, 'Kafe'
    private static final Pattern SURROUNDING_QUOTES = Pattern.compile("^[\"'“”„«»‘’`]+|[\"'“”„«»‘’`]+$");
    // "... olaylarının gerçekleştiği yer", "... savaşın yapıldığı yer", "... bulunduğu alan"
    private static final Pattern EVENT_PLACE = Pattern.compile(
            "(?iuU)\\p{L}+(?:dığı|diği|duğu|düğü|tığı|tiği|tuğu|tüğü|ldüğü|ldığı|ndüğü|ndığı)\\s+(?:yer|yeri|nokta|noktası|alan|alanı)$");
    private static final Pattern EVENT_WORDS = Pattern.compile(
            "(?iuU)\\b(?:olaylarının|olayların|olayının|katliamının|saldırının|saldırısının|protestolarının|çatışmanın|"
                    + "savaşının|muharebesinin|depreminin|yangınının)\\b");
    // Verb endings that make a long "name" a sentence ("... yapılmıştır", "... bulunmaktadır", "... idi")
    private static final Pattern SENTENCE_VERB = Pattern.compile(
            "(?iuU)\\b\\p{L}+(?:mıştır|miştir|muştur|müştür|maktadır|mektedir|dığı|diği|duğu|düğü|tığı|tiği|yordu|acaktır|ecektir|dır|dir|dur|dür)\\b");

    private PlaceNames() {
    }

    // name:tr when OSM has it, else name (both cleaned); null when neither is usable
    public static String choose(String name, String nameTr) {
        String tr = clean(nameTr);
        return tr != null ? tr : clean(name);
    }

    // name:en only when it says something the main name does not (not a copy of it)
    public static String english(String nameEn, String chosen) {
        String en = clean(nameEn);
        if (en == null || chosen == null || OsmPlaceMapper.fold(en).equals(OsmPlaceMapper.fold(chosen))) {
            return null;
        }
        return en;
    }

    /**
     * @return the cleaned name, or null when nothing is left
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String name = SPACES.matcher(raw.strip()).replaceAll(" ");
        String unquoted = SURROUNDING_QUOTES.matcher(name).replaceAll("").strip();
        // Keep a quote that belongs to the name ("Nuri'nin Yeri'" stays when only the end would be stripped)
        if (!unquoted.isEmpty() && quotedOnBothSides(name)) {
            name = unquoted;
        }
        if (name.isEmpty()) {
            return null;
        }
        if (isAllLowercase(name)) {
            return titleCase(name, false);
        }
        if (isAllCaps(name) && letterCount(name) > 4 && !ambiguousCapitalI(name)) {
            return titleCase(name, true);
        }
        return name;
    }

    private static boolean quotedOnBothSides(String name) {
        return name.length() >= 2 && isQuote(name.charAt(0)) && isQuote(name.charAt(name.length() - 1));
    }

    private static boolean isQuote(char c) {
        return "\"'“”„«»‘’`".indexOf(c) >= 0;
    }

    static boolean isAllLowercase(String name) {
        return name.chars().anyMatch(Character::isLetter) && name.chars().noneMatch(Character::isUpperCase);
    }

    static boolean isAllCaps(String name) {
        return name.chars().anyMatch(Character::isUpperCase) && name.chars().noneMatch(Character::isLowerCase);
    }

    private static int letterCount(String name) {
        return (int) name.chars().filter(Character::isLetter).count();
    }

    // "I" without any "İ" or other Turkish capital: English, or Turkish typed without Turkish capitals
    static boolean ambiguousCapitalI(String name) {
        if (name.indexOf('I') < 0 || name.indexOf('İ') >= 0) {
            return false;
        }
        return name.chars().noneMatch(c -> "ÇĞÖŞÜçğöşü".indexOf(c) >= 0);
    }

    /**
     * Turkish title case (Locale tr: "i" -> "İ", "I" -> "ı"). Words start after a space, "-", "/", "(" or "&";
     * letters after an apostrophe stay lowercase ("Yusuf'un"). Particles stay lowercase unless first.
     *
     * @param fromCaps the name was ALL CAPS: short acronyms ("KFC", "İBB") keep their capitals
     */
    static String titleCase(String name, boolean fromCaps) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ') {
                words.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        words.add(current.toString());

        StringBuilder result = new StringBuilder();
        for (int w = 0; w < words.size(); w++) {
            String word = words.get(w);
            if (w > 0) {
                result.append(' ');
            }
            String lower = word.toLowerCase(TR);
            int letters = (int) word.chars().filter(Character::isLetter).count();
            if (fromCaps && letters > 0 && letters <= 3 && !SHORT_WORDS.contains(lower) && !PARTICLES.contains(lower)) {
                // An acronym: "KFC", "İBB", "AVM"
                result.append(word);
            } else if (w > 0 && PARTICLES.contains(lower)) {
                result.append(lower);
            } else {
                result.append(capitalizeParts(lower));
            }
        }
        return result.toString();
    }

    // Capitalizes the first letter of the word and of each part after "-", "/", "(" or "&"
    private static String capitalizeParts(String lower) {
        StringBuilder out = new StringBuilder(lower.length());
        boolean start = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (start && Character.isLetter(c)) {
                out.append(String.valueOf(c).toUpperCase(TR));
                start = false;
            } else {
                out.append(c);
                if (c == '-' || c == '/' || c == '(' || c == '&' || c == '.') {
                    start = true;
                } else if (Character.isLetterOrDigit(c)) {
                    start = false;
                }
            }
        }
        return out.toString();
    }

    /**
     * A name that describes an event or is a sentence, not the name of a place:
     * "Gezi Parkı olaylarının gerçekleştiği yer", "Eski caminin bulunduğu yer". "Atatürk'ün konakladığı ev" is a
     * real house though, so only the "... -dığı yer / nokta / alan" form and event words count, plus long names
     * (> MAX_NAME_LENGTH) with a verb or a list of things.
     */
    public static boolean describesEventOrSentence(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.strip();
        if (EVENT_PLACE.matcher(trimmed).find()) {
            return true;
        }
        // "... olaylarının yeri"
        if (EVENT_WORDS.matcher(trimmed).find() && trimmed.toLowerCase(TR).matches("(?s).*\\s(yer|yeri|nokta|noktası)$")) {
            return true;
        }
        // A long description or sentence: a verb, or a list of things ("... vardır", "kamp, kahvaltı, çay, duş")
        return trimmed.length() > MAX_NAME_LENGTH
                && (SENTENCE_VERB.matcher(trimmed).find() || trimmed.chars().filter(c -> c == ',').count() >= 3);
    }
}
