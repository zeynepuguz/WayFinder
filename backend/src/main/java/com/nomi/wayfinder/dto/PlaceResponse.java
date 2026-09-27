package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.PlaceCategory;

import java.time.Instant;
import java.util.List;

public class PlaceResponse {

    private Long id;
    private String name;
    private String description;
    private String address;
    private String neighborhood;
    private Double latitude;
    private Double longitude;
    private PlaceCategory category;
    private Integer estimatedCost;
    private Double rating;
    private boolean indoor;
    private Integer avgVisitMinutes;
    private List<String> tags;
    private List<OpeningHoursDto> openingHours;
    // null = opening hours unknown
    private Boolean openNow;
    private String source;
    private Instant lastVerifiedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
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
        this.tags = tags;
    }

    public List<OpeningHoursDto> getOpeningHours() {
        return openingHours;
    }

    public void setOpeningHours(List<OpeningHoursDto> openingHours) {
        this.openingHours = openingHours;
    }

    public Boolean getOpenNow() {
        return openNow;
    }

    public void setOpenNow(Boolean openNow) {
        this.openNow = openNow;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }
}
