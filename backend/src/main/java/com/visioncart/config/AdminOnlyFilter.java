package com.visioncart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AdminOnlyFilter extends OncePerRequestFilter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VisionCartProperties properties;

    public AdminOnlyFilter(VisionCartProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isAdminPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isPrometheusPath(request.getRequestURI()) && hasMonitoringRole()) {
            filterChain.doFilter(request, response);
            return;
        }

        Long userId = currentUserId();
        if (!properties.getSecurity().isAdmin(userId)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            OBJECT_MAPPER.writeValue(response.getOutputStream(),
                    ApiResponse.fail(403, "Admin access required"));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminPath(String path) {
        return path != null && (
                path.startsWith("/api/v1/metrics")
                        || path.startsWith("/actuator/metrics")
                        || path.equals("/actuator/prometheus")
        );
    }

    private boolean isPrometheusPath(String path) {
        return "/actuator/prometheus".equals(path);
    }

    private boolean hasMonitoringRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(MonitoringTokenFilter.MONITORING_ROLE::equals);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof JwtAuthenticationFilter.AuthPrincipal authPrincipal) {
            return authPrincipal.userId();
        }
        if (principal instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
