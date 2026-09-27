package com.nomi.wayfinder.weather;

import com.nomi.wayfinder.i18n.Texts;

// Simplified WMO weather codes (https://open-meteo.com/en/docs, "WMO Weather interpretation codes")
public enum WeatherCondition {
    CLEAR("açık", "clear"),
    CLOUDY("bulutlu", "cloudy"),
    FOG("sisli", "foggy"),
    RAIN("yağmurlu", "rainy"),
    SNOW("karlı", "snowy"),
    STORM("fırtınalı", "stormy");

    private final String label;
    private final String labelEn;

    WeatherCondition(String label, String labelEn) {
        this.label = label;
        this.labelEn = labelEn;
    }

    // In the request's language (Turkish unless the request asked for English)
    public String getLabel() {
        return Texts.t(label, labelEn);
    }

    public boolean isWet() {
        return this == RAIN || this == SNOW || this == STORM;
    }

    public static WeatherCondition fromWmoCode(int code) {
        if (code == 0) {
            return CLEAR;
        }
        if (code <= 3) {
            return CLOUDY;
        }
        if (code == 45 || code == 48) {
            return FOG;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return SNOW;
        }
        if (code >= 95) {
            return STORM;
        }
        return RAIN;
    }
}
