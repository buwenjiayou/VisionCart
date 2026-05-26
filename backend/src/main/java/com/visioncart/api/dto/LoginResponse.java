package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LoginResponse {
    private String token;
    @JsonProperty("user_id")
    private Long userId;
    private String email;

    public LoginResponse() {}

    public LoginResponse(String token, Long userId, String email) {
        this.token = token;
        this.userId = userId;
        this.email = email;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
