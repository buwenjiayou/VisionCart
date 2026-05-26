package com.visioncart.service.search;

import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCircuitBreakerTest {

    private PlatformCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        VisionCartProperties props = new VisionCartProperties();
        // Use short timeouts for testing
        props.getCircuitBreaker().setFailureThreshold(3);
        props.getCircuitBreaker().setOpenDurationMs(100);
        props.getCircuitBreaker().setHalfOpenProbeCount(2);
        circuitBreaker = new PlatformCircuitBreaker(props);
    }

    @Test
    void closedByDefault() {
        assertThat(circuitBreaker.allowRequest("taobao")).isTrue();
        assertThat(circuitBreaker.getStatus("taobao")).isEqualTo(PlatformCircuitBreaker.Status.CLOSED);
    }

    @Test
    void opensAfterThresholdFailures() {
        circuitBreaker.recordFailure("taobao");
        circuitBreaker.recordFailure("taobao");
        assertThat(circuitBreaker.allowRequest("taobao")).isTrue(); // not yet

        circuitBreaker.recordFailure("taobao");
        assertThat(circuitBreaker.allowRequest("taobao")).isFalse(); // now open
        assertThat(circuitBreaker.getStatus("taobao")).isEqualTo(PlatformCircuitBreaker.Status.OPEN);
    }

    @Test
    void halfOpenAfterDuration() throws Exception {
        // Open the circuit
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure("pdd");
        assertThat(circuitBreaker.allowRequest("pdd")).isFalse();

        // Wait for open duration to expire
        Thread.sleep(150);

        assertThat(circuitBreaker.allowRequest("pdd")).isTrue(); // half-open, first probe
        assertThat(circuitBreaker.getStatus("pdd")).isEqualTo(PlatformCircuitBreaker.Status.HALF_OPEN);
    }

    @Test
    void halfOpenClosesAfterSuccessfulProbes() throws Exception {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure("ebay");
        Thread.sleep(150);

        // First probe
        circuitBreaker.allowRequest("ebay");
        circuitBreaker.recordSuccess("ebay");
        assertThat(circuitBreaker.getStatus("ebay")).isEqualTo(PlatformCircuitBreaker.Status.HALF_OPEN);

        // Second probe - should close
        circuitBreaker.allowRequest("ebay");
        circuitBreaker.recordSuccess("ebay");
        assertThat(circuitBreaker.getStatus("ebay")).isEqualTo(PlatformCircuitBreaker.Status.CLOSED);
    }

    @Test
    void halfOpenReopensOnFailure() throws Exception {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure("jd");
        Thread.sleep(150);

        circuitBreaker.allowRequest("jd"); // enter half-open
        circuitBreaker.recordFailure("jd"); // fail during probe
        assertThat(circuitBreaker.allowRequest("jd")).isFalse(); // back to open
        assertThat(circuitBreaker.getStatus("jd")).isEqualTo(PlatformCircuitBreaker.Status.OPEN);
    }

    @Test
    void halfOpenLimitsProbeRequests() throws Exception {
        for (int i = 0; i < 3; i++) circuitBreaker.recordFailure("taobao");
        Thread.sleep(150);

        // Allow up to halfOpenProbeCount (2) requests
        assertThat(circuitBreaker.allowRequest("taobao")).isTrue();
        assertThat(circuitBreaker.allowRequest("taobao")).isTrue();
        assertThat(circuitBreaker.allowRequest("taobao")).isFalse(); // exceeded
    }

    @Test
    void successResetsFailureCount() {
        circuitBreaker.recordFailure("pdd");
        circuitBreaker.recordFailure("pdd");
        circuitBreaker.recordSuccess("pdd"); // reset
        circuitBreaker.recordFailure("pdd");
        circuitBreaker.recordFailure("pdd");
        assertThat(circuitBreaker.allowRequest("pdd")).isTrue(); // only 2 failures since reset
    }
}
