package com.visioncart.service.search;

import com.visioncart.config.VisionCartProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PlatformCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(PlatformCircuitBreaker.class);
    private static final int SLIDING_WINDOW_SIZE = 10;

    private final int failureThreshold;
    private final long openDurationMs;
    private final int halfOpenProbeCount;
    private final long slowCallThresholdMs;
    private final double slowCallRateThreshold;

    private final ConcurrentHashMap<String, CircuitState> states = new ConcurrentHashMap<>();

    public PlatformCircuitBreaker(VisionCartProperties properties) {
        VisionCartProperties.CircuitBreaker cfg = properties.getCircuitBreaker();
        this.failureThreshold = cfg.getFailureThreshold();
        this.openDurationMs = cfg.getOpenDurationMs();
        this.halfOpenProbeCount = cfg.getHalfOpenProbeCount();
        this.slowCallThresholdMs = cfg.getSlowCallThresholdMs();
        this.slowCallRateThreshold = cfg.getSlowCallRateThreshold();
    }

    public boolean allowRequest(String platform) {
        CircuitState state = states.get(platform);
        if (state == null) return true;

        if (state.status == Status.OPEN) {
            synchronized (state) {
                if (state.status == Status.OPEN && System.currentTimeMillis() - state.openedAt > openDurationMs) {
                    state.status = Status.HALF_OPEN;
                    state.halfOpenSuccesses.set(0);
                    state.halfOpenAllowed.set(0);
                    log.info("Circuit half-open for {}", platform);
                    return true;
                }
            }
            return state.status == Status.HALF_OPEN;
        }
        if (state.status == Status.HALF_OPEN) {
            int allowed = state.halfOpenAllowed.incrementAndGet();
            if (allowed < halfOpenProbeCount) {
                return true;
            }
            return false;
        }
        return true;
    }

    public void recordSuccess(String platform, long durationMs) {
        CircuitState state = states.get(platform);
        if (state == null) {
            if (durationMs > slowCallThresholdMs) {
                state = states.computeIfAbsent(platform, k -> new CircuitState());
                recordSlowCall(state, platform);
            }
            return;
        }

        synchronized (state) {
            recordCall(state, platform);
            if (durationMs > slowCallThresholdMs) {
                recordSlowCall(state, platform);
            }

            if (state.status == Status.CLOSED) {
                state.failures.set(0);
            }

            if (state.status == Status.HALF_OPEN) {
                int successes = state.halfOpenSuccesses.incrementAndGet();
                if (successes >= halfOpenProbeCount) {
                    states.remove(platform, state);
                    log.info("Circuit closed for {} after {} successful probes", platform, successes);
                }
            }
        }
    }

    public void recordSuccess(String platform) {
        recordSuccess(platform, 0);
    }

    public void recordFailure(String platform, long durationMs) {
        CircuitState state = states.get(platform);
        if (state == null) {
            state = states.computeIfAbsent(platform, k -> new CircuitState());
        }

        synchronized (state) {
            if (durationMs > slowCallThresholdMs) {
                recordSlowCall(state, platform);
            }

            if (state.status == Status.HALF_OPEN) {
                state.status = Status.OPEN;
                state.openedAt = System.currentTimeMillis();
                log.warn("Circuit re-opened for {} during half-open probe", platform);
                return;
            }
            int failures = state.failures.incrementAndGet();
            if (failures >= failureThreshold) {
                state.status = Status.OPEN;
                state.openedAt = System.currentTimeMillis();
                log.warn("Circuit opened for {} after {} failures", platform, failures);
            }
        }
    }

    public void recordFailure(String platform) {
        recordFailure(platform, 0);
    }

    private void recordSlowCall(CircuitState state, String platform) {
        state.slowCalls.incrementAndGet();
        checkSlowCallRate(state, platform);
    }

    private void recordCall(CircuitState state, String platform) {
        int total = state.totalCalls.incrementAndGet();
        if (total >= SLIDING_WINDOW_SIZE) {
            checkSlowCallRate(state, platform);
        }
    }

    private void checkSlowCallRate(CircuitState state, String platform) {
        int total = state.totalCalls.get();
        if (total >= SLIDING_WINDOW_SIZE) {
            double slowRate = (double) state.slowCalls.get() / total;
            if (slowRate >= slowCallRateThreshold) {
                state.status = Status.OPEN;
                state.openedAt = System.currentTimeMillis();
                state.slowCalls.set(0);
                state.totalCalls.set(0);
                log.warn("Circuit opened for {} due to slow call rate {}% (threshold {}%)",
                        platform, String.format("%.1f", slowRate * 100), String.format("%.1f", slowCallRateThreshold * 100));
            }
        }
    }

    public Status getStatus(String platform) {
        CircuitState state = states.get(platform);
        return state == null ? Status.CLOSED : state.status;
    }

    public enum Status { CLOSED, OPEN, HALF_OPEN }

    private static class CircuitState {
        volatile Status status = Status.CLOSED;
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);
        final AtomicInteger halfOpenAllowed = new AtomicInteger(0);
        final AtomicInteger slowCalls = new AtomicInteger(0);
        final AtomicInteger totalCalls = new AtomicInteger(0);
        volatile long openedAt;
    }
}
