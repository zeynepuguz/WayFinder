package com.nomi.wayfinder.security;

import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    // For endpoints that work with or without login (home, recommendations)
    public static Long idOrNull(Jwt jwt) {
        return jwt == null ? null : id(jwt);
    }
}
