package com.nomi.wayfinder.entity;

import java.time.Period;

/**
 * What a user can buy. Prices live in configuration (nomi.billing.prices), not here,
 * so they can change without a code change. Product ids must match the in-app products
 * created in Google Play Console.
 */
public enum AccessPlan {
    DAILY(Period.ofDays(1), "nomi_pass_daily", "Günlük"),
    WEEKLY(Period.ofWeeks(1), "nomi_pass_weekly", "Haftalık"),
    MONTHLY(Period.ofMonths(1), "nomi_pass_monthly", "Aylık"),
    YEARLY(Period.ofYears(1), "nomi_pass_yearly", "Yıllık");

    private final Period duration;
    private final String productId;
    private final String label;

    AccessPlan(Period duration, String productId, String label) {
        this.duration = duration;
        this.productId = productId;
        this.label = label;
    }

    public Period getDuration() {
        return duration;
    }

    public String getProductId() {
        return productId;
    }

    public String getLabel() {
        return label;
    }

    public static AccessPlan fromProductId(String productId) {
        for (AccessPlan plan : values()) {
            if (plan.productId.equals(productId)) {
                return plan;
            }
        }
        throw new IllegalArgumentException("Unknown product id: " + productId);
    }
}
