package com.visioncart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class MonitoringTokenFilter extends OncePerRequestFilter {
    public static final String MONITORING_ROLE = "ROLE_MONITORING";
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final VisionCartProperties properties;

    public MonitoringTokenFilter(VisionCartProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!PROMETHEUS_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String expectedToken = properties.getSecurity().getMonitorToken();
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(expectedToken)
                && authHeader != null
                && authHeader.equals(BEARER_PREFIX + expectedToken)) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            "prometheus-monitor",
                            null,
                            List.of(new SimpleGrantedAuthority(MONITORING_ROLE)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
