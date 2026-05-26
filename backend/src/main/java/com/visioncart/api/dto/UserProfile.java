package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UserProfile {
    private Long id;
    private String email;
    @JsonProperty("created_at")
    private String createdAt;

    public UserProfile() {}

    public static UserProfile fromEntity(com.visioncart.domain.User user) {
        UserProfile p = new UserProfile();
        p.setId(user.getId());
        p.setEmail(user.getEmail());
        p.setCreatedAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return p;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
