package com.nomi.wayfinder.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "route_stops")
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id")
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id")
    private Place place;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StopType stopType;

    @Column(nullable = false)
    private LocalTime plannedStart;

    @Column(nullable = false)
    private LocalTime plannedEnd;

    // Straight-line distance from the previous stop (or the start point), calculated by PostGIS
    @Column(name = "distance_from_previous_m", nullable = false)
    private int distanceFromPreviousMeters;

    @Column(nullable = false)
    private int walkingMinutes;

    // Why the planner picked this place; built from real data (distance, rating, weather, ...)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> reasons = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StopStatus status = StopStatus.PLANNED;

    public RouteStop() {
    }

    public Long getId() {
        return id;
    }

    public Route getRoute() {
        return route;
    }

    void setRoute(Route route) {
        this.route = route;
    }

    public Place getPlace() {
        return place;
    }

    public void setPlace(Place place) {
        this.place = place;
    }

    public int getPosition() {
        return position;
    }

    void setPosition(int position) {
        this.position = position;
    }

    public StopType getStopType() {
        return stopType;
    }

    public void setStopType(StopType stopType) {
        this.stopType = stopType;
    }

    public LocalTime getPlannedStart() {
        return plannedStart;
    }

    public void setPlannedStart(LocalTime plannedStart) {
        this.plannedStart = plannedStart;
    }

    public LocalTime getPlannedEnd() {
        return plannedEnd;
    }

    public void setPlannedEnd(LocalTime plannedEnd) {
        this.plannedEnd = plannedEnd;
    }

    public int getDistanceFromPreviousMeters() {
        return distanceFromPreviousMeters;
    }

    public void setDistanceFromPreviousMeters(int distanceFromPreviousMeters) {
        this.distanceFromPreviousMeters = distanceFromPreviousMeters;
    }

    public int getWalkingMinutes() {
        return walkingMinutes;
    }

    public void setWalkingMinutes(int walkingMinutes) {
        this.walkingMinutes = walkingMinutes;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons == null ? new ArrayList<>() : new ArrayList<>(reasons);
    }

    public StopStatus getStatus() {
        return status;
    }

    public void setStatus(StopStatus status) {
        this.status = status;
    }
}
