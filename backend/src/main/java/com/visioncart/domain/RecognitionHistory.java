package com.visioncart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "recognition_history", indexes = {
    @Index(name = "idx_history_user", columnList = "user_id"),
    @Index(name = "idx_history_created", columnList = "created_at DESC"),
    @Index(name = "idx_history_user_created", columnList = "user_id, created_at DESC")
})
public class RecognitionHistory {
    @Id
    @Column(length = 64)
    private String sessionId;

    @Column(length = 512)
    private String imageUrl;

    @Column(length = 64)
    private String imageHash;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String categoryJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String attributesJson;

    @Column(length = 512)
    private String keywords;

    private double confidence = 0.0;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Lob
    @Column(name = "display_products_snapshot", columnDefinition = "TEXT")
    private String displayProductsSnapshot;

    @Lob
    @Column(name = "applied_filters_json", columnDefinition = "TEXT")
    private String appliedFiltersJson;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getImageHash() {
        return imageHash;
    }

    public void setImageHash(String imageHash) {
        this.imageHash = imageHash;
    }

    public String getCategoryJson() {
        return categoryJson;
    }

    public void setCategoryJson(String categoryJson) {
        this.categoryJson = categoryJson;
    }

    public String getAttributesJson() {
        return attributesJson;
    }

    public void setAttributesJson(String attributesJson) {
        this.attributesJson = attributesJson;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDisplayProductsSnapshot() {
        return displayProductsSnapshot;
    }

    public void setDisplayProductsSnapshot(String displayProductsSnapshot) {
        this.displayProductsSnapshot = displayProductsSnapshot;
    }

    public String getAppliedFiltersJson() {
        return appliedFiltersJson;
    }

    public void setAppliedFiltersJson(String appliedFiltersJson) {
        this.appliedFiltersJson = appliedFiltersJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
