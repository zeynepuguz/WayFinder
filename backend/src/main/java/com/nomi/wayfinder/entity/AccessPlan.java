package com.nomi.wayfinder.entity;

import com.nomi.wayfinder.i18n.Texts;

import java.time.Period;

/**
 * What a user can buy. Prices live in configuration (nomi.billing.prices), not here,
 * so they can change without a code change. Product ids must match the in-app products
 * created in Google Play Console.
 */
public enum AccessPlan {
    DAILY(Period.ofDays(1), "nomi_pass_daily", "Günlük", "Daily"),
    WEEKLY(Period.ofWeeks(1), "nomi_pass_weekly", "Haftalık", "Weekly"),
    MONTHLY(Period.ofMonths(1), "nomi_pass_monthly", "Aylık", "Monthly"),
    YEARLY(Period.ofYears(1), "nomi_pass_yearly", "Yıllık", "Yearly");

    private final Period duration;
    private final String productId;
    private final String label;
    private final String labelEn;

    AccessPlan(Period duration, String productId, String label, String labelEn) {
        this.duration = duration;
        this.productId = productId;
        this.label = label;
        this.labelEn = labelEn;
    }

    public Period getDuration() {
        return duration;
    }

    public String getProductId() {
        return productId;
    }

    // In the request's language (Turkish unless the request asked for English)
    public String getLabel() {
        return Texts.t(label, labelEn);
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
