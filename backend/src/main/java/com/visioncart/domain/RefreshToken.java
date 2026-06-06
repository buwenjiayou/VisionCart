package com.visioncart.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_token", indexes = {
    @Index(name = "idx_rt_user", columnList = "user_id"),
    @Index(name = "idx_rt_token_hash", columnList = "token_hash")
})
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Device/client info for display in "active sessions" */
    @Column(length = 256)
    private String deviceInfo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }

    @Transient
    public boolean isRevoked() { return revokedAt != null; }

    @Transient
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    @Transient
    public boolean isValid() { return !isRevoked() && !isExpired(); }
}
