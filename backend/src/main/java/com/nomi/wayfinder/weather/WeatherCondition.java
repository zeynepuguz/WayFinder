package com.nomi.wayfinder.weather;

// Simplified WMO weather codes (https://open-meteo.com/en/docs, "WMO Weather interpretation codes")
public enum WeatherCondition {
    CLEAR("açık"),
    CLOUDY("bulutlu"),
    FOG("sisli"),
    RAIN("yağmurlu"),
    SNOW("karlı"),
    STORM("fırtınalı");

    private final String label;

    WeatherCondition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
