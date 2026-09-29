package com.nomi.wayfinder.entity;

public enum PlaceCategory {
    BREAKFAST,
    RESTAURANT,
    CAFE,
    DESSERT,
    ATTRACTION,
    MUSEUM,
    PARK,
    CULTURE,
    // Mosques, churches, synagogues, cemevleri to pray at (famous ones are ATTRACTION as sights too). Explore only
    WORSHIP,
    // Supermarkets, markets, bakkal. Explore only: never a route stop or a suggestion
    MARKET
}
