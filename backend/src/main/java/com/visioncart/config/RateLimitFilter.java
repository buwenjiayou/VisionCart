package com.visioncart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RateLimitService rateLimitService;
    private final VisionCartProperties properties;

    public RateLimitFilter(RateLimitService rateLimitService, VisionCartProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (shouldSkip(request, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        VisionCartProperties.RateLimit cfg = properties.getRateLimit();
        LimitSpec spec = limitSpec(path, cfg);
        String identity = identity(request);
        if (!rateLimitService.allow(spec.scope(), identity, spec.limit(), spec.windowSeconds())) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            OBJECT_MAPPER.writeValue(response.getOutputStream(),
                    ApiResponse.fail(429, "请求过于频繁，请稍后再试"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldSkip(HttpServletRequest request, String path) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals("/api/v1/health")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars")
                || path.startsWith("/actuator")
                || path.startsWith("/ws/")
                || !path.startsWith("/api/");
    }

    private LimitSpec limitSpec(String path, VisionCartProperties.RateLimit cfg) {
        if (path.equals("/api/v1/auth/send-code")) {
            return new LimitSpec("auth", cfg.getAuthLimit(), cfg.getAuthWindowSeconds());
        }
        if (path.equals("/api/v1/recognition/analyze")) {
            return new LimitSpec("recognition", cfg.getRecognitionLimit(), cfg.getRecognitionWindowSeconds());
        }
        if (path.equals("/api/v1/nlp/parse")) {
            return new LimitSpec("nlp", cfg.getRecognitionLimit(), cfg.getRecognitionWindowSeconds());
        }
        return new LimitSpec("api", cfg.getDefaultLimit(), cfg.getDefaultWindowSeconds());
    }

    private String identity(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtAuthenticationFilter.AuthPrincipal principal) {
            return "user:" + principal.getUserId();
        }
        // Always use remoteAddr for rate limiting (not spoofable by client)
        return "ip:" + request.getRemoteAddr();
    }

    private record LimitSpec(String scope, int limit, int windowSeconds) {
    }
}
