package com.nomi.wayfinder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    // All "now" / "today" decisions use the city's time zone, not the server's.
    // Injecting a Clock also lets tests use a fixed time.
    @Bean
    public Clock clock(NomiProperties properties) {
        return Clock.system(ZoneId.of(properties.timezone()));
    }
}
