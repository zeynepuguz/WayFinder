package com.nomi.wayfinder.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

// A day plan generated for a user: ordered stops + the conditions it was planned for
@Entity
@Table(name = "routes")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "route_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus status = RouteStatus.DRAFT;

    @Column(nullable = false)
    private boolean saved;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point startLocation;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private int partySize = 1;

    // Total budget in TL for the whole party
    private Integer budget;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalkingTolerance walkingTolerance;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> interests = new ArrayList<>();

    // Places swapped out of this route by the user: never planned into it again
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "bigint[]", nullable = false)
    private List<Long> rejectedPlaceIds = new ArrayList<>();

    @Column(length = 20)
    private String weatherCondition;

    private Double weatherTemperature;

    private String weatherAdvice;

    // Where the route starts; null = the user's position (routes created before start areas existed)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private StartKind startKind;

    // The sight / district / city name of an area start; null for LOCATION
    private String startLabel;

    // The user told us it is raining; stays on for later replans of this route
    @Column(nullable = false)
    private boolean assumeWet;

    // Warnings produced by the planner (skipped stops, budget exceeded, ...)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> notes = new ArrayList<>();

    // Invite code of a group plan (service/RouteGroupService); null = not shared
    @Column(length = 32, unique = true)
    private String shareToken;

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RouteStop> stops = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Route() {
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void replaceStops(List<RouteStop> newStops) {
        stops.clear();
        for (int i = 0; i < newStops.size(); i++) {
            RouteStop stop = newStops.get(i);
            stop.setRoute(this);
            stop.setPosition(i + 1);
            stops.add(stop);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public void setStatus(RouteStatus status) {
        this.status = status;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
    }

    public Point getStartLocation() {
        return startLocation;
    }

    public void setStartLocation(double latitude, double longitude) {
        this.startLocation = GeoPoints.of(latitude, longitude);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public int getPartySize() {
        return partySize;
    }

    public void setPartySize(int partySize) {
        this.partySize = partySize;
    }

    public Integer getBudget() {
        return budget;
    }

    public void setBudget(Integer budget) {
        this.budget = budget;
    }

    public WalkingTolerance getWalkingTolerance() {
        return walkingTolerance;
    }

    public void setWalkingTolerance(WalkingTolerance walkingTolerance) {
        this.walkingTolerance = walkingTolerance;
    }

    public List<Long> getRejectedPlaceIds() {
        return rejectedPlaceIds;
    }

    public void rejectPlace(Long placeId) {
        if (placeId != null && !rejectedPlaceIds.contains(placeId)) {
            rejectedPlaceIds = new ArrayList<>(rejectedPlaceIds);
            rejectedPlaceIds.add(placeId);
        }
    }

    public List<String> getInterests() {
        return interests;
    }

    public void setInterests(List<String> interests) {
        this.interests = interests == null ? new ArrayList<>() : new ArrayList<>(interests);
    }

    public String getWeatherCondition() {
        return weatherCondition;
    }

    public void setWeatherCondition(String weatherCondition) {
        this.weatherCondition = weatherCondition;
    }

    public Double getWeatherTemperature() {
        return weatherTemperature;
    }

    public void setWeatherTemperature(Double weatherTemperature) {
        this.weatherTemperature = weatherTemperature;
    }

    public String getWeatherAdvice() {
        return weatherAdvice;
    }

    public void setWeatherAdvice(String weatherAdvice) {
        this.weatherAdvice = weatherAdvice;
    }

    public StartKind getStartKind() {
        return startKind;
    }

    public void setStartKind(StartKind startKind) {
        this.startKind = startKind;
    }

    public String getStartLabel() {
        return startLabel;
    }

    public void setStartLabel(String startLabel) {
        this.startLabel = startLabel;
    }

    public boolean isAssumeWet() {
        return assumeWet;
    }

    public void setAssumeWet(boolean assumeWet) {
        this.assumeWet = assumeWet;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes == null ? new ArrayList<>() : new ArrayList<>(notes);
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public List<RouteStop> getStops() {
        return stops;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
