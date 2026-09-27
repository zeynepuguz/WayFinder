package com.nomi.wayfinder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Background jobs (e.g. revoking passes of refunded Google Play purchases)
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
