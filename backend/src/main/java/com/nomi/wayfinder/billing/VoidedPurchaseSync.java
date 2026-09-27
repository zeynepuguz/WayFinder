package com.nomi.wayfinder.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Refunds and chargebacks happen in Google Play, not in the app. Once an hour this asks Google
 * for purchases voided in the last 30 days and revokes the passes they granted.
 * Idempotent: already revoked or unknown tokens are skipped. Does nothing until
 * GOOGLE_PLAY_SERVICE_ACCOUNT_FILE is set.
 */
@Component
public class VoidedPurchaseSync {

    private static final Logger log = LoggerFactory.getLogger(VoidedPurchaseSync.class);
    // Google only keeps the last 30 days
    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final int MAX_PAGES = 50;

    private final GooglePlayClient googlePlay;
    private final BillingService billingService;
    private final Clock clock;

    public VoidedPurchaseSync(GooglePlayClient googlePlay, BillingService billingService, Clock clock) {
        this.googlePlay = googlePlay;
        this.billingService = billingService;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    public void run() {
        if (!googlePlay.isConfigured()) {
            return;
        }
        try {
            int revoked = sync();
            if (revoked > 0) {
                log.info("Revoked {} access pass(es) after Google Play refunds", revoked);
            }
        } catch (Exception e) {
            // Retried on the next run; the 30-day window covers missed hours
            log.warn("Voided purchase sync failed: {}", e.getMessage());
        }
    }

    int sync() {
        long startTime = clock.instant().minus(LOOKBACK).plus(Duration.ofMinutes(5)).toEpochMilli();
        int revoked = 0;
        String pageToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            GooglePlayClient.VoidedPurchasesPage result = googlePlay.getVoidedPurchases(startTime, pageToken);
            if (result == null) {
                break;
            }
            if (result.voidedPurchases() != null) {
                for (GooglePlayClient.VoidedPurchase voided : result.voidedPurchases()) {
                    if (voided.purchaseToken() != null && billingService.revokePurchase(voided.purchaseToken())) {
                        revoked++;
                    }
                }
            }
            pageToken = result.tokenPagination() == null ? null : result.tokenPagination().nextPageToken();
            if (pageToken == null) {
                break;
            }
        }
        return revoked;
    }
}
