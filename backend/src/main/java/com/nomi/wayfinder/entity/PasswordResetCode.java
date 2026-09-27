package com.nomi.wayfinder.entity;

import jakarta.persistence.*;

import java.time.Instant;

// A 6-digit "forgot password" code; only its hash is stored
@Entity
@Table(name = "password_reset_codes")
public class PasswordResetCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetCode() {
    }

    public PasswordResetCode(Long userId, String codeHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now, int maxAttempts) {
        return usedAt == null && attempts < maxAttempts && expiresAt.isAfter(now);
    }

    public void recordFailedAttempt() {
        attempts++;
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
