package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter.AuthPrincipal;
import com.visioncart.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Send verification code to email
     * POST /api/v1/auth/send-code
     */
    @PostMapping("/send-code")
    public ResponseEntity<ApiResponse<Void>> sendCode(@RequestBody SendCodeRequest request) {
        try {
            authService.sendCode(request.getEmail());
            return ResponseEntity.ok(ApiResponse.ok(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail(500, "发送失败: " + e.getMessage()));
        }
    }

    /**
     * Login / Register with email + verification code
     * POST /api/v1/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody EmailLoginRequest request) {
        try {
            LoginResponse response = authService.loginWithCode(request.getEmail(), request.getCode());
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(400, e.getMessage()));
        }
    }

    /**
     * Get current user profile
     * GET /api/v1/auth/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfile>> getProfile() {
        try {
            Long userId = getCurrentUserId();
            UserProfile profile = authService.getUserProfile(userId);
            return ResponseEntity.ok(ApiResponse.ok(profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(404, e.getMessage()));
        }
    }

    /**
     * Update nickname
     * PUT /api/v1/auth/profile
     */
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfile>> updateProfile(@RequestBody Map<String, String> body) {
        try {
            Long userId = getCurrentUserId();
            String nickname = body.get("nickname");
            if (nickname == null || nickname.isBlank()) {
                return ResponseEntity.ok(ApiResponse.fail(400, "昵称不能为空"));
            }
            UserProfile profile = authService.updateNickname(userId, nickname);
            return ResponseEntity.ok(ApiResponse.ok(profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(404, e.getMessage()));
        }
    }

    /**
     * Logout
     * POST /api/v1/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.getUserId();
        }
        throw new IllegalArgumentException("未登录");
    }
}
