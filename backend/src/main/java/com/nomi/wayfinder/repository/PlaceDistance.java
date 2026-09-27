package com.nomi.wayfinder.repository;

// A place id with its distance (meters) to a reference point, calculated by PostGIS
public interface PlaceDistance {

    Long getId();

    Double getDistanceMeters();
}
