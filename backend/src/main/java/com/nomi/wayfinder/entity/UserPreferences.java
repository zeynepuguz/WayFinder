package com.nomi.wayfinder.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Defaults used when the user does not say otherwise in a request
@Entity
@Table(name = "user_preferences")
public class UserPreferences {

    @Id
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalkingTolerance walkingTolerance = WalkingTolerance.MEDIUM;

    @Column(nullable = false)
    private int defaultPartySize = 1;

    private Integer defaultBudget;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> interests = new ArrayList<>();

    @Column(nullable = false)
    private Instant updatedAt;

    public UserPreferences() {
    }

    public UserPreferences(Long userId) {
        this.userId = userId;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public WalkingTolerance getWalkingTolerance() {
        return walkingTolerance;
    }

    public void setWalkingTolerance(WalkingTolerance walkingTolerance) {
        this.walkingTolerance = walkingTolerance;
    }

    public int getDefaultPartySize() {
        return defaultPartySize;
    }

    public void setDefaultPartySize(int defaultPartySize) {
        this.defaultPartySize = defaultPartySize;
    }

    public Integer getDefaultBudget() {
        return defaultBudget;
    }

    public void setDefaultBudget(Integer defaultBudget) {
        this.defaultBudget = defaultBudget;
    }

    public List<String> getInterests() {
        return interests;
    }

    public void setInterests(List<String> interests) {
        this.interests = interests == null ? new ArrayList<>() : new ArrayList<>(interests);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
