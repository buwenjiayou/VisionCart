package com.visioncart.service.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiTraceServiceTest {

    private SimpleMeterRegistry registry;
    private AiTraceService traceService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        traceService = new AiTraceService(registry);
    }

    @Test
    void startIncrementsCallCounter() {
        traceService.start("nlp.parse", Map.of("input", "test"));
        Counter counter = registry.find("ai.task.calls").tag("task", "nlp.parse").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void finishRecordsLatency() throws Exception {
        String traceId = traceService.start("vision.recognition", Map.of());
        Thread.sleep(10);
        traceService.finish("vision.recognition", traceId, "doubao_vision");

        Timer latency = registry.find("ai.task.latency")
                .tag("task", "vision.recognition")
                .tag("decision", "doubao_vision")
                .timer();
        assertThat(latency).isNotNull();
        assertThat(latency.count()).isEqualTo(1);
        assertThat(latency.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    @Test
    void failIncrementsFailureCounter() {
        String traceId = traceService.start("nlp.parse", Map.of());
        traceService.fail("nlp.parse", traceId, new RuntimeException("timeout"), "none");

        Counter failures = registry.find("ai.task.failures")
                .tag("task", "nlp.parse")
                .tag("error", "RuntimeException")
                .counter();
        assertThat(failures).isNotNull();
        assertThat(failures.count()).isEqualTo(1.0);
    }

    @Test
    void multipleCallsAccumulateMetrics() {
        traceService.start("nlp.parse", Map.of());
        traceService.start("nlp.parse", Map.of());

        Counter counter = registry.find("ai.task.calls").tag("task", "nlp.parse").counter();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void finishRecordsDurationTimer() throws Exception {
        String traceId = traceService.start("vision.recognition", Map.of());
        Thread.sleep(10);
        traceService.finish("vision.recognition", traceId, "doubao_vision");

        Timer duration = registry.find("ai.task.duration")
                .tag("task", "vision.recognition")
                .timer();
        assertThat(duration).isNotNull();
        assertThat(duration.count()).isEqualTo(1);
    }
}
