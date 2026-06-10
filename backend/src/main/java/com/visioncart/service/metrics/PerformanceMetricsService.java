package com.visioncart.service.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一性能指标服务
 * 收集各类延迟、命中率、熔断次数等指标
 */
@Service
public class PerformanceMetricsService {
    private static final Logger log = LoggerFactory.getLogger(PerformanceMetricsService.class);

    private final MeterRegistry registry;
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gaugeCache = new ConcurrentHashMap<>();

    // 搜索缓存命中统计
    private final AtomicLong searchCacheHits = new AtomicLong(0);
    private final AtomicLong searchCacheMisses = new AtomicLong(0);

    // NLP LLM 回退统计
    private final AtomicLong nlpLlmFallbacks = new AtomicLong(0);
    private final AtomicLong nlpTotalRequests = new AtomicLong(0);

    public PerformanceMetricsService(MeterRegistry registry) {
        this.registry = registry;

        // 注册 Gauge 指标
        Gauge.builder("search.cache.hit.count", searchCacheHits, AtomicLong::get)
                .description("搜索缓存命中次数")
                .register(registry);

        Gauge.builder("search.cache.miss.count", searchCacheMisses, AtomicLong::get)
                .description("搜索缓存未命中次数")
                .register(registry);

        Gauge.builder("nlp.llm.fallback.count", nlpLlmFallbacks, AtomicLong::get)
                .description("NLP LLM 回退次数")
                .register(registry);

        Gauge.builder("nlp.total.requests", nlpTotalRequests, AtomicLong::get)
                .description("NLP 总请求数")
                .register(registry);

        log.info("PerformanceMetricsService initialized with Micrometer registry");
    }

    // ==================== 识别相关指标 ====================

    /**
     * 记录识别延迟
     */
    public Timer.Sample startRecognitionTimer() {
        return Timer.start(registry);
    }

    public void stopRecognitionTimer(Timer.Sample sample, boolean success) {
        sample.stop(registry.timer("recognition.latency", "success", String.valueOf(success)));
    }

    public void recordRecognitionMultiProductPending() {
        incrementCounter("recognition_multi_product_pending");
    }

    public void recordRecognitionDetectedCandidates(int count) {
        setGauge("recognition_detected_candidates_count", Math.max(0, count));
    }

    public void recordRecognitionSelectedCandidates(int count) {
        setGauge("recognition_selected_candidates_count", Math.max(0, count));
    }

    public void recordRecognitionLowConfidenceCandidateKept(int count) {
        if (count > 0) {
            incrementCounterBy("recognition_low_confidence_candidate_kept", count);
        }
    }

    public void recordRecognitionSingleStageFallback(String reason) {
        incrementCounter("recognition_single_stage_fallback",
                "reason", reasonTag(reason));
    }

    /**
     * 记录视觉模型延迟
     */
    public Timer.Sample startVisionModelTimer() {
        return Timer.start(registry);
    }

    public void stopVisionModelTimer(Timer.Sample sample, String model, boolean success) {
        sample.stop(registry.timer("vision_model.latency", "model", model, "success", String.valueOf(success)));
    }

    // ==================== 搜索相关指标 ====================

    /**
     * 记录总搜索延迟
     */
    public Timer.Sample startSearchTotalTimer() {
        return Timer.start(registry);
    }

    public void stopSearchTotalTimer(Timer.Sample sample, boolean cached) {
        sample.stop(registry.timer("search.total_latency", "cached", String.valueOf(cached)));
    }

    /**
     * 记录平台搜索延迟
     */
    public Timer.Sample startPlatformSearchTimer() {
        return Timer.start(registry);
    }

    public void stopPlatformSearchTimer(Timer.Sample sample, String platform, boolean success) {
        sample.stop(registry.timer("search.platform_latency", "platform", platform, "success", String.valueOf(success)));
    }

    /**
     * 记录搜索缓存命中
     */
    public void recordSearchCacheHit() {
        searchCacheHits.incrementAndGet();
        registry.counter("search.cache", "result", "hit").increment();
    }

    /**
     * 记录搜索缓存未命中
     */
    public void recordSearchCacheMiss() {
        searchCacheMisses.incrementAndGet();
        registry.counter("search.cache", "result", "miss").increment();
    }

    public void recordSearchInProgress(String source) {
        incrementCounter("search_in_progress",
                "source", tagValue(source));
    }

    public void recordSearchFinalEmpty(String reason) {
        incrementCounter("search_final_empty",
                "reason", reasonTag(reason));
    }

    public void recordSearchPlatformOutcome(String platform, String stage, String result, String reason) {
        incrementCounter("search_platform_outcome",
                "platform", tagValue(platform),
                "stage", tagValue(stage),
                "result", tagValue(result),
                "reason", reasonTag(reason));
    }

    public void recordSearchPlatformProducts(String platform, String stage, int count) {
        setGauge("search_platform_products_count", Math.max(0, count),
                "platform", tagValue(platform),
                "stage", tagValue(stage));
    }

    public void recordSearchCacheSkipped(String reason) {
        incrementCounter("search_cache_skipped",
                "reason", reasonTag(reason));
    }

    /**
     * 获取搜索缓存命中率
     */
    public double getSearchCacheHitRate() {
        long hits = searchCacheHits.get();
        long misses = searchCacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    // ==================== NLP 相关指标 ====================

    /**
     * 记录 NLP 解析延迟
     */
    public Timer.Sample startNlpParseTimer() {
        return Timer.start(registry);
    }

    public void stopNlpParseTimer(Timer.Sample sample, String engine, boolean success) {
        sample.stop(registry.timer("nlp.parse_latency", "engine", engine, "success", String.valueOf(success)));
    }

    /**
     * 记录 NLP LLM 回退
     */
    public void recordNlpLlmFallback() {
        nlpLlmFallbacks.incrementAndGet();
        registry.counter("nlp.llm_fallback").increment();
    }

    /**
     * 记录 NLP 请求
     */
    public void recordNlpRequest() {
        nlpTotalRequests.incrementAndGet();
        registry.counter("nlp.requests").increment();
    }

    public void recordNlpStatePush() {
        incrementCounter("nlp_state_push");
    }

    public void recordNlpStateUndo() {
        incrementCounter("nlp_state_undo");
    }

    public void recordNlpStateClear() {
        incrementCounter("nlp_state_clear");
    }

    public void recordNlpZeroResultRollback() {
        incrementCounter("nlp_zero_result_rollback");
    }

    public void recordNlpPlanConditions(String type, int count) {
        setGauge("nlp_plan_conditions_count", Math.max(0, count),
                "type", tagValue(type));
    }

    /**
     * 获取 NLP LLM 回退率
     */
    public double getNlpLlmFallbackRate() {
        long fallbacks = nlpLlmFallbacks.get();
        long total = nlpTotalRequests.get();
        return total > 0 ? (double) fallbacks / total : 0.0;
    }

    // ==================== 候选过滤相关指标 ====================

    /**
     * 记录候选过滤延迟
     */
    public Timer.Sample startCandidateFilterTimer() {
        return Timer.start(registry);
    }

    public void stopCandidateFilterTimer(Timer.Sample sample, int inputCount, int outputCount) {
        sample.stop(registry.timer("candidate.filter_latency"));
        registry.gauge("candidate.input_count", inputCount);
        registry.gauge("candidate.output_count", outputCount);
        registry.gauge("candidate.filter_rate", inputCount > 0 ? (double) outputCount / inputCount : 1.0);
    }

    // ==================== 平台熔断相关指标 ====================

    /**
     * 记录平台熔断器打开
     */
    public void recordCircuitOpen(String platform) {
        counterCache.computeIfAbsent("platform.circuit_open." + platform,
                k -> Counter.builder("platform.circuit_open_count")
                        .description("平台熔断器打开次数")
                        .tag("platform", platform)
                        .register(registry))
                .increment();
    }

    /**
     * 记录平台熔断器关闭
     */
    public void recordCircuitClose(String platform) {
        counterCache.computeIfAbsent("platform.circuit_close." + platform,
                k -> Counter.builder("platform.circuit_close_count")
                        .description("平台熔断器关闭次数")
                        .tag("platform", platform)
                        .register(registry))
                .increment();
    }

    /**
     * 记录平台熔断器半开
     */
    public void recordCircuitHalfOpen(String platform) {
        counterCache.computeIfAbsent("platform.circuit_half_open." + platform,
                k -> Counter.builder("platform.circuit_half_open_count")
                        .description("平台熔断器半开次数")
                        .tag("platform", platform)
                        .register(registry))
                .increment();
    }

    // ==================== 搜索质量指标 (P1-1) ====================

    /**
     * 记录搜索首屏精确率（EXACT_MAIN 在 top N 中的比例）
     */
    public void recordSearchFirstScreenExactRate(String productFamily, double rate) {
        setGauge("search.first_screen_exact_main_rate", rate,
                "family", StringUtils.defaultString(productFamily, "unknown"));
    }

    /**
     * 记录搜索 related/accessory 比例
     */
    public void recordSearchRelatedRate(String productFamily, double rate) {
        setGauge("search.related_accessory_rate", rate,
                "family", StringUtils.defaultString(productFamily, "unknown"));
    }

    /**
     * 记录搜索零结果
     */
    public void recordSearchZeroResult(String productFamily, String strategy) {
        incrementCounter("search.zero_result",
                "family", StringUtils.defaultString(productFamily, "unknown"),
                "strategy", StringUtils.defaultString(strategy, "unknown"));
    }

    /**
     * 记录搜索 fallback（策略放宽）
     */
    public void recordSearchFallbackUsed(String productFamily, String strategy) {
        incrementCounter("search.fallback_used",
                "family", StringUtils.defaultString(productFamily, "unknown"),
                "strategy", StringUtils.defaultString(strategy, "unknown"));
    }

    /**
     * 记录品牌放宽
     */
    public void recordSearchBrandRelax(String productFamily, String strategy) {
        incrementCounter("search.brand_relax",
                "family", StringUtils.defaultString(productFamily, "unknown"),
                "strategy", StringUtils.defaultString(strategy, "unknown"));
    }

    /**
     * 记录 LLM Judge 超时
     */
    public void recordNlpJudgeTimeout() {
        incrementCounter("nlp.judge_timeout");
    }

    // ==================== AI 导购卡指标 ====================

    /**
     * 记录 AI 导购卡生成延迟
     */
    public Timer.Sample startDeepSuggestionTimer() {
        return Timer.start(registry);
    }

    public void stopDeepSuggestionTimer(Timer.Sample sample, String result) {
        sample.stop(registry.timer("deep_suggestion.latency", "result", result));
    }

    /**
     * 记录 AI 导购卡生成成功
     */
    public void recordDeepSuggestionGenerated() {
        registry.counter("deep_suggestion.generated_count").increment();
    }

    /**
     * 记录 AI 导购卡超时降级
     */
    public void recordDeepSuggestionTimeout() {
        registry.counter("deep_suggestion.timeout_count").increment();
    }

    /**
     * 记录 AI 导购卡规则降级
     */
    public void recordDeepSuggestionFallback() {
        registry.counter("deep_suggestion.fallback_count").increment();
    }

    // ==================== 收藏与价格提醒指标 ====================

    public void recordFavoriteCreate(String result) {
        incrementCounter("favorite_create",
                "result", tagValue(result));
    }

    public void recordPriceRefresh(String platform, String result) {
        incrementCounter("price_refresh",
                "platform", tagValue(platform),
                "result", tagValue(result));
    }

    public void recordPriceAlertTriggered(String type) {
        incrementCounter("price_alert_triggered",
                "type", tagValue(type));
    }

    // ==================== 通用指标工具 ====================

    /**
     * 记录自定义计时器
     */
    public Timer.Sample startTimer(String name, String... tags) {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String name, String... tags) {
        sample.stop(registry.timer(name, tags));
    }

    /**
     * 记录自定义计数器
     */
    public void incrementCounter(String name, String... tags) {
        counterCache.computeIfAbsent(name + String.join("", tags),
                k -> Counter.builder(name)
                        .tags(tags)
                        .register(registry))
                .increment();
    }

    public void incrementCounterBy(String name, double amount, String... tags) {
        if (amount <= 0) {
            return;
        }
        counterCache.computeIfAbsent(name + String.join("", tags),
                k -> Counter.builder(name)
                        .tags(tags)
                        .register(registry))
                .increment(amount);
    }

    /**
     * 记录自定义 Gauge
     */
    public void setGauge(String name, double value, String... tags) {
        gaugeCache.computeIfAbsent(name + String.join("", tags),
                k -> {
                    AtomicLong gauge = new AtomicLong((long) value);
                    Gauge.builder(name, gauge, AtomicLong::get)
                            .tags(tags)
                            .register(registry);
                    return gauge;
                })
                .set((long) value);
    }

    private String tagValue(String value) {
        if (StringUtils.isBlank(value)) {
            return "unknown";
        }
        if (value.contains("淘宝") || value.contains("淘寶")) {
            return "taobao";
        }
        if (value.contains("拼多多")) {
            return "pdd";
        }
        if (value.contains("京东") || value.contains("京東")) {
            return "jd";
        }
        if (value.toLowerCase(Locale.ROOT).contains("ebay")) {
            return "ebay";
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private String reasonTag(String reason) {
        if (StringUtils.isBlank(reason)) {
            return "none";
        }
        String value = reason.toLowerCase(Locale.ROOT);
        if (value.contains("timeout")) {
            return "timeout";
        }
        if (value.contains("queue_full")) {
            return "queue_full";
        }
        if (value.contains("circuit_open")) {
            return "circuit_open";
        }
        if (value.startsWith("error:")) {
            return "error";
        }
        if (value.contains("collection_failed")) {
            return "collection_failed";
        }
        return tagValue(reason);
    }

    /**
     * 获取所有指标摘要
     */
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();

        // 搜索缓存指标
        summary.put("search.cache_hit_rate", getSearchCacheHitRate());
        summary.put("search.cache_hit_rate_percent", String.format("%.2f%%", getSearchCacheHitRate() * 100));
        summary.put("search.cache_hit_count", searchCacheHits.get());
        summary.put("search.cache_miss_count", searchCacheMisses.get());

        // NLP 指标
        summary.put("nlp.llm_fallback_rate", getNlpLlmFallbackRate());
        summary.put("nlp.llm_fallback_rate_percent", String.format("%.2f%%", getNlpLlmFallbackRate() * 100));
        summary.put("nlp.llm_fallback_count", nlpLlmFallbacks.get());
        summary.put("nlp.total_requests", nlpTotalRequests.get());

        // Timer 指标（带格式化）
        Map<String, Object> timers = new java.util.LinkedHashMap<>();
        registry.find("recognition.latency").timers().forEach(timer ->
                timers.put("recognition.latency", formatTimer(timer)));
        registry.find("vision_model.latency").timers().forEach(timer -> {
            String model = timer.getId().getTag("model");
            timers.put("vision_model.latency" + (model != null ? "." + model : ""), formatTimer(timer));
        });
        registry.find("search.total_latency").timers().forEach(timer -> {
            String cached = timer.getId().getTag("cached");
            timers.put("search.total_latency" + (cached != null ? ".cached=" + cached : ""), formatTimer(timer));
        });
        registry.find("search.platform_latency").timers().forEach(timer -> {
            String platform = timer.getId().getTag("platform");
            String success = timer.getId().getTag("success");
            if (platform != null) {
                timers.put("search.platform_latency." + platform + ".success=" + success, formatTimer(timer));
            }
        });
        registry.find("nlp.parse_latency").timers().forEach(timer -> {
            String engine = timer.getId().getTag("engine");
            timers.put("nlp.parse_latency" + (engine != null ? "." + engine : ""), formatTimer(timer));
        });
        registry.find("candidate.filter_latency").timers().forEach(timer ->
                timers.put("candidate.filter_latency", formatTimer(timer)));
        summary.put("timers", timers);

        // Deep suggestion 指标
        Map<String, Object> deepSuggestion = new java.util.LinkedHashMap<>();
        registry.find("deep_suggestion.latency").timers().forEach(timer -> {
            String result = timer.getId().getTag("result");
            deepSuggestion.put("latency" + (result != null ? "." + result : ""), formatTimer(timer));
        });
        registry.find("deep_suggestion.generated_count").counters().forEach(counter ->
                deepSuggestion.put("generated_count", counter.count()));
        registry.find("deep_suggestion.timeout_count").counters().forEach(counter ->
                deepSuggestion.put("timeout_count", counter.count()));
        registry.find("deep_suggestion.fallback_count").counters().forEach(counter ->
                deepSuggestion.put("fallback_count", counter.count()));
        if (!deepSuggestion.isEmpty()) summary.put("deep_suggestion", deepSuggestion);

        // Counter 指标
        Map<String, Object> counters = new java.util.LinkedHashMap<>();
        registry.find("platform.circuit_open_count").counters().forEach(counter -> {
            String platform = counter.getId().getTag("platform");
            if (platform != null) counters.put("platform.circuit_open." + platform, counter.count());
        });
        registry.find("platform.circuit_close_count").counters().forEach(counter -> {
            String platform = counter.getId().getTag("platform");
            if (platform != null) counters.put("platform.circuit_close." + platform, counter.count());
        });
        registry.find("platform.circuit_half_open_count").counters().forEach(counter -> {
            String platform = counter.getId().getTag("platform");
            if (platform != null) counters.put("platform.circuit_half_open." + platform, counter.count());
        });
        summary.put("counters", counters);

        // Per-platform 统计
        Map<String, Object> platformStats = new java.util.LinkedHashMap<>();
        registry.find("search.platform_latency").timers().forEach(timer -> {
            String platform = timer.getId().getTag("platform");
            if (platform != null && timer.count() > 0) {
                Map<String, Object> stat = new java.util.LinkedHashMap<>();
                stat.put("total_calls", timer.count());
                stat.put("mean_latency_ms", String.format("%.2f", timer.mean(TimeUnit.MILLISECONDS)));
                stat.put("p95_latency_ms", String.format("%.2f", timer.percentile(0.95, TimeUnit.MILLISECONDS)));
                stat.put("max_latency_ms", String.format("%.2f", timer.max(TimeUnit.MILLISECONDS)));
                platformStats.put(platform, stat);
            }
        });
        summary.put("platform_stats", platformStats);

        return summary;
    }

    private String formatTimer(Timer timer) {
        return String.format("mean=%.2fms, p95=%.2fms, count=%d",
                timer.mean(TimeUnit.MILLISECONDS),
                timer.percentile(0.95, TimeUnit.MILLISECONDS),
                timer.count());
    }

    /**
     * 打印指标摘要到日志
     */
    public void logMetricsSummary() {
        Map<String, Object> summary = getMetricsSummary();
        log.info("=== Performance Metrics Summary ===");
        log.info("Search Cache Hit Rate: {}", String.format("%.2f%%", getSearchCacheHitRate() * 100));
        log.info("NLP LLM Fallback Rate: {}", String.format("%.2f%%", getNlpLlmFallbackRate() * 100));

        if (summary.containsKey("timers")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> timers = (Map<String, Object>) summary.get("timers");
            timers.forEach((key, value) -> log.info("  {}: {}", key, value));
        }

        if (summary.containsKey("counters")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> counters = (Map<String, Object>) summary.get("counters");
            counters.forEach((key, value) -> log.info("  {}: {}", key, value));
        }

        if (summary.containsKey("platform_stats")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> platformStats = (Map<String, Object>) summary.get("platform_stats");
            log.info("--- Per-Platform Stats ---");
            platformStats.forEach((platform, statObj) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> stat = (Map<String, Object>) statObj;
                log.info("  {}: calls={}, mean={}ms, p95={}ms, max={}ms",
                        platform, stat.get("total_calls"), stat.get("mean_latency_ms"),
                        stat.get("p95_latency_ms"), stat.get("max_latency_ms"));
            });
        }

        log.info("===================================");
    }
}
