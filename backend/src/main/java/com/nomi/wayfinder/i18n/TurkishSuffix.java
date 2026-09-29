package com.nomi.wayfinder.i18n;

// Turkish case endings for place names in generated sentences
public final class TurkishSuffix {

    private static final String BACK_VOWELS = "aıou";
    private static final String VOWELS = "aeıioöuü";
    // Consonants that turn the ending's "d" into "t" ("Beşiktaş'tan", "Bebek'ten")
    private static final String VOICELESS = "fstkçşhp";

    private TurkishSuffix() {
    }

    // Ablative ("from"): "Üsküdar" -> "Üsküdar'dan", "Kadıköy" -> "Kadıköy'den", "Beşiktaş" -> "Beşiktaş'tan"
    public static String ablative(String name) {
        String lower = name.trim().toLowerCase(Texts.TURKISH);
        if (lower.isEmpty()) {
            return name;
        }
        char vowel = 'e';
        for (int i = lower.length() - 1; i >= 0; i--) {
            if (VOWELS.indexOf(lower.charAt(i)) >= 0) {
                vowel = lower.charAt(i);
                break;
            }
        }
        char last = lower.charAt(lower.length() - 1);
        String consonant = VOICELESS.indexOf(last) >= 0 ? "t" : "d";
        String ending = BACK_VOWELS.indexOf(vowel) >= 0 ? "an" : "en";
        return name.trim() + "'" + consonant + ending;
    }

    // Genitive ("of"): "Bursa" -> "Bursa'nın", "İzmir" -> "İzmir'in", "Kadıköy" -> "Kadıköy'ün", "Konya" -> "Konya'nın"
    public static String genitive(String name) {
        String lower = name.trim().toLowerCase(Texts.TURKISH);
        if (lower.isEmpty()) {
            return name;
        }
        char vowel = 'e';
        for (int i = lower.length() - 1; i >= 0; i--) {
            if (VOWELS.indexOf(lower.charAt(i)) >= 0) {
                vowel = lower.charAt(i);
                break;
            }
        }
        String ending = switch (vowel) {
            case 'a', 'ı' -> "ın";
            case 'o', 'u' -> "un";
            case 'ö', 'ü' -> "ün";
            default -> "in";
        };
        boolean endsWithVowel = VOWELS.indexOf(lower.charAt(lower.length() - 1)) >= 0;
        return name.trim() + "'" + (endsWithVowel ? "n" : "") + ending;
    }
}
