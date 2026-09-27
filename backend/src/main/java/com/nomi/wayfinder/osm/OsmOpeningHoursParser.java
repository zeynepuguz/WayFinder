package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the simple, unambiguous forms of OSM's opening_hours tag into our weekly rows.
 * Supported: "24/7", "09:00-22:00" (every day), rules like "Mo-Fr 08:00-20:00; Sa 12:00-17:00",
 * day lists ("Mo,We,Fr"), wrapping ranges ("Sa-Mo"), several ranges per day ("09:00-12:00,13:00-18:00"),
 * "off"/"closed", "24:00" as midnight and closing after midnight ("18:00-02:00").
 * Anything else (PH, months, week numbers, sunrise, "+", comments, ...) returns an empty list,
 * which means "hours unknown" in our schema. Guessing wrong hours would be worse than not knowing.
 *
 * Rows follow the schema's convention: closesAt <= opensAt means the place closes after midnight,
 * so 00:00-00:00 is a full day. A later rule replaces earlier ones for the days it names (OSM semantics);
 * days no rule names are closed.
 */
public final class OsmOpeningHoursParser {

    private static final List<String> DAYS = List.of("mo", "tu", "we", "th", "fr", "sa", "su");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");
    private static final Pattern DAY_SELECTOR = Pattern.compile("[A-Za-z]{2}(-[A-Za-z]{2})?(,[A-Za-z]{2}(-[A-Za-z]{2})?)*");
    private static final LocalTime MIDNIGHT = LocalTime.MIDNIGHT;

    private OsmOpeningHoursParser() {
    }

    public static List<OpeningHoursDto> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String text = value.trim();
        if (text.equals("24/7")) {
            return everyDay(MIDNIGHT, MIDNIGHT);
        }

        // day (1..7) -> ranges; insertion order keeps the output stable
        Map<Integer, List<LocalTime[]>> week = new LinkedHashMap<>();
        for (String raw : text.split(";")) {
            String rule = raw.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if (!applyRule(rule, week)) {
                return List.of();
            }
        }

        List<OpeningHoursDto> rows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            for (LocalTime[] range : week.getOrDefault(day, List.of())) {
                rows.add(new OpeningHoursDto(day, range[0], range[1]));
            }
        }
        return rows;
    }

    private static boolean applyRule(String rule, Map<Integer, List<LocalTime[]>> week) {
        List<Integer> days;
        String times;

        if (Character.isDigit(rule.charAt(0))) {
            days = List.of(1, 2, 3, 4, 5, 6, 7);
            times = rule;
        } else {
            int space = rule.indexOf(' ');
            if (space < 0) {
                return false;
            }
            days = parseDays(rule.substring(0, space));
            if (days == null) {
                return false;
            }
            times = rule.substring(space + 1).trim();
        }

        String lowerTimes = times.toLowerCase(java.util.Locale.ROOT);
        if (lowerTimes.equals("off") || lowerTimes.equals("closed")) {
            days.forEach(d -> week.put(d, new ArrayList<>()));
            return true;
        }

        List<LocalTime[]> ranges = parseRanges(times);
        if (ranges == null) {
            return false;
        }
        days.forEach(d -> week.put(d, new ArrayList<>(ranges)));
        return true;
    }

    // "Mo-Fr", "Mo,We,Fr", "Sa-Mo" (wraps over the week end); null when not understood
    private static List<Integer> parseDays(String selector) {
        if (!DAY_SELECTOR.matcher(selector).matches()) {
            return null;
        }
        List<Integer> days = new ArrayList<>();
        for (String part : selector.split(",")) {
            String[] ends = part.split("-");
            int from = DAYS.indexOf(ends[0].toLowerCase(java.util.Locale.ROOT));
            int to = ends.length == 2 ? DAYS.indexOf(ends[1].toLowerCase(java.util.Locale.ROOT)) : from;
            if (from < 0 || to < 0) {
                return null;
            }
            for (int i = from; ; i = (i + 1) % 7) {
                if (!days.contains(i + 1)) {
                    days.add(i + 1);
                }
                if (i == to) {
                    break;
                }
            }
        }
        return days;
    }

    // "09:00-12:00,13:00-18:00"; null when not understood
    private static List<LocalTime[]> parseRanges(String times) {
        List<LocalTime[]> ranges = new ArrayList<>();
        for (String raw : times.split(",")) {
            Matcher m = TIME_RANGE.matcher(raw.trim());
            if (!m.matches()) {
                return null;
            }
            int openHour = Integer.parseInt(m.group(1));
            int openMinute = Integer.parseInt(m.group(2));
            int closeHour = Integer.parseInt(m.group(3));
            int closeMinute = Integer.parseInt(m.group(4));

            if (openHour > 23 || openMinute > 59 || closeMinute > 59
                    || closeHour > 24 || (closeHour == 24 && closeMinute != 0)) {
                return null;
            }
            LocalTime opens = LocalTime.of(openHour, openMinute);
            // "24:00" is the end of the day: midnight, which the schema reads as "after midnight"
            LocalTime closes = closeHour == 24 ? MIDNIGHT : LocalTime.of(closeHour, closeMinute);

            // "10:00-10:00" is not a real opening; only 00:00-24:00 may mean a whole day
            if (opens.equals(closes) && !(opens.equals(MIDNIGHT) && closeHour == 24)) {
                return null;
            }
            ranges.add(new LocalTime[]{opens, closes});
        }
        return ranges;
    }

    private static List<OpeningHoursDto> everyDay(LocalTime opens, LocalTime closes) {
        List<OpeningHoursDto> rows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            rows.add(new OpeningHoursDto(day, opens, closes));
        }
        return rows;
    }
}
