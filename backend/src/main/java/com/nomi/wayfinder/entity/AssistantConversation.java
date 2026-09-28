package com.nomi.wayfinder.entity;

import jakarta.persistence.*;

import java.time.Instant;

// One assistant chat ("Sohbetler"). The route it planned is the only route its messages may change
@Entity
@Table(name = "assistant_conversations")
public class AssistantConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    // null until the first message (or a rename)
    private String title;

    private Long routeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    public AssistantConversation() {
    }

    public AssistantConversation(Long userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (lastMessageAt == null) {
            lastMessageAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }
}
