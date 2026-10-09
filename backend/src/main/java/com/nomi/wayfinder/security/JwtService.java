package com.nomi.wayfinder.security;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.entity.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class JwtService {

    public static final String SESSION_CLAIM = "sid";

    private final JwtEncoder jwtEncoder;
    private final long expirationMinutes;

    public JwtService(JwtEncoder jwtEncoder, NomiProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.expirationMinutes = properties.security().jwtExpirationMinutes();
    }

    /** An access token of the user's session (claim "sid": refused at once when the session ends). */
    public IssuedToken issue(User user, long sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expirationMinutes, ChronoUnit.MINUTES);

        // subject = user id; everything else is looked up from the database when needed
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("nomi-wayfinder")
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(user.getRole().name()))
                .claim(SESSION_CLAIM, sessionId)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
