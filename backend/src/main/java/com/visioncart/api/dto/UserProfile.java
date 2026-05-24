package com.visioncart.api.dto;

public class UserProfile {
    private Long id;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String createdAt;

    public UserProfile() {}

    public static UserProfile fromEntity(com.visioncart.domain.User user) {
        UserProfile p = new UserProfile();
        p.setId(user.getId());
        p.setEmail(user.getEmail());
        p.setNickname(user.getNickname());
        p.setAvatarUrl(user.getAvatarUrl());
        p.setCreatedAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return p;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
