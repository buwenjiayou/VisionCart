package com.visioncart.service.search;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlatformHealthIndicator implements HealthIndicator {

    private final List<PlatformSearchService> platformServices;
    private final PlatformCircuitBreaker circuitBreaker;

    public PlatformHealthIndicator(List<PlatformSearchService> platformServices,
                                   PlatformCircuitBreaker circuitBreaker) {
        this.platformServices = platformServices;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        long availableCount = platformServices.stream()
                .filter(ps -> circuitBreaker.getStatus(ps.platform()) != PlatformCircuitBreaker.Status.OPEN)
                .count();

        Health.Builder builder = availableCount > 0 ? Health.up() : Health.down();

        for (PlatformSearchService ps : platformServices) {
            builder.withDetail(ps.platform(), circuitBreaker.getStatus(ps.platform()).name());
        }

        return builder.build();
    }
}
