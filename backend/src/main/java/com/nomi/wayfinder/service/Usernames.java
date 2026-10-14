package com.nomi.wayfinder.service;

import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Public usernames (shown under the photos a user adds): 3-20 characters, lowercase letters, digits, "_" and ".",
 * starting with a letter. Unique; a few words that would look official are reserved.
 */
@Component
public class Usernames {

    static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_.]{2,19}");
    // Would look like Nomi itself or its team under a photo
    static final Set<String> RESERVED = Set.of("admin", "administrator", "nomi", "nomiapp", "nomi_app", "destek",
            "support", "yonetim", "yonetici", "moderator", "mod", "root", "system", "sistem", "resmi", "official");
    static final String INVALID = "Invalid username";
    static final String TAKEN = "Username is taken";

    private final UserRepository users;

    public Usernames(UserRepository users) {
        this.users = users;
    }

    /** "Zeynep_7 " -> "zeynep_7", "IŞIL" -> "isil": lowercase, Turkish letters in their plain form. */
    public static String normalize(String username) {
        if (username == null) {
            return "";
        }
        String lower = username.trim().toLowerCase(Texts.TURKISH).replace('ı', 'i');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    public static boolean isValid(String username) {
        return VALID.matcher(username).matches() && !RESERVED.contains(username)
                && !username.contains("..") && !username.endsWith(".");
    }

    /**
     * The chosen username, normalized, when it is valid and free; free also when {@code ownerId} already has it.
     *
     * @throws BusinessException 400 when it breaks the rules, 409 when someone else has it
     */
    public String require(String username, Long ownerId) {
        String name = normalize(username);
        if (!isValid(name)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID);
        }
        users.findByUsername(name)
                .filter(user -> !user.getId().equals(ownerId))
                .ifPresent(user -> {
                    throw new BusinessException(HttpStatus.CONFLICT, TAKEN);
                });
        return name;
    }

    /** A free username from the first name: "zeynep", else "zeynep" plus a few digits ("zeynep482"). */
    public String suggest(String firstName) {
        String base = plain(firstName);
        if (base.length() < 3 || !Character.isLetter(base.charAt(0))) {
            base = "gezgin";
        }
        base = base.length() > 14 ? base.substring(0, 14) : base;
        if (isValid(base) && !users.existsByUsername(base)) {
            return base;
        }
        for (int digits = 2; ; digits++) {
            int bound = (int) Math.pow(10, Math.min(digits, 6));
            for (int attempt = 0; attempt < 20; attempt++) {
                String candidate = base + ThreadLocalRandom.current().nextInt(bound / 10, bound);
                if (!users.existsByUsername(candidate)) {
                    return candidate;
                }
            }
        }
    }

    // "Şükrü Ayşe" -> "sukru": the first word in plain lowercase letters and digits
    static String plain(String name) {
        String first = name == null ? "" : name.trim().split("\\s+")[0];
        String folded = Normalizer.normalize(first.toLowerCase(Texts.TURKISH).replace('ı', 'i'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
