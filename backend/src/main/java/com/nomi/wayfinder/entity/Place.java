package com.nomi.wayfinder.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "places")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    private String address;

    private String neighborhood;

    // WGS84 (GPS) point; geography type makes distances come back in meters
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaceCategory category;

    // Approximate cost per person in TL
    private Integer estimatedCost;

    private Double rating;

    // Indoor places are preferred when it rains, it is windy or very hot
    @Column(nullable = false)
    private boolean indoor;

    private Integer avgVisitMinutes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();

    // Where the data came from (SEED_UNVERIFIED, ADMIN, OSM, ...) and when it was last checked
    @Column(nullable = false)
    private String source = "MANUAL";

    private String sourceUrl;

    private Instant lastVerifiedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "place", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek, opensAt")
    private List<PlaceOpeningHours> openingHours = new ArrayList<>();

    public Place() {
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

    /**
     * Is the place open for the whole visit window?
     * Returns null when the opening hours are unknown (no rows), e.g. parks and streets.
     */
    public Boolean isOpenDuring(LocalDate date, LocalTime arrival, int minutes) {
        if (openingHours.isEmpty()) {
            return null;
        }

        LocalDateTime visitStart = date.atTime(arrival);
        LocalDateTime visitEnd = visitStart.plusMinutes(minutes);

        // The previous day's row matters when it closes after midnight
        for (LocalDate day : List.of(date.minusDays(1), date)) {
            int dayOfWeek = day.getDayOfWeek().getValue();

            for (PlaceOpeningHours hours : openingHours) {
                if (hours.getDayOfWeek() != dayOfWeek) {
                    continue;
                }

                LocalDateTime opens = day.atTime(hours.getOpensAt());
                LocalDateTime closes = hours.closesAfterMidnight()
                        ? day.plusDays(1).atTime(hours.getClosesAt())
                        : day.atTime(hours.getClosesAt());

                if (!visitStart.isBefore(opens) && !visitEnd.isAfter(closes)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasTag(String tag) {
        return tag != null && tags.contains(tag);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public void setNeighborhood(String neighborhood) {
        this.neighborhood = neighborhood;
    }

    public Point getLocation() {
        return location;
    }

    public void setCoordinates(double latitude, double longitude) {
        this.location = GeoPoints.of(latitude, longitude);
    }

    public Double getLatitude() {
        return location == null ? null : location.getY();
    }

    public Double getLongitude() {
        return location == null ? null : location.getX();
    }

    public PlaceCategory getCategory() {
        return category;
    }

    public void setCategory(PlaceCategory category) {
        this.category = category;
    }

    public Integer getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(Integer estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
    }

    public boolean isIndoor() {
        return indoor;
    }

    public void setIndoor(boolean indoor) {
        this.indoor = indoor;
    }

    public Integer getAvgVisitMinutes() {
        return avgVisitMinutes;
    }

    public void setAvgVisitMinutes(Integer avgVisitMinutes) {
        this.avgVisitMinutes = avgVisitMinutes;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<PlaceOpeningHours> getOpeningHours() {
        return openingHours;
    }

    public void replaceOpeningHours(List<PlaceOpeningHours> hours) {
        openingHours.clear();
        hours.forEach(h -> {
            h.setPlace(this);
            openingHours.add(h);
        });
    }
}
