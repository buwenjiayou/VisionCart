package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
            String msg = e.getMessage();
            HttpStatus status = msg != null && msg.contains("频繁") ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), msg));
        } catch (Exception e) {
            log.error("Failed to send verification code", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(500, "发送失败，请稍后再试"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody EmailLoginRequest request) {
        try {
            LoginResponse response = authService.loginWithCode(request.getEmail(), request.getCode());
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, e.getMessage()));
        } catch (Exception e) {
            log.error("Login failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(500, "登录失败，请稍后再试"));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfile>> getProfile() {
        try {
            Long userId = SecurityUtils.currentUserId();
            UserProfile profile = authService.getUserProfile(userId);
            return ResponseEntity.ok(ApiResponse.ok(profile));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(404, e.getMessage()));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@RequestBody java.util.Map<String, String> body) {
        String refreshToken = body.get("refresh_token");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "缺少 refresh_token"));
        }
        try {
            LoginResponse response = authService.refreshToken(refreshToken);
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request,
                                                     @RequestBody(required = false) java.util.Map<String, String> body) {
        String authHeader = request.getHeader("Authorization");
        String accessToken = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
        String refreshToken = body != null ? body.get("refresh_token") : null;
        if (accessToken != null) {
            authService.logout(accessToken, refreshToken);
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
