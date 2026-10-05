package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.PlaceCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PlaceCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 4000)
    private String description;

    // Optional English description (shown to English requests)
    @Size(max = 4000)
    private String descriptionEn;

    @Size(max = 500)
    private String address;

    private String neighborhood;

    @NotNull
    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private Double latitude;

    @NotNull
    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private Double longitude;

    @NotNull
    private PlaceCategory category;

    @PositiveOrZero
    private Integer estimatedCost;

    @DecimalMin(value = "0.0")
    @DecimalMax(value = "5.0")
    private Double rating;

    private boolean indoor;

    @Positive
    @Max(600)
    private Integer avgVisitMinutes;

    private List<@NotBlank String> tags = new ArrayList<>();

    private List<@Valid OpeningHoursDto> openingHours = new ArrayList<>();

    @Size(max = 50)
    private String source;

    @Size(max = 500)
    private String sourceUrl;

    private Instant lastVerifiedAt;

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

    public String getDescriptionEn() {
        return descriptionEn;
    }

    public void setDescriptionEn(String descriptionEn) {
        this.descriptionEn = descriptionEn;
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
}
