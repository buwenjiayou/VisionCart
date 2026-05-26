package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter.AuthPrincipal;
import com.visioncart.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/send-code")
    public ResponseEntity<ApiResponse<Void>> sendCode(@Valid @RequestBody SendCodeRequest request) {
        try {
            authService.sendCode(request.getEmail());
            return ResponseEntity.ok(ApiResponse.ok(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(400, e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to send verification code", e);
            return ResponseEntity.ok(ApiResponse.fail(500, "发送失败，请稍后再试"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody EmailLoginRequest request) {
        try {
            LoginResponse response = authService.loginWithCode(request.getEmail(), request.getCode());
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(400, e.getMessage()));
        } catch (Exception e) {
            log.error("Login failed", e);
            return ResponseEntity.ok(ApiResponse.fail(500, "登录失败，请稍后再试"));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfile>> getProfile() {
        try {
            Long userId = getCurrentUserId();
            UserProfile profile = authService.getUserProfile(userId);
            return ResponseEntity.ok(ApiResponse.ok(profile));
        } catch (SecurityException e) {
            return ResponseEntity.ok(ApiResponse.fail(401, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(404, e.getMessage()));
        }
    }

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
        throw new SecurityException("未登录");
    }
}
