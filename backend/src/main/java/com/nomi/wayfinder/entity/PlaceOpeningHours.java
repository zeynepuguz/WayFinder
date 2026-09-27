package com.nomi.wayfinder.entity;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(name = "place_opening_hours")
public class PlaceOpeningHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id")
    private Place place;

    // 1 = Monday ... 7 = Sunday
    @Column(nullable = false)
    private short dayOfWeek;

    @Column(nullable = false)
    private LocalTime opensAt;

    @Column(nullable = false)
    private LocalTime closesAt;

    public PlaceOpeningHours() {
    }

    public PlaceOpeningHours(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        this.dayOfWeek = (short) dayOfWeek;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    public boolean closesAfterMidnight() {
        return !closesAt.isAfter(opensAt);
    }

    public Long getId() {
        return id;
    }

    public Place getPlace() {
        return place;
    }

    void setPlace(Place place) {
        this.place = place;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getOpensAt() {
        return opensAt;
    }

    public LocalTime getClosesAt() {
        return closesAt;
    }
}
