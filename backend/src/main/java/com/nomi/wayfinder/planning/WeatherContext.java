package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.weather.HourlyWeather;
import com.nomi.wayfinder.weather.WeatherForecast;

import java.time.LocalTime;

// How the weather looks at one specific time of the plan, reduced to the flags the scorer cares about
public record WeatherContext(boolean wet, boolean hot, boolean windy, boolean cold, boolean known) {

    public static final WeatherContext UNKNOWN = new WeatherContext(false, false, false, false, false);

    public static WeatherContext at(WeatherForecast forecast, LocalTime time, boolean assumeWet) {
        if (forecast == null) {
            return assumeWet ? new WeatherContext(true, false, false, false, true) : UNKNOWN;
        }

        HourlyWeather hour = forecast.at(time);
        if (hour == null) {
            return assumeWet ? new WeatherContext(true, false, false, false, true) : UNKNOWN;
        }

        // Heat only matters in the middle of the day; evenings are fine for outdoor places
        boolean midday = !time.isBefore(LocalTime.of(11, 0)) && time.isBefore(LocalTime.of(17, 30));

        return new WeatherContext(
                assumeWet || hour.isWet(),
                hour.isHot() && midday,
                hour.isWindy(),
                hour.isCold(),
                true
        );
    }

    public boolean badForOutdoor() {
        return wet || hot || windy || cold;
    }

    // Short Turkish reason used in stop explanations
    public String reasonLabel() {
        if (wet) {
            return "yağışlı";
        }
        if (hot) {
            return "çok sıcak";
        }
        if (windy) {
            return "rüzgarlı";
        }
        if (cold) {
            return "soğuk";
        }
        return "uygun";
    }
}
