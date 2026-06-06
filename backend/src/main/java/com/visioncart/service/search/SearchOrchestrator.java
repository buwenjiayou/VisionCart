package com.visioncart.service.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.ai.HashUtils;
import com.visioncart.service.metrics.PerformanceMetricsService;
import com.visioncart.service.suggestion.SuggestionCardCache;
import com.visioncart.service.suggestion.SuggestionService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SearchOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SearchOrchestrator.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String CACHE_PREFIX = "visioncart:search:cache:v2:";
    private static final String SEARCH_LOCK_PREFIX = "visioncart:search:lock:";
    private static final String SEARCH_RUN_PREFIX = "visioncart:search:run:";
    private static final Duration SEARCH_LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration SEARCH_RUN_TTL = Duration.ofMinutes(2);
    private static final int MIN_RESULT_TARGET = 30;

    private final List<PlatformSearchService> platformServices;
    private final ProductDeduplicator deduplicator;
    private final RelevanceRanker ranker;
    private final SuggestionService suggestionService;
    private final com.visioncart.service.suggestion.DeepSuggestionService deepSuggestionService;
    private final ExecutorService platformSearchExecutor;
    private final ExecutorService aiSuggestionExecutor;
    private final Duration platformTimeout;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformCircuitBreaker circuitBreaker;
    private final RegionResolver regionResolver;
    private final RecognitionHistoryRepository recognitionHistoryRepository;
    private final CandidateSessionCache sessionCache;
    private final SuggestionCardCache suggestionCardCache;
    private final SimpMessagingTemplate messagingTemplate;
    private final PerformanceMetricsService metricsService;
    private final VisionCartProperties properties;
    private final ProductSortService productSortService;
    private final ProductReputationService reputationService;
    private final Map<String, String> localSearchRuns = new java.util.concurrent.ConcurrentHashMap<>();

    public SearchOrchestrator(List<PlatformSearchService> platformServices,
                              ProductDeduplicator deduplicator,
                              RelevanceRanker ranker,
                              SuggestionService suggestionService,
                              com.visioncart.service.suggestion.DeepSuggestionService deepSuggestionService,
                              @Qualifier("platformSearchExecutor") ExecutorService platformSearchExecutor,
                              @Qualifier("aiSuggestionExecutor") ExecutorService aiSuggestionExecutor,
                              VisionCartProperties properties,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              PlatformCircuitBreaker circuitBreaker,
                              RegionResolver regionResolver,
                              RecognitionHistoryRepository recognitionHistoryRepository,
                              CandidateSessionCache sessionCache,
                              SuggestionCardCache suggestionCardCache,
                              SimpMessagingTemplate messagingTemplate,
                              PerformanceMetricsService metricsService,
                              ProductSortService productSortService,
                              ProductReputationService reputationService) {
        this.platformServices = platformServices;
        this.deduplicator = deduplicator;
        this.ranker = ranker;
        this.suggestionService = suggestionService;
        this.deepSuggestionService = deepSuggestionService;
        this.platformSearchExecutor = platformSearchExecutor;
        this.aiSuggestionExecutor = aiSuggestionExecutor;
        this.platformTimeout = Duration.ofMillis(properties.getSearch().getPlatformTimeoutMs());
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.regionResolver = regionResolver;
        this.recognitionHistoryRepository = recognitionHistoryRepository;
        this.sessionCache = sessionCache;
        this.suggestionCardCache = suggestionCardCache;
        this.messagingTemplate = messagingTemplate;
        this.metricsService = metricsService;
        this.properties = properties;
        this.productSortService = productSortService;
        this.reputationService = reputationService;
    }

    /** Enrich products with ratingDisplayLabel from ProductReputationService */
    private List<ProductCard> enrichRatingLabels(List<ProductCard> products) {
        if (products == null || products.isEmpty()) return products;
        return products.stream()
                .map(p -> {
                    String label = reputationService.score(p).displayLabel();
                    return (label != null && !label.equals("暂无评分"))
                            ? p.withRatingDisplayLabel(label) : p;
                })
                .toList();
    }

    public SearchResult search(SearchRequest request) {
        return search(request, regionResolver.isDomestic());
    }

    public SearchResult search(SearchRequest request, Long userId) {
        return search(request, regionResolver.isDomestic(), userId);
    }

    public SearchResult search(SearchRequest request, boolean domestic) {
        return search(request, domestic, null);
    }

    public SearchResult search(SearchRequest request, boolean domestic, Long userId) {
        // Idempotent lock: prevent duplicate searches for the same session
        String sessionId = request.sessionId();
        String lockKey = null;
        String lockValue = null;
        boolean lockAcquired = false;

        if (sessionId != null && !sessionId.isBlank()) {
            // Lock key includes filter+attributes+recallSize hash so different searches don't collide
            String lockInput = request.effectiveFilter().toString()
                    + "|" + (request.attributes() != null ? request.attributes().toString() : "")
                    + "|recall=" + request.effectiveRecallSize();
            String lockHash = HashUtils.md5Hex(lockInput);
            lockKey = SEARCH_LOCK_PREFIX + sessionId + ":" + lockHash.substring(0, 8);
            lockValue = java.util.UUID.randomUUID().toString();
            try {
                lockAcquired = Boolean.TRUE.equals(
                        redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, SEARCH_LOCK_TTL));
            } catch (Exception e) {
                log.warn("Redis lock check failed, proceeding without lock: {}", e.getMessage());
            }

            if (!lockAcquired) {
                // Another search is in progress. Wait for the session cache, but do not return a false empty
                // result if the original platform search is simply slower than this request.
                log.info("Search lock held for session {}, waiting for cached result", sessionId);
                // Wait long enough for a full multi-platform search to complete (not just one platform)
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10_000);
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    List<ProductCard> sessionCached = sessionCache.getCandidates(sessionId);
                    if (!sessionCached.isEmpty()) {
                        int pageSize = targetPageSize(request);
                        List<ProductCard> page = enrichRatingLabels(sessionCached.subList(0, Math.min(pageSize, sessionCached.size())));
                        List<SuggestionCard> lockCards = suggestionService.cards(request.clientType(), page);
                        return new SearchResult(sessionCached.size(), page, List.of(), lockCards);
                    }
                }
                log.warn("Search lock wait expired for session {}, running duplicate search instead of returning empty", sessionId);
            }
        }

        try {
            String searchRunToken = beginSearchRun(sessionId);
            return doSearch(request, domestic, userId, searchRunToken);
        } finally {
            // Only release lock if this thread acquired it (atomic check-and-delete via Lua)
            if (lockAcquired && lockKey != null) {
                try {
                    org.springframework.data.redis.core.script.DefaultRedisScript<Long> releaseScript =
                            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                                    "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
                                    Long.class);
                    Long released = redisTemplate.execute(releaseScript,
                            List.of(lockKey), lockValue);
                    if (released == null || released == 0L) {
                        log.warn("Lock release failed or already expired for key {}", lockKey);
                    }
                } catch (Exception e) {
                    log.warn("Lock release failed for key {}: {}", lockKey, e.getMessage());
                }
            }
        }
    }

    private SearchResult doSearch(SearchRequest request, boolean domestic, Long userId, String searchRunToken) {
        // Start total search timer
        io.micrometer.core.instrument.Timer.Sample searchTimer = metricsService.startSearchTotalTimer();

        Map<String, String> attributes = enrichAttributes(request, userId);
        SearchFilter filter = request.effectiveFilter();
        SearchIntent intent = SearchIntent.from(attributes, filter);

        // Check cache (cache full ranked list, paginate on hit)
        String cacheKey = buildCacheKey(attributes, filter, domestic, request.effectiveRecallSize());
        List<ProductCard> cached = getFromCache(cacheKey);
        if (cached != null) {
            // Record cache hit
            metricsService.recordSearchCacheHit();
            metricsService.stopSearchTotalTimer(searchTimer, true);

            // Write to session cache even on global cache hit (for NLP filtering)
            if (request.sessionId() != null && !request.sessionId().isBlank()
                    && isCurrentSearchRun(request.sessionId(), searchRunToken)) {
                sessionCache.saveCandidates(request.sessionId(), cached);
            }
            // Apply pagination before diversification
            int pageNum = Math.max(1, request.effectivePage());
            int pageSize = targetPageSize(request);
            int from = (pageNum - 1) * pageSize;
            List<ProductCard> pageSlice = from >= cached.size() ? List.of()
                    : cached.subList(from, Math.min(cached.size(), from + pageSize));
            List<ProductCard> page = enrichRatingLabels(diversifyPlatforms(pageSlice, pageSize));
            List<SuggestionCard> cards = mergeInsightCards(request.sessionId(), searchRunToken, request.clientType(), cached, attributes, filter,
                    suggestionService.cards(request.clientType(), page, attributes, filter));
            return new SearchResult(cached.size(), page, stats(cached), cards);
        }

        // Record cache miss
        metricsService.recordSearchCacheMiss();

        int targetCount = targetPageSize(request);
        int recallSize = request.effectiveRecallSize();

        // Parallel fetch: all platforms concurrently, push partial results via WebSocket as each completes
        // Filter by region strategy (platform config) and domesticOnly (interface default)
        List<PlatformSearchService> eligible = platformServices.stream()
                .filter(ps -> {
                    VisionCartProperties.Platform cfg = properties.getPlatforms().getPlatform(ps.platform());
                    return cfg.isEnabled() && matchesRegionStrategy(cfg, domestic);
                })
                .toList();
        String sessionId = request.sessionId();

        // Submit all platform searches in parallel via CompletionService (completion-order collection)
        java.util.concurrent.CompletionService<List<ProductCard>> completionService =
                new java.util.concurrent.ExecutorCompletionService<>(platformSearchExecutor);
        int submittedCount = 0;
        for (PlatformSearchService ps : eligible) {
            try {
                completionService.submit(() -> fetchSinglePlatform(ps, attributes, filter, request));
                submittedCount++;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("{} search skipped: executor queue full", ps.platform());
            }
        }

        // Collect results as each platform completes — push staged results via WebSocket
        List<ProductCard> all = new ArrayList<>();
        for (int i = 0; i < submittedCount; i++) {
            try {
                List<ProductCard> platformResult = completionService.take().get(
                        platformTimeout.toMillis() + 500, TimeUnit.MILLISECONDS);
                if (platformResult != null && !platformResult.isEmpty()) {
                    all.addAll(platformResult);
                    // Push partial results to client via WebSocket (staged delivery)
                    if (sessionId != null && !sessionId.isBlank()
                            && isCurrentSearchRun(sessionId, searchRunToken)) {
                        sendSearchProgress(sessionId, all, request, searchRunToken);
                    }
                }
            } catch (Exception e) {
                log.warn("Platform search result collection failed: {}", e.getMessage());
            }
        }

        log.info("Search results: {} raw products from {} platforms, attributes={}", all.size(), eligible.size(), attributes);

        List<ProductCard> scored = ranker.withSimilarity(all, intent);
        List<ProductCard> deduped = deduplicator.deduplicate(scored);
        List<ProductCard> afterFilter = applyFilter(deduped, filter);
        List<ProductCard> filtered = applyIntentFilter(afterFilter, intent, targetCount);
        if (filtered.size() < targetCount) {
            Map<String, String> supplementalAttributes = supplementalAttributes(attributes);
            if (!supplementalAttributes.equals(attributes)) {
                // Parallel supplemental recall
                java.util.concurrent.CompletionService<List<ProductCard>> suppCompletion =
                        new java.util.concurrent.ExecutorCompletionService<>(platformSearchExecutor);
                int suppSubmitted = 0;
                for (PlatformSearchService ps : eligible) {
                    try {
                        suppCompletion.submit(() -> fetchSinglePlatform(ps, supplementalAttributes, filter, request));
                        suppSubmitted++;
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        log.warn("{} supplemental search skipped: executor queue full", ps.platform());
                    }
                }
                List<ProductCard> supplemental = new ArrayList<>();
                for (int i = 0; i < suppSubmitted; i++) {
                    try {
                        List<ProductCard> suppResult = suppCompletion.take().get(
                                platformTimeout.toMillis() + 500, TimeUnit.MILLISECONDS);
                        if (suppResult != null) supplemental.addAll(suppResult);
                    } catch (Exception e) {
                        log.warn("Supplemental platform search failed: {}", e.getMessage());
                    }
                }
                if (!supplemental.isEmpty()) {
                    List<ProductCard> merged = new ArrayList<>(all);
                    merged.addAll(supplemental);
                    scored = ranker.withSimilarity(merged, intent);
                    deduped = deduplicator.deduplicate(scored);
                    afterFilter = applyFilter(deduped, filter);
                    filtered = applyIntentFilter(afterFilter, intent, targetCount);
                    all = merged;
                    log.info("Supplemental recall: {} products, intentFiltered={}", supplemental.size(), filtered.size());
                }
            }
        }
        List<ProductCard> ranked = applySort(ranker.rank(filtered, intent), filter);
        log.info("Filter pipeline: {} raw -> {} deduped -> {} afterFilter -> {} intentFiltered -> {} ranked",
                all.size(), deduped.size(), afterFilter.size(), filtered.size(), ranked.size());

        // 品牌降级：只有品牌不可靠时才去掉品牌重试，避免可靠品牌识别后返回错品牌。
        // 用户手动修正的品牌、verified=true 的品牌都不允许降级。
        boolean relaxed = false;
        if (ranked.isEmpty() && !intent.strictIntent() && !intent.brand().isBlank() && !intent.hasReliableBrand()) {
            log.info("Brand/intent filter returned 0 results, retrying without brand: {}", intent.brand());
            Map<String, String> relaxedAttributes = new LinkedHashMap<>(attributes);
            relaxedAttributes.remove(SearchTextUtils.ATTR_BRAND);
            relaxedAttributes.remove(SearchTextUtils.ATTR_BRAND_RELIABLE);
            SearchIntent relaxedIntent = SearchIntent.from(relaxedAttributes, filter);
            List<ProductCard> relaxedScored = ranker.withSimilarity(all, relaxedIntent);
            List<ProductCard> relaxedFiltered = applyIntentFilter(
                    applyFilter(deduplicator.deduplicate(relaxedScored), filter),
                    relaxedIntent, targetCount);
            ranked = applySort(ranker.rank(relaxedFiltered, relaxedIntent), filter);
            relaxed = !ranked.isEmpty();
            if (relaxed) {
                log.info("Brand fallback found {} results", ranked.size());
            }
        }

        if (ranked.isEmpty()
                && !afterFilter.isEmpty()
                && !intent.strictIntent()
                && (intent.brand().isBlank() || !intent.hasReliableBrand())) {
            ranked = applySort(ranker.rank(afterFilter, intent), filter);
            relaxed = true;
            log.info("Intent fallback returning {} user-filtered products from {} raw products",
                    ranked.size(), all.size());
        }

        // Save final candidates AFTER all fallbacks — ensures cache reflects best available pool.
        List<ProductCard> rawPage = diversifyPlatforms(ranked, targetCount);
        if (sessionId != null && !sessionId.isBlank() && isCurrentSearchRun(sessionId, searchRunToken)) {
            sessionCache.saveCandidates(sessionId, ranked);
            // Send final WebSocket message with staging=false to indicate search completion
            // IMPORTANT: send page (展示列表), not ranked (全量候选)
            try {
                var doneMsg = new com.visioncart.api.dto.SearchProgressMessage(sessionId, rawPage, ranked.size(), false);
                messagingTemplate.convertAndSend("/topic/search/" + sessionId, doneMsg);
            } catch (Exception e) {
                log.debug("Failed to send search completion message: {}", e.getMessage());
            }
        }
        List<SuggestionCard> cards = mergeInsightCards(request.sessionId(), searchRunToken, request.clientType(), ranked, attributes, filter,
                suggestionService.cards(request.clientType(), rawPage, attributes, filter));

        // Write cache
        putToCache(cacheKey, ranked);

        // Stop total search timer
        metricsService.stopSearchTotalTimer(searchTimer, false);

        List<ProductCard> page = enrichRatingLabels(rawPage);
        return new SearchResult(ranked.size(), page, stats(ranked), cards, relaxed);
    }

    private String beginSearchRun(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String token = java.util.UUID.randomUUID().toString();
        localSearchRuns.put(sessionId, token);
        try {
            redisTemplate.opsForValue().set(SEARCH_RUN_PREFIX + sessionId, token, SEARCH_RUN_TTL);
        } catch (Exception e) {
            log.debug("Redis unavailable for search run token, using local memory: {}", e.getMessage());
        }
        return token;
    }

    private boolean isCurrentSearchRun(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank() || token == null || token.isBlank()) {
            return true;
        }
        try {
            String current = redisTemplate.opsForValue().get(SEARCH_RUN_PREFIX + sessionId);
            if (current != null) {
                return token.equals(current);
            }
        } catch (Exception e) {
            log.debug("Redis unavailable for search run token check, using local memory: {}", e.getMessage());
        }
        return token.equals(localSearchRuns.get(sessionId));
    }

    private int targetPageSize(SearchRequest request) {
        return request.effectivePageSize();
    }

    private static final long INSIGHT_TIMEOUT_MS = 800;
    private static final int INSIGHT_POOL_LIMIT = 300;

    private List<SuggestionCard> mergeInsightCards(String sessionId, String searchRunToken, String clientType, List<ProductCard> ranked,
                                                    Map<String, String> attributes, SearchFilter filter,
                                                    List<SuggestionCard> baseCards) {
        // Fire-and-forget: AI 导购卡异步生成，不阻塞搜索主链路。
        // 结果缓存到 sessionCache，客户端通过 /suggestions/cards 拉取。
        try {
            List<ProductCard> pool = ranked.stream().limit(INSIGHT_POOL_LIMIT).toList();
            if (isCurrentSearchRun(sessionId, searchRunToken)) {
                suggestionCardCache.save(sessionId, baseCards);
            }
            CompletableFuture.runAsync(() -> {
                try {
                    if (!isCurrentSearchRun(sessionId, searchRunToken)) {
                        return;
                    }
                    List<SuggestionCard> insights = deepSuggestionService.insightCards(clientType, pool, attributes, filter);
                    if (!insights.isEmpty() && isCurrentSearchRun(sessionId, searchRunToken)) {
                        log.info("AI insight cards generated: {} cards", insights.size());
                        // Merge with baseCards and cache for /suggestions/cards endpoint
                        java.util.Set<String> existingIds = baseCards.stream()
                                .map(SuggestionCard::id).collect(Collectors.toSet());
                        List<SuggestionCard> merged = new java.util.ArrayList<>(baseCards);
                        insights.stream()
                                .filter(card -> existingIds.add(card.id()))
                                .forEach(merged::add);
                        suggestionCardCache.save(sessionId, merged);
                    }
                } catch (Exception e) {
                    log.debug("Deep suggestion background task failed: {}", e.getMessage());
                }
            }, aiSuggestionExecutor);
        } catch (Exception e) {
            log.debug("Deep suggestion scheduling failed (non-critical): {}", e.getMessage());
        }
        // Return baseCards immediately — never block the search response
        return baseCards;
    }

    /**
     * 判断平台是否适配当前区域策略
     */
    private boolean matchesRegionStrategy(VisionCartProperties.Platform config, boolean domestic) {
        String strategy = config.getRegionStrategy();
        if (strategy == null || "global".equals(strategy)) return true;
        if ("domestic".equals(strategy)) return domestic;
        if ("international".equals(strategy)) return !domestic;
        return true;
    }

    /**
     * 同步执行单个平台搜索（含重试、熔断、指标）。
     * 调用方应确保在异步线程中调用（如 CompletionService.submit），
     * 本方法内部不再开子线程，避免同一线程池嵌套导致饥饿。
     */
    private List<ProductCard> fetchSinglePlatform(PlatformSearchService ps,
                                                   Map<String, String> attributes,
                                                   SearchFilter filter,
                                                   SearchRequest request) {
        // Check if platform is enabled via config
        VisionCartProperties.Platform platformConfig = properties.getPlatforms().getPlatform(ps.platform());
        if (!platformConfig.isEnabled()) {
            log.info("Platform {} disabled via config, skipping", ps.platform());
            return List.of();
        }

        // Region strategy filtering (replaces simple domesticOnly() check)
        boolean domestic = regionResolver.isDomestic();
        if (!matchesRegionStrategy(platformConfig, domestic)) {
            log.info("Platform {} regionStrategy={} not suitable for {} request, skipping",
                    ps.platform(), platformConfig.getRegionStrategy(), domestic ? "domestic" : "international");
            return List.of();
        }

        boolean circuitBreakerEnabled = platformConfig.isCircuitBreakerEnabled();
        if (circuitBreakerEnabled && !circuitBreaker.allowRequest(ps.platform())) {
            log.info("Circuit open, skipping {}", ps.platform());
            return List.of();
        }

        int maxRetries = platformConfig.getMaxRetries();
        long retryDelayMs = platformConfig.getRetryDelayMs();

        // Start platform search timer
        io.micrometer.core.instrument.Timer.Sample platformTimer = metricsService.startPlatformSearchTimer();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("Platform {} retry attempt {}/{}", ps.platform(), attempt, maxRetries);
            }

            long start = System.nanoTime();
            try {
                // 直接同步调用平台搜索，不再内部 supplyAsync（外层 CompletionService 已在异步线程）
                List<ProductCard> result = ps.search(attributes, filter, request.effectivePage(), request.effectiveRecallSize());
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                if (circuitBreakerEnabled) {
                    circuitBreaker.recordSuccess(ps.platform(), durationMs);
                }
                metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), true);

                // Apply weight: multiply result relevance scores by platform weight
                double weight = platformConfig.getWeight();
                if (weight != 1.0 && !result.isEmpty()) {
                    result = result.stream()
                            .map(p -> p.withWeightedScore(weight))
                            .toList();
                }

                log.info("Platform {} returned {} products in {}ms (attempt {}, weight={})",
                        ps.platform(), result.size(), durationMs, attempt + 1, weight);
                return result;
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                if (circuitBreakerEnabled) {
                    circuitBreaker.recordFailure(ps.platform(), durationMs);
                }
                if (attempt >= maxRetries) {
                    metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
                    log.warn("Platform {} search error after {} attempts: {}", ps.platform(), attempt + 1, e.getMessage());
                    return List.of();
                }
                log.warn("Platform {} search error on attempt {}: {}", ps.platform(), attempt + 1, e.getMessage());
            }
        }

        metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
        return List.of();
    }

    private void sendSearchProgress(String sessionId, List<ProductCard> products, SearchRequest request, String searchRunToken) {
        try {
            if (!isCurrentSearchRun(sessionId, searchRunToken)) {
                return;
            }
            int pageSize = targetPageSize(request);
            // 轻量净化：去重 + 基本有效性过滤（有标题、价格 > 0）+ 平台均衡
            List<ProductCard> deduped = deduplicator.deduplicate(products);
            List<ProductCard> valid = deduped.stream()
                    .filter(p -> p.title() != null && !p.title().isBlank())
                    .filter(p -> p.price() != null && p.price().compareTo(java.math.BigDecimal.ZERO) > 0)
                    .toList();
            List<ProductCard> page = diversifyPlatforms(valid, pageSize);
            var msg = new com.visioncart.api.dto.SearchProgressMessage(sessionId, page, products.size(), true);
            messagingTemplate.convertAndSend("/topic/search/" + sessionId, msg);
            log.debug("Sent search progress for session {}: {} raw -> {} deduped -> {} page", sessionId, products.size(), valid.size(), page.size());
        } catch (Exception e) {
            log.warn("Failed to send search progress for session {}: {}", sessionId, e.getMessage());
        }
    }

    private Map<String, String> supplementalAttributes(Map<String, String> attributes) {
        Map<String, String> source = attributes == null ? Map.of() : attributes;
        Map<String, String> relaxed = new LinkedHashMap<>();
        copyIfPresent(source, relaxed, SearchTextUtils.ATTR_CATEGORY_CHAIN);
        copyIfPresent(source, relaxed, SearchTextUtils.ATTR_CATEGORY);
        copyIfPresent(source, relaxed, SearchTextUtils.ATTR_KEYWORD);
        copyIfPresent(source, relaxed, SearchTextUtils.ATTR_KEYWORDS);
        return relaxed;
    }

    private void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (StringUtils.isNotBlank(SearchTextUtils.useful(value))) {
            target.put(key, value);
        }
    }

    private Map<String, String> enrichAttributes(SearchRequest request, Long userId) {
        Map<String, String> attributes = new LinkedHashMap<>(
                request.attributes() == null ? Map.of() : request.attributes());
        if (StringUtils.isBlank(request.sessionId())) {
            return attributes;
        }

        boolean needsCategory = StringUtils.isBlank(SearchTextUtils.useful(attributes.get("类目")));
        boolean needsKeyword = StringUtils.isBlank(SearchTextUtils.useful(attributes.get("关键词")));

        (userId == null
                ? recognitionHistoryRepository.findById(request.sessionId())
                : recognitionHistoryRepository.findBySessionIdAndUserId(request.sessionId(), userId)).ifPresent(history -> {
            mergeHistoryAttributes(attributes, history);
            if (needsCategory) {
                putIfUsefulMissing(attributes, "类目", categoryName(history.getCategoryJson()));
            }
            List<String> keywords = SearchTextUtils.splitSearchTerms(history.getKeywords());
            if (!keywords.isEmpty()) {
                attributes.put(SearchTextUtils.ATTR_KEYWORDS, String.join(",", keywords));
                if (needsKeyword) {
                    putIfUsefulMissing(attributes, "关键词", keywords.get(0));
                }
            }
        });
        return attributes;
    }

    private void mergeHistoryAttributes(Map<String, String> attributes, RecognitionHistory history) {
        readAttributes(history.getAttributesJson()).forEach((key, value) -> {
            if (value == null) {
                return;
            }
            putIfUsefulMissing(attributes, key, value.value());
            if (SearchTextUtils.ATTR_BRAND.equals(key)
                    && StringUtils.isNotBlank(SearchTextUtils.useful(value.value()))
                    && (value.verified() || value.confidence() >= 0.7)) {
                attributes.put(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");
            }
        });
        String categoryChain = categoryChain(history.getCategoryJson());
        if (StringUtils.isNotBlank(categoryChain)) {
            attributes.putIfAbsent(SearchTextUtils.ATTR_CATEGORY_CHAIN, categoryChain);
        }
    }

    private Map<String, AttributeValue> readAttributes(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, AttributeValue>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse recognition attributes for search: {}", e.getMessage());
            return Map.of();
        }
    }

    private String categoryName(String categoryJson) {
        if (StringUtils.isBlank(categoryJson)) {
            return "";
        }
        try {
            JsonNode category = objectMapper.readTree(categoryJson);
            return firstNonBlank(
                    category.path("level3").asText(""),
                    category.path("level2").asText(""),
                    category.path("level1").asText(""));
        } catch (Exception e) {
            log.warn("Failed to parse recognition category for search: {}", e.getMessage());
            return "";
        }
    }

    private String categoryChain(String categoryJson) {
        if (StringUtils.isBlank(categoryJson)) {
            return "";
        }
        try {
            JsonNode category = objectMapper.readTree(categoryJson);
            return List.of(
                            category.path("level3").asText(""),
                            category.path("level2").asText(""),
                            category.path("level1").asText(""))
                    .stream()
                    .map(SearchTextUtils::useful)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .collect(Collectors.joining(","));
        } catch (Exception e) {
            log.warn("Failed to parse recognition category chain for search: {}", e.getMessage());
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private void putIfUsefulMissing(Map<String, String> attributes, String key, String value) {
        if (StringUtils.isNotBlank(SearchTextUtils.useful(attributes.get(key)))) {
            return;
        }
        String useful = SearchTextUtils.useful(value);
        if (StringUtils.isNotBlank(useful)) {
            attributes.put(key, useful);
        }
    }

    // --- Cache ---

    private String buildCacheKey(Map<String, String> attributes, SearchFilter filter, boolean domestic, int recallSize) {
        String raw;
        try {
            Map<String, String> sorted = new java.util.TreeMap<>(attributes);
            raw = objectMapper.writeValueAsString(sorted) + objectMapper.writeValueAsString(filter)
                    + (domestic ? ":CN" : ":INTL") + ":recall=" + recallSize;
        } catch (JsonProcessingException e) {
            raw = new java.util.TreeMap<>(attributes).toString() + filter.toString()
                    + (domestic ? ":CN" : ":INTL") + ":recall=" + recallSize;
        }
        return CACHE_PREFIX + HashUtils.md5Hex(raw);
    }

    private List<ProductCard> getFromCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("Cache read failed: {}", e.getMessage());
        }
        return null;
    }

    private void putToCache(String key, List<ProductCard> products) {
        if (products == null || products.isEmpty()) return; // Don't cache empty results
        try {
            String json = objectMapper.writeValueAsString(products);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }
    }

    // --- Filter / Sort / Diversify / Stats (unchanged) ---

    private List<ProductCard> diversifyPlatforms(List<ProductCard> products, int pageSize) {
        List<ProductCard> page = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        Map<String, List<ProductCard>> byPlatform = products.stream()
                .collect(Collectors.groupingBy(ProductCard::platform, java.util.LinkedHashMap::new, Collectors.toList()));

        byPlatform.values().forEach(platformProducts -> {
            if (!platformProducts.isEmpty() && page.size() < pageSize) {
                page.add(platformProducts.get(0));
                seenIds.add(platformProducts.get(0).id());
            }
        });

        products.stream()
                .filter(product -> seenIds.add(product.id()))
                .limit(Math.max(0, pageSize - page.size()))
                .forEach(page::add);
        return page;
    }

    private List<ProductCard> applyFilter(List<ProductCard> products, SearchFilter filter) {
        String positiveKeyword = SearchTextUtils.positiveKeyword(filter.keyword());
        List<String> negativeTerms = SearchTextUtils.negativeTerms(filter.keyword());
        Map<String, String> excludeAttrs = filter.attributes() == null ? Map.of() :
                filter.attributes().entrySet().stream()
                        .filter(e -> e.getKey().startsWith("exclude_"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, String> includeAttrs = filter.attributes() == null ? Map.of() :
                filter.attributes().entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("exclude_"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return products.stream()
                .filter(product -> filter.platforms() == null || filter.platforms().isEmpty() || filter.platforms().contains(product.platform()))
                .filter(product -> filter.selfOperated() == null || filter.selfOperated() == product.selfOperated())
                .filter(product -> filter.ratingMin() == null || product.rating() == 0 || product.rating() >= filter.ratingMin())
                .filter(product -> filter.priceRange() == null || filter.priceRange().min() == null || product.price() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().min())) >= 0)
                .filter(product -> filter.priceRange() == null || filter.priceRange().max() == null || product.price() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().max())) <= 0)
                .filter(product -> StringUtils.isBlank(positiveKeyword)
                        || productText(product).contains(positiveKeyword.toLowerCase()))
                .filter(product -> negativeTerms.isEmpty()
                        || negativeTerms.stream().noneMatch(term -> productText(product).contains(term.toLowerCase())))
                .filter(product -> filter.colors() == null || filter.colors().isEmpty()
                        || filter.colors().stream().anyMatch(color -> textMatches(product, color)))
                .filter(product -> filter.brands() == null || filter.brands().isEmpty() || filter.brands().stream().anyMatch(brand -> matchesBrand(product, brand)))
                // Structured attributes: all include attrs must match, no exclude attrs may match
                .filter(product -> matchesAttributes(product, includeAttrs))
                .filter(product -> !matchesExcludeAttributes(product, excludeAttrs))
                // Exclude roles: filter out products matching any excluded productRole
                .filter(product -> filter.excludeRoles() == null || filter.excludeRoles().isEmpty()
                        || !filter.excludeRoles().contains(product.productRole().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    private boolean matchesAttributes(ProductCard product, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return true;
        String text = productText(product);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank() && !text.contains(value.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesExcludeAttributes(ProductCard product, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return false;
        String text = productText(product);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank() && text.contains(value.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBrand(ProductCard product, String brand) {
        String useful = SearchTextUtils.useful(brand);
        if (useful.isBlank()) {
            return true;
        }
        String productBrand = SearchTextUtils.useful(product.brand());
        return BrandMatcher.sameBrand(useful, productBrand)
                || BrandMatcher.productMatchesExpectedBrand(product, useful);
    }

    private boolean textMatches(ProductCard product, String token) {
        String useful = SearchTextUtils.useful(token);
        return useful.isBlank() || productText(product).contains(useful.toLowerCase());
    }

    private String productText(ProductCard product) {
        return String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.brand()),
                StringUtils.defaultString(product.shopName()),
                String.join(" ", product.tags() == null ? List.of() : product.tags())
        ).toLowerCase();
    }

    private List<ProductCard> applyIntentFilter(List<ProductCard> products, SearchIntent intent, int targetCount) {
        if (!intent.hasSpecificSignals()) {
            return products;
        }

        List<ProductCard> strong = products.stream()
                .filter(product -> ranker.tier(product, intent) == RelevanceRanker.RelevanceTier.STRONG)
                .toList();
        List<ProductCard> safeFill = products.stream()
                .filter(product -> ranker.tier(product, intent) == RelevanceRanker.RelevanceTier.SAFE_FILL)
                .toList();
        log.info("Intent tiers: {} products -> {} strong, {} safeFill, target={}, core='{}'",
                products.size(), strong.size(), safeFill.size(), targetCount, intent.coreProduct());

        if (strong.size() >= targetCount) {
            return strong;
        }

        List<ProductCard> merged = new ArrayList<>(strong);
        java.util.Set<String> seen = strong.stream().map(ProductCard::id).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        safeFill.stream()
                .filter(product -> seen.add(product.id()))
                .forEach(merged::add);
        if (!merged.isEmpty()) {
            return merged;
        }

        return List.of();
    }

    private List<ProductCard> applySort(List<ProductCard> products, SearchFilter filter) {
        return productSortService.applySort(products, filter);
    }

    private List<PlatformPriceStat> stats(List<ProductCard> products) {
        return products.stream()
                .collect(Collectors.groupingBy(ProductCard::platform))
                .entrySet()
                .stream()
                .map(entry -> {
                    BigDecimal min = entry.getValue().stream().map(ProductCard::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                    BigDecimal total = entry.getValue().stream().map(ProductCard::price).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal avg = total.divide(BigDecimal.valueOf(entry.getValue().size()), 2, RoundingMode.HALF_UP);
                    return new PlatformPriceStat(entry.getKey(), min, avg, entry.getValue().size());
                })
                .sorted(Comparator.comparing(PlatformPriceStat::minPrice))
                .toList();
    }
}
