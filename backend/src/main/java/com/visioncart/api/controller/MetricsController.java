package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.service.metrics.PerformanceMetricsService;
import com.visioncart.service.search.PlatformCircuitBreaker;
import com.visioncart.config.VisionCartProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 性能指标控制器
 * 提供统一的性能指标查询接口
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "性能指标查询接口")
public class MetricsController {

    private final PerformanceMetricsService metricsService;
    private final PlatformCircuitBreaker circuitBreaker;
    private final VisionCartProperties properties;

    public MetricsController(PerformanceMetricsService metricsService,
                            PlatformCircuitBreaker circuitBreaker,
                            VisionCartProperties properties) {
        this.metricsService = metricsService;
        this.circuitBreaker = circuitBreaker;
        this.properties = properties;
    }

    @GetMapping("/summary")
    @Operation(summary = "获取性能指标摘要", description = "返回所有关键性能指标的摘要信息")
    public ApiResponse<Map<String, Object>> getMetricsSummary() {
        Map<String, Object> summary = metricsService.getMetricsSummary();

        // 添加平台配置信息
        Map<String, Object> platformConfigs = new LinkedHashMap<>();
        properties.getPlatforms().all().forEach(entry ->
                platformConfigs.put(entry.getKey(), entry.getValue()));
        summary.put("platform_configs", platformConfigs);

        // 添加熔断器状态
        Map<String, String> circuitStates = new LinkedHashMap<>();
        properties.getPlatforms().all().forEach(entry ->
                circuitStates.put(entry.getKey(), circuitBreaker.getStatus(entry.getKey()).name()));
        summary.put("circuit_breaker_states", circuitStates);

        return ApiResponse.ok(summary);
    }

    @GetMapping("/search")
    @Operation(summary = "获取搜索性能指标", description = "返回搜索相关的性能指标")
    public ApiResponse<Map<String, Object>> getSearchMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 搜索缓存命中率
        metrics.put("cache_hit_rate", metricsService.getSearchCacheHitRate());
        metrics.put("cache_hit_rate_percent", String.format("%.2f%%", metricsService.getSearchCacheHitRate() * 100));

        return ApiResponse.ok(metrics);
    }

    @GetMapping("/nlp")
    @Operation(summary = "获取 NLP 性能指标", description = "返回 NLP 相关的性能指标")
    public ApiResponse<Map<String, Object>> getNlpMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // NLP LLM 回退率
        metrics.put("llm_fallback_rate", metricsService.getNlpLlmFallbackRate());
        metrics.put("llm_fallback_rate_percent", String.format("%.2f%%", metricsService.getNlpLlmFallbackRate() * 100));

        return ApiResponse.ok(metrics);
    }

    @GetMapping("/platforms")
    @Operation(summary = "获取平台能力配置表", description = "返回各平台的配置和熔断器状态")
    public ApiResponse<Map<String, Object>> getPlatformMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 平台能力配置表
        properties.getPlatforms().all().forEach(entry -> {
            VisionCartProperties.Platform p = entry.getValue();
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("enabled", p.isEnabled());
            cfg.put("timeoutMs", p.getTimeoutMs());
            cfg.put("weight", p.getWeight());
            cfg.put("fallbackEnabled", p.isFallbackEnabled());
            cfg.put("fallbackPriority", p.getFallbackPriority());
            cfg.put("regionStrategy", p.getRegionStrategy());
            cfg.put("maxRetries", p.getMaxRetries());
            cfg.put("retryDelayMs", p.getRetryDelayMs());
            cfg.put("rateLimitPerSecond", p.getRateLimitPerSecond());
            cfg.put("circuitBreakerEnabled", p.isCircuitBreakerEnabled());
            cfg.put("circuitState", circuitBreaker.getStatus(entry.getKey()).name());
            metrics.put(entry.getKey(), cfg);
        });

        return ApiResponse.ok(metrics);
    }

    @GetMapping("/log")
    @Operation(summary = "打印指标摘要到日志", description = "将性能指标摘要打印到服务器日志")
    public ApiResponse<String> logMetricsSummary() {
        metricsService.logMetricsSummary();
        return ApiResponse.ok("Metrics summary logged to server console");
    }
}
