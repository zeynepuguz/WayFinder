package com.nomi.wayfinder.dto;

public class NearbyPlaceResponse extends PlaceResponse {

    // Distance from the user's current location, in meters
    private Double distanceMeters;

    public Double getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Double distanceMeters) {
        this.distanceMeters = distanceMeters;
    }
}
