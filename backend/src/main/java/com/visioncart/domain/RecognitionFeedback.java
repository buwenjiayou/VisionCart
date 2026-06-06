package com.visioncart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "recognition_feedback", indexes = {
    @Index(name = "idx_feedback_session", columnList = "session_id")
})
public class RecognitionFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "image_hash", length = 64)
    private String imageHash;

    @Column(name = "attribute_name", nullable = false, length = 128)
    private String attributeName;

    @Lob
    @Column(name = "vlm_output", columnDefinition = "LONGTEXT")
    private String vlmOutput;

    @Lob
    @Column(name = "user_correction", columnDefinition = "LONGTEXT")
    private String userCorrection;

    @Column(name = "user_id")
    private Long userId;

    private Instant createdAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getImageHash() {
        return imageHash;
    }

    public void setImageHash(String imageHash) {
        this.imageHash = imageHash;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public void setAttributeName(String attributeName) {
        this.attributeName = attributeName;
    }

    public String getVlmOutput() {
        return vlmOutput;
    }

    public void setVlmOutput(String vlmOutput) {
        this.vlmOutput = vlmOutput;
    }

    public String getUserCorrection() {
        return userCorrection;
    }

    public void setUserCorrection(String userCorrection) {
        this.userCorrection = userCorrection;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
