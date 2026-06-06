package com.visioncart.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminOnlyFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMetricsForNonAdmin() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setAdminUserIds("7");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(8L, "user@example.com"),
                        null,
                        Collections.emptyList()
                )
        );
        AdminOnlyFilter filter = new AdminOnlyFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsMetricsForAdmin() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setAdminUserIds("7");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "admin@example.com"),
                        null,
                        Collections.emptyList()
                )
        );
        AdminOnlyFilter filter = new AdminOnlyFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
