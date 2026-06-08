package com.visioncart.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MonitoringTokenFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesPrometheusRequestWithMonitorToken() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setMonitorToken("monitor-secret");
        MonitoringTokenFilter filter = new MonitoringTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer monitor-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly(MonitoringTokenFilter.MONITORING_ROLE);
    }

    @Test
    void ignoresWrongMonitorToken() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setMonitorToken("monitor-secret");
        MonitoringTokenFilter filter = new MonitoringTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresBlankConfiguredMonitorToken() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setMonitorToken("");
        MonitoringTokenFilter filter = new MonitoringTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNotAuthenticateOtherPaths() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getSecurity().setMonitorToken("monitor-secret");
        MonitoringTokenFilter filter = new MonitoringTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/metrics/summary");
        request.addHeader("Authorization", "Bearer monitor-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
