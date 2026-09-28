package com.nomi.wayfinder.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "assistant_messages")
public class AssistantMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    // The chat (AssistantConversation) this message belongs to
    @Column(nullable = false)
    private Long conversationId;

    private Long routeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AssistantMessage() {
    }

    public AssistantMessage(Long userId, Long conversationId, Long routeId, MessageRole role, String content) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.routeId = routeId;
        this.role = role;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getRouteId() {
        return routeId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
