package com.nomi.wayfinder.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "saved_places")
@IdClass(SavedPlace.Key.class)
public class SavedPlace {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "place_id")
    private Long placeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", insertable = false, updatable = false)
    private Place place;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public SavedPlace() {
    }

    public SavedPlace(Long userId, Long placeId) {
        this.userId = userId;
        this.placeId = placeId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Place getPlace() {
        return place;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static class Key implements Serializable {

        private Long userId;
        private Long placeId;

        public Key() {
        }

        public Key(Long userId, Long placeId) {
            this.userId = userId;
            this.placeId = placeId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(placeId, key.placeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, placeId);
        }
    }
}
