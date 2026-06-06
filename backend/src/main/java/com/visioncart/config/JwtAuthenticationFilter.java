package com.visioncart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Completely skip: public auth endpoints, health, static resources
        if (path.equals("/api/v1/auth/send-code") ||
            path.equals("/api/v1/auth/login") ||
            path.startsWith("/swagger") ||
            path.startsWith("/v3/api-docs") ||
            path.startsWith("/webjars") ||
            path.equals("/api/v1/health") ||
            path.equals("/actuator/health") ||
            path.startsWith("/actuator/health/") ||
            path.equals("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip non-API, non-actuator paths (static resources, etc.)
        boolean isApiPath = path.startsWith("/api/");
        boolean isActuatorPath = path.startsWith("/actuator/");
        if (!isApiPath && !isActuatorPath) {
            filterChain.doFilter(request, response);
            return;
        }

        // Try to extract and validate JWT
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                Long userId = jwtUtil.getUserId(token);
                String email = jwtUtil.getEmail(token);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                new AuthPrincipal(userId, email), null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        // Require auth for both /api/v1/** and /actuator/** (except health, already skipped above)
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            OBJECT_MAPPER.writeValue(response.getOutputStream(),
                    new ApiResponse<>(401, "未登录或登录已过期", null, null));
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Simple principal to carry userId and email through SecurityContext.
     */
    public record AuthPrincipal(Long userId, String email) {
        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
    }
}
