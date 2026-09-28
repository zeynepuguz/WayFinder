package com.nomi.wayfinder.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * User photos of places and districts ("nomi.photos.*").
 *
 * @param storageDir            where the re-encoded JPEGs are written (a Docker volume in production)
 * @param publicBase            URL prefix of the stored files in API answers: "/media/photos" (same origin) or an
 *                              absolute URL ("https://api.example.com/media/photos") for the mobile app
 * @param keepPerTarget         approved photos kept per place / district; older ones are deleted
 * @param dailyUploadsPerUser   uploads per user per day (Istanbul date)
 * @param dailyUploadsPerTarget uploads per user per place / district per day
 * @param aiReadTimeout         the AI check (moderation + vision model) takes a few seconds
 */
@ConfigurationProperties(prefix = "nomi.photos")
public record PhotoProperties(
        String storageDir,
        String publicBase,
        int keepPerTarget,
        int dailyUploadsPerUser,
        int dailyUploadsPerTarget,
        Duration aiReadTimeout
) {
}
