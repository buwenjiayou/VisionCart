package com.visioncart.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceMetricsServiceTest {

    private MeterRegistry registry;
    private PerformanceMetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new PerformanceMetricsService(registry);
    }

    @Test
    void testSearchCacheHitRate() {
        // Given
        metricsService.recordSearchCacheHit();
        metricsService.recordSearchCacheHit();
        metricsService.recordSearchCacheMiss();

        // When
        double hitRate = metricsService.getSearchCacheHitRate();

        // Then
        assertEquals(2.0 / 3.0, hitRate, 0.01);
    }

    @Test
    void testNlpLlmFallbackRate() {
        // Given
        metricsService.recordNlpRequest();
        metricsService.recordNlpRequest();
        metricsService.recordNlpRequest();
        metricsService.recordNlpLlmFallback();

        // When
        double fallbackRate = metricsService.getNlpLlmFallbackRate();

        // Then
        assertEquals(1.0 / 3.0, fallbackRate, 0.01);
    }

    @Test
    void testSearchCacheMetrics() {
        // Given
        metricsService.recordSearchCacheHit();
        metricsService.recordSearchCacheHit();
        metricsService.recordSearchCacheMiss();

        // When
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // Then
        assertNotNull(summary);
        assertEquals(2.0 / 3.0, (Double) summary.get("search.cache_hit_rate"), 0.01);
        assertEquals(2L, summary.get("search.cache_hit_count"));
        assertEquals(1L, summary.get("search.cache_miss_count"));
    }

    @Test
    void testNlpMetrics() {
        // Given
        metricsService.recordNlpRequest();
        metricsService.recordNlpRequest();
        metricsService.recordNlpLlmFallback();

        // When
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // Then
        assertNotNull(summary);
        assertEquals(1.0 / 2.0, (Double) summary.get("nlp.llm_fallback_rate"), 0.01);
        assertEquals(1L, summary.get("nlp.llm_fallback_count"));
        assertEquals(2L, summary.get("nlp.total_requests"));
    }

    @Test
    void testTimerMetrics() throws InterruptedException {
        // Given
        Timer.Sample sample = metricsService.startSearchTotalTimer();
        Thread.sleep(100); // Simulate some work
        metricsService.stopSearchTotalTimer(sample, false);

        // When
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // Then
        assertNotNull(summary);
        Map<String, Object> timers = (Map<String, Object>) summary.get("timers");
        assertNotNull(timers);
        assertTrue(timers.containsKey("search.total_latency.cached=false"));
        String latency = (String) timers.get("search.total_latency.cached=false");
        assertNotNull(latency);
        assertTrue(latency.contains("mean="));
    }

    @Test
    void testCircuitBreakerMetrics() {
        // Given
        metricsService.recordCircuitOpen("pdd");
        metricsService.recordCircuitOpen("pdd");
        metricsService.recordCircuitClose("pdd");
        metricsService.recordCircuitHalfOpen("taobao");

        // When
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // Then
        assertNotNull(summary);
        Map<String, Object> counters = (Map<String, Object>) summary.get("counters");
        assertNotNull(counters);
        assertEquals(2.0, counters.get("platform.circuit_open.pdd"));
        assertEquals(1.0, counters.get("platform.circuit_close.pdd"));
        assertEquals(1.0, counters.get("platform.circuit_half_open.taobao"));
    }

    @Test
    void testCustomTimer() throws InterruptedException {
        // Given
        Timer.Sample sample = metricsService.startTimer("custom.timer", "tag1", "value1");
        Thread.sleep(50); // Simulate some work
        metricsService.stopTimer(sample, "custom.timer", "tag1", "value1");

        // When
        Timer timer = registry.find("custom.timer").timer();

        // Then
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) > 0);
    }

    @Test
    void testCustomCounter() {
        // Given
        metricsService.incrementCounter("custom.counter", "tag1", "value1");
        metricsService.incrementCounter("custom.counter", "tag1", "value1");

        // When
        Counter counter = registry.find("custom.counter").counter();

        // Then
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    @Test
    void testCustomGauge() {
        // Given
        metricsService.setGauge("custom.gauge", 42.0, "tag1", "value1");

        // When
        Double gauge = registry.find("custom.gauge").gauge().value();

        // Then
        assertNotNull(gauge);
        assertEquals(42.0, gauge);
    }

    @Test
    void recordsSearchStabilityMetrics() {
        metricsService.recordSearchInProgress("app");
        metricsService.recordSearchFinalEmpty("all_platform_failed");
        metricsService.recordSearchPlatformOutcome("pdd", "main", "failed", "main_timeout");
        metricsService.recordSearchPlatformProducts("taobao", "main", 12);
        metricsService.recordSearchCacheSkipped("platform_failure");

        assertEquals(1.0, registry.find("search_in_progress")
                .tags("source", "app").counter().count());
        assertEquals(1.0, registry.find("search_final_empty")
                .tags("reason", "all_platform_failed").counter().count());
        assertEquals(1.0, registry.find("search_platform_outcome")
                .tags("platform", "pdd", "stage", "main", "result", "failed", "reason", "timeout")
                .counter().count());
        assertEquals(12.0, registry.find("search_platform_products_count")
                .tags("platform", "taobao", "stage", "main").gauge().value());
        assertEquals(1.0, registry.find("search_cache_skipped")
                .tags("reason", "platform_failure").counter().count());
    }

    @Test
    void recordsRecognitionNlpFavoriteAndPriceMetrics() {
        metricsService.recordRecognitionMultiProductPending();
        metricsService.recordRecognitionDetectedCandidates(3);
        metricsService.recordRecognitionSelectedCandidates(2);
        metricsService.recordRecognitionLowConfidenceCandidateKept(2);
        metricsService.recordRecognitionSingleStageFallback("detect_failed");

        metricsService.recordNlpStatePush();
        metricsService.recordNlpStateUndo();
        metricsService.recordNlpStateClear();
        metricsService.recordNlpZeroResultRollback();
        metricsService.recordNlpPlanConditions("preference", 2);

        metricsService.recordFavoriteCreate("success");
        metricsService.recordPriceRefresh("pdd", "failed");
        metricsService.recordPriceAlertTriggered("target_reached");

        assertEquals(1.0, registry.find("recognition_multi_product_pending").counter().count());
        assertEquals(3.0, registry.find("recognition_detected_candidates_count").gauge().value());
        assertEquals(2.0, registry.find("recognition_selected_candidates_count").gauge().value());
        assertEquals(2.0, registry.find("recognition_low_confidence_candidate_kept").counter().count());
        assertEquals(1.0, registry.find("recognition_single_stage_fallback")
                .tags("reason", "detect_failed").counter().count());

        assertEquals(1.0, registry.find("nlp_state_push").counter().count());
        assertEquals(1.0, registry.find("nlp_state_undo").counter().count());
        assertEquals(1.0, registry.find("nlp_state_clear").counter().count());
        assertEquals(1.0, registry.find("nlp_zero_result_rollback").counter().count());
        assertEquals(2.0, registry.find("nlp_plan_conditions_count")
                .tags("type", "preference").gauge().value());

        assertEquals(1.0, registry.find("favorite_create")
                .tags("result", "success").counter().count());
        assertEquals(1.0, registry.find("price_refresh")
                .tags("platform", "pdd", "result", "failed").counter().count());
        assertEquals(1.0, registry.find("price_alert_triggered")
                .tags("type", "target_reached").counter().count());
    }

    @Test
    void testMetricsSummaryContainsAllSections() {
        // Given
        metricsService.recordSearchCacheHit();
        metricsService.recordNlpRequest();

        // When
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // Then
        assertNotNull(summary);
        assertTrue(summary.containsKey("search.cache_hit_rate"));
        assertTrue(summary.containsKey("search.cache_hit_count"));
        assertTrue(summary.containsKey("search.cache_miss_count"));
        assertTrue(summary.containsKey("nlp.llm_fallback_rate"));
        assertTrue(summary.containsKey("nlp.llm_fallback_count"));
        assertTrue(summary.containsKey("nlp.total_requests"));
        assertTrue(summary.containsKey("timers"));
        assertTrue(summary.containsKey("counters"));
    }
}
