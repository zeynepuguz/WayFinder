package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.i18n.TurkishFold;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which day a message is about, in Turkish or English: "bugün", "yarın", "yarından sonra" / "öbür gün",
 * weekdays ("cumartesi", "on Saturday"), explicit dates ("28 Eylül", "28.09", "September 28").
 * Works on the ASCII-folded text, so "yarin" / "carsamba" typed without Turkish letters work too.
 *
 * Deliberately careful:
 * - "pazar" is also "market": only the bare word counts ("pazar", "bu pazar", "pazar günü"), never "pazara" / "pazarı"
 * - "28.09" is a date, but "12.05" could be 12:05 and "2.5" a decimal: a dotted day.month without a year is only
 *   a date when it cannot be either (day > 23, two-digit month); "12.05.2026" and "12/05" are dates
 * - a date that already passed this year means next year
 */
final class IntentDates {

    private static final Map<String, Integer> MONTHS_TR = new LinkedHashMap<>();
    private static final Map<String, Integer> MONTHS_EN = new LinkedHashMap<>();
    private static final Map<String, DayOfWeek> WEEKDAYS_TR = new LinkedHashMap<>();
    private static final Map<String, DayOfWeek> WEEKDAYS_EN = new LinkedHashMap<>();
    // Case / possessive endings a weekday may carry: "cumaya", "salı günü" (separate word), "perşembeden"
    private static final Set<String> WEEKDAY_SUFFIXES = Set.of(
            "", "ya", "ye", "yi", "yu", "da", "de", "ta", "te", "dan", "den", "ki", "si", "leri", "lari");

    static {
        String[] tr = {"ocak", "subat", "mart", "nisan", "mayis", "haziran", "temmuz", "agustos", "eylul", "ekim",
                "kasim", "aralik"};
        for (int i = 0; i < tr.length; i++) {
            MONTHS_TR.put(tr[i], i + 1);
        }
        String[] en = {"january", "february", "march", "april", "may", "june", "july", "august", "september",
                "october", "november", "december"};
        for (int i = 0; i < en.length; i++) {
            MONTHS_EN.put(en[i], i + 1);
        }
        MONTHS_EN.put("jan", 1);
        MONTHS_EN.put("feb", 2);
        MONTHS_EN.put("mar", 3);
        MONTHS_EN.put("apr", 4);
        MONTHS_EN.put("jun", 6);
        MONTHS_EN.put("jul", 7);
        MONTHS_EN.put("aug", 8);
        MONTHS_EN.put("sep", 9);
        MONTHS_EN.put("sept", 9);
        MONTHS_EN.put("oct", 10);
        MONTHS_EN.put("nov", 11);
        MONTHS_EN.put("dec", 12);

        // Longest first: "cumartesi" / "pazartesi" before "cuma" / "pazar"
        WEEKDAYS_TR.put("cumartesi", DayOfWeek.SATURDAY);
        WEEKDAYS_TR.put("pazartesi", DayOfWeek.MONDAY);
        WEEKDAYS_TR.put("carsamba", DayOfWeek.WEDNESDAY);
        WEEKDAYS_TR.put("persembe", DayOfWeek.THURSDAY);
        WEEKDAYS_TR.put("cuma", DayOfWeek.FRIDAY);
        WEEKDAYS_TR.put("sali", DayOfWeek.TUESDAY);

        for (DayOfWeek day : DayOfWeek.values()) {
            WEEKDAYS_EN.put(day.name().toLowerCase(Locale.ROOT), day);
        }
    }

    private static final String TR_MONTH_ALT = String.join("|", MONTHS_TR.keySet());
    private static final String EN_MONTH_ALT = MONTHS_EN.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).reduce((a, b) -> a + "|" + b).orElseThrow();

    // "28 eylül", "28 eylül'de", "28 september", "28th of september", "28 eylül 2026"
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?<![\\d.,:/])(\\d{1,2})(?:st|nd|rd|th)?\\.?\\s*(?:of\\s+)?(" + TR_MONTH_ALT + "|" + EN_MONTH_ALT + ")"
                    + "[a-z']*(?:\\s+(\\d{4}))?(?!\\d)");
    // "september 28", "sept 28th, 2026" (not "may": "may 2 people join")
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<![a-z])(" + EN_MONTH_ALT.replace("|may|", "|") + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?"
                    + "(?!\\d|[.:]\\d|\\s*(?:kisi|people|persons|tl|lira))");
    // "28.09", "28/09", "28.09.2026", "28/9/26"
    private static final Pattern NUMERIC = Pattern.compile(
            "(?<![\\d.,:/])(?<!saat )(\\d{1,2})([./])(\\d{1,2})(?:\\2(\\d{4}|\\d{2}))?(?!\\d|[.,:/]\\d|\\s*(?:tl|lira|km|kisi))");

    private IntentDates() {
    }

    /**
     * @param text  the message (any case, Turkish letters or not)
     * @param today today in Istanbul
     * @return the day the message is about, or null when it names none
     */
    static LocalDate parse(String text, LocalDate today) {
        String folded = TurkishFold.ascii(text);

        LocalDate explicit = explicitDate(folded, today);
        if (explicit != null) {
            return explicit;
        }

        List<String> tokens = tokens(folded);
        if (folded.contains("yarindan sonra") || folded.contains("obur gun") || folded.contains("day after tomorrow")) {
            return today.plusDays(2);
        }
        if (tokens.stream().anyMatch(t -> t.startsWith("yarin")) || tokens.contains("tomorrow")) {
            return today.plusDays(1);
        }

        boolean saysToday = tokens.stream().anyMatch(t -> t.startsWith("bugun"))
                || tokens.contains("today") || tokens.contains("tonight");
        for (int i = 0; i < tokens.size(); i++) {
            DayOfWeek day = weekday(tokens.get(i));
            if (day != null) {
                boolean thisOne = saysToday || (i > 0 && (tokens.get(i - 1).equals("bu") || tokens.get(i - 1).equals("this")));
                return next(today, day, thisOne);
            }
        }

        return saysToday ? today : null;
    }

    private static LocalDate explicitDate(String folded, LocalDate today) {
        Matcher m = DAY_MONTH.matcher(folded);
        while (m.find()) {
            Integer month = MONTHS_TR.getOrDefault(m.group(2), MONTHS_EN.get(m.group(2)));
            LocalDate date = date(today, Integer.parseInt(m.group(1)), month, m.group(3));
            if (date != null) {
                return date;
            }
        }

        m = MONTH_DAY.matcher(folded);
        while (m.find()) {
            LocalDate date = date(today, Integer.parseInt(m.group(2)), MONTHS_EN.get(m.group(1)), m.group(3));
            if (date != null) {
                return date;
            }
        }

        m = NUMERIC.matcher(folded);
        while (m.find()) {
            String dayText = m.group(1);
            String monthText = m.group(3);
            int day = Integer.parseInt(dayText);
            // "12.05" may be 12:05 and "2.5" a decimal: without a year only "28.09"-like pairs count
            boolean unclear = ".".equals(m.group(2)) && m.group(4) == null && (day <= 23 || monthText.length() != 2);
            if (unclear) {
                continue;
            }
            LocalDate date = date(today, day, Integer.parseInt(monthText), m.group(4));
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    private static LocalDate date(LocalDate today, int day, Integer month, String yearText) {
        if (month == null || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            if (yearText != null) {
                int year = Integer.parseInt(yearText);
                LocalDate date = LocalDate.of(year < 100 ? 2000 + year : year, month, day);
                return date.isBefore(today) ? null : date;
            }
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            return date.isBefore(today) ? date.plusYears(1) : date;
        } catch (DateTimeException e) {
            // 30 Şubat
            return null;
        }
    }

    private static DayOfWeek weekday(String token) {
        // "pazar" only as the bare word: "pazara" / "pazarı" are more likely the market
        if (token.equals("pazar")) {
            return DayOfWeek.SUNDAY;
        }
        for (Map.Entry<String, DayOfWeek> e : WEEKDAYS_TR.entrySet()) {
            if (token.startsWith(e.getKey())) {
                String suffix = token.substring(e.getKey().length()).replace("'", "");
                if (WEEKDAY_SUFFIXES.contains(suffix)) {
                    return e.getValue();
                }
            }
        }
        for (Map.Entry<String, DayOfWeek> e : WEEKDAYS_EN.entrySet()) {
            if (token.equals(e.getKey()) || token.equals(e.getKey() + "s")) {
                return e.getValue();
            }
        }
        return null;
    }

    // The next such day; today only when the user said "bugün" / "bu cuma" / "this friday"
    private static LocalDate next(LocalDate today, DayOfWeek day, boolean todayCounts) {
        int ahead = (day.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        if (ahead == 0 && !todayCounts) {
            ahead = 7;
        }
        return today.plusDays(ahead);
    }

    private static List<String> tokens(String folded) {
        return Arrays.stream(folded.split("[^a-z0-9']+"))
                .map(t -> t.replaceAll("^'+|'+$", ""))
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
