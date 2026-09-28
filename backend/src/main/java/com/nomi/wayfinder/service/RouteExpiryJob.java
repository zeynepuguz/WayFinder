package com.nomi.wayfinder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Just after midnight (Istanbul time) unfinished routes of the day that ended become EXPIRED, so the home screen
 * never shows yesterday's route as "Aktif rotan". RouteService also expires a user's routes when it reads them,
 * this job only keeps the table tidy. Runs once at startup too (the server may have been down at midnight).
 */
@Component
public class RouteExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(RouteExpiryJob.class);

    private final RouteService routeService;

    public RouteExpiryJob(RouteService routeService) {
        this.routeService = routeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(cron = "${nomi.routes.expire-cron:0 5 0 * * *}", zone = "${nomi.timezone}")
    public void run() {
        try {
            int expired = routeService.expirePastRoutes();
            if (expired > 0) {
                log.info("Expired {} route(s) of past days", expired);
            }
        } catch (Exception e) {
            // Retried on the next run; reads expire routes on their own meanwhile
            log.warn("Route expiry failed: {}", e.getMessage());
        }
    }
}
