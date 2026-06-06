package com.visioncart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets correlation ID, user ID, and request path in MDC for structured logging.
 * MDC values are automatically included in log output when using a JSON encoder.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    public static final String CORRELATION_ID = "correlationId";
    public static final String USER_ID = "userId";
    public static final String ENDPOINT = "endpoint";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(CORRELATION_ID, correlationId);
        MDC.put(ENDPOINT, request.getMethod() + " " + request.getRequestURI());

        // Set userId if authenticated
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                Object principal = auth.getPrincipal();
                if (principal instanceof JwtAuthenticationFilter.AuthPrincipal authPrincipal) {
                    MDC.put(USER_ID, String.valueOf(authPrincipal.userId()));
                } else if (principal instanceof Long userId) {
                    MDC.put(USER_ID, String.valueOf(userId));
                }
            }
        } catch (Exception ignored) {}

        response.setHeader("X-Correlation-Id", correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = response.getStatus();
            log.info("request completed: status={}, latencyMs={}", status, latencyMs);
            MDC.clear();
        }
    }
}
