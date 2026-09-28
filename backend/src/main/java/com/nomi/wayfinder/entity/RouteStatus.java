package com.nomi.wayfinder.entity;

public enum RouteStatus {
    DRAFT,
    ACTIVE,
    COMPLETED,
    // The route's day is over and it was never finished (RouteService / RouteExpiryJob); read-only
    EXPIRED
}
