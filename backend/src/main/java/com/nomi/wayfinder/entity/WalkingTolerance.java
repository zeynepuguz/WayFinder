package com.nomi.wayfinder.entity;

// Max straight-line distance we are willing to put between two consecutive stops
public enum WalkingTolerance {
    LOW(600),
    MEDIUM(1200),
    HIGH(2500);

    private final int maxLegMeters;

    WalkingTolerance(int maxLegMeters) {
        this.maxLegMeters = maxLegMeters;
    }

    public int getMaxLegMeters() {
        return maxLegMeters;
    }
}
