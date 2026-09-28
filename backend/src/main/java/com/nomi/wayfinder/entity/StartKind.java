package com.nomi.wayfinder.entity;

// Where a route starts (routes.start_kind): shown to the user as the route's start label
public enum StartKind {
    // The user's own position ("Konumun")
    LOCATION,
    // The chosen district's / city's most popular sight (its name)
    SIGHT,
    // The chosen district's centre ("Kadıköy merkezi")
    DISTRICT,
    // The chosen city's centre ("İzmir merkezi")
    CITY
}
