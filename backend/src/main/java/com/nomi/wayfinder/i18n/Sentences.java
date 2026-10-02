package com.nomi.wayfinder.i18n;

import java.util.Locale;

/**
 * Turkish (and English) writing rules for the assistant's answers: every sentence starts with a capital letter
 * ("i" -> "İ" in Turkish). A sentence starts at the beginning of the text, after a line break, and after ".", "?"
 * or "!" followed by a space; quotes, brackets and list bullets in front of it are skipped.
 */
public final class Sentences {

    private Sentences() {
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Locale locale = Texts.english() ? Locale.ROOT : Texts.TURKISH;
        StringBuilder sb = new StringBuilder(text.length());
        boolean start = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (start && Character.isLetter(c)) {
                sb.append(String.valueOf(c).toUpperCase(locale));
                start = false;
                continue;
            }
            if (start && !(Character.isWhitespace(c) || "\"“'‘(•-".indexOf(c) >= 0)) {
                // A sentence starting with a number or a time ("09:30 → Kahvaltı") stays as it is
                start = false;
            }
            sb.append(c);
            if (c == '\n') {
                start = true;
            } else if (c == '.' || c == '?' || c == '!') {
                start = i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1));
            }
        }
        return sb.toString();
    }
}
