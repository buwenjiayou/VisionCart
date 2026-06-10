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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
    private static final long SEARCH_LOCK_WAIT_TIMEOUT_MS = 2_500;
    private static final long SEARCH_LOCK_WAIT_SLEEP_MS = 150;
    private static final int MIN_RESULT_TARGET = 30;
    private static final int UNKNOWN_BRAND_POOL_MIN = 20;
    private static final int UNKNOWN_BRAND_POOL_MULTIPLIER = 4;

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
    private final IntentAwareSorter intentAwareSorter;
    private final QueryBudgetAllocator queryBudgetAllocator;
    private final ProductIntentBuilder productIntentBuilder;
    private final QueryPlanner queryPlanner;
    private final IntentGate intentGate;
    private final com.visioncart.service.search.strategy.VerticalStrategyRegistry strategyRegistry;
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
                              ProductReputationService reputationService,
                              ProductIntentBuilder productIntentBuilder,
                              QueryPlanner queryPlanner,
                              IntentGate intentGate,
                              com.visioncart.service.search.strategy.VerticalStrategyRegistry strategyRegistry) {
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
        this.intentAwareSorter = new IntentAwareSorter(productSortService);
        this.queryBudgetAllocator = new QueryBudgetAllocator();
        this.productIntentBuilder = productIntentBuilder;
        this.queryPlanner = queryPlanner;
        this.intentGate = intentGate;
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Enrich products with reputation index (口碑指数) and rating display label.
     * Uses pool-relative scoring so sales normalization is consistent within a result set.
     */
    private List<ProductCard> enrichRatingLabels(List<ProductCard> products) {
        if (products == null || products.isEmpty()) return products;
        return reputationService.attachReputation(products);
    }

    private List<ProductCard> enrichRatingLabels(List<ProductCard> products, SearchFilter filter) {
        return enrichRatingLabels(products);
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
        String lockHash = null;
        boolean lockAcquired = false;
        boolean lockChecked = false;

        if (sessionId != null && !sessionId.isBlank()) {
            // Lock key includes filter+attributes+recallSize hash so different searches don't collide
            String lockInput = request.effectiveFilter().toString()
                    + "|" + (request.attributes() != null ? request.attributes().toString() : "")
                    + "|recall=" + request.effectiveRecallSize();
            lockHash = HashUtils.md5Hex(lockInput);
            lockKey = SEARCH_LOCK_PREFIX + sessionId + ":" + lockHash.substring(0, 8);
            lockValue = java.util.UUID.randomUUID().toString();
            try {
                lockAcquired = Boolean.TRUE.equals(
                        redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, SEARCH_LOCK_TTL));
                lockChecked = true;
            } catch (Exception e) {
                log.warn("Redis lock check failed, proceeding without lock: {}", e.getMessage());
            }

            if (lockChecked && !lockAcquired) {
                SearchResult cached = waitForMatchingCachedSearchResult(sessionId, lockHash, request);
                if (cached != null) {
                    log.info("Search lock held for session {}, returning matching cached candidates", sessionId);
                    return cached;
                }
                log.info("Search lock held for session {}, returning in-progress response", sessionId);
                metricsService.recordSearchInProgress(request.clientType());
                return new SearchResult(0, List.of(), List.of(), List.of(),
                        false, currentSearchRunId(sessionId), true);
            }
        }

        try {
            String searchRunToken = beginSearchRun(sessionId);
            return doSearch(request, domestic, userId, searchRunToken, lockHash);
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

    private SearchResult waitForMatchingCachedSearchResult(String sessionId, String searchIdentity, SearchRequest request) {
        SearchResult cached = matchingCachedSearchResult(sessionId, searchIdentity, request);
        if (cached != null) {
            return cached;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEARCH_LOCK_WAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(SEARCH_LOCK_WAIT_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            cached = matchingCachedSearchResult(sessionId, searchIdentity, request);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private SearchResult matchingCachedSearchResult(String sessionId, String searchIdentity, SearchRequest request) {
        if (!sessionCache.matchesSearchIdentity(sessionId, searchIdentity)) {
            return null;
        }
        List<ProductCard> sessionCached = sessionCache.getBestCandidates(sessionId);
        if (sessionCached.isEmpty()) {
            return null;
        }
        int pageSize = targetPageSize(request);
        List<ProductCard> page = sessionCached.stream()
                .limit(pageSize)
                .toList();
        return new SearchResult(sessionCached.size(), page, stats(sessionCached), List.of(),
                false, currentSearchRunId(sessionId));
    }

    private SearchResult doSearch(SearchRequest request, boolean domestic, Long userId,
                                  String searchRunToken, String searchIdentity) {
        // Start total search timer
        io.micrometer.core.instrument.Timer.Sample searchTimer = metricsService.startSearchTotalTimer();

        Map<String, String> attributes = enrichAttributes(request, userId);
        SearchFilter filter = request.effectiveFilter();
        SearchIntent intent = SearchIntent.from(attributes, filter);

        // Build ProductIntent for structured intent analysis
        ProductIntent productIntent = productIntentBuilder.build(attributes, request.sessionId());

        // 解析垂直策略（高频类目有专属策略，其他走默认）
        com.visioncart.service.search.strategy.VerticalSearchStrategy strategy =
                strategyRegistry.resolve(productIntent);
        QueryPlan queryPlan = strategy.buildQueryPlan(productIntent, filter);
        long telemetryStartNanos = System.nanoTime();
        SearchTelemetry telemetry = new SearchTelemetry(searchRunToken)
                .put("strategy", strategy.getClass().getSimpleName())
                .put("queryPlan", queryPlan.debug())
                .put("primaryQueries", queryPlan.primaryQueries())
                .put("secondaryQueries", queryPlan.secondaryQueries())
                .put("fallbackQueries", queryPlan.fallbackQueries());

        log.info("ProductIntent: canonicalProduct={}, role={}, productBrand={}, compatibleBrand={}, " +
                        "features={}, relatedOnly={}, primaryQueries={}, strategy={}",
                productIntent.canonicalProduct(), productIntent.productRole(),
                productIntent.productBrand(), productIntent.compatibleBrand(),
                productIntent.featureTerms(), productIntent.relatedOnlyTerms(),
                queryPlan.primaryQueries(), strategy.getClass().getSimpleName());

        // 注入 QueryPlan 查询到 attributes，让平台服务使用分层查询
        injectPlanQueries(attributes, queryPlan);

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
                sessionCache.saveCandidates(request.sessionId(), cached, searchIdentity);
            }
            // Apply pagination before diversification
            int pageNum = Math.max(1, request.effectivePage());
            int pageSize = targetPageSize(request);
            List<ProductCard> displayCached = diversifyUnknownBrandResults(cached, productIntent, pageSize);
            int from = (pageNum - 1) * pageSize;
            List<ProductCard> pageSlice = from >= displayCached.size() ? List.of()
                    : displayCached.subList(from, Math.min(displayCached.size(), from + pageSize));
            List<ProductCard> page = enrichRatingLabels(diversifyPlatforms(pageSlice, pageSize), filter);
            // 缓存命中也要保存 classifiedPool，供后续排序/筛选/NLP 使用
            if (request.sessionId() != null && !request.sessionId().isBlank()
                    && isCurrentSearchRun(request.sessionId(), searchRunToken)) {
                com.visioncart.service.search.strategy.VerticalSearchStrategy cachedStrategy =
                        strategyRegistry.resolve(productIntent);
                List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> cachedClassified;
                boolean hasIntent = productIntent != null && !productIntent.canonicalProduct().isBlank();
                if (hasIntent) {
                    cachedClassified = cachedStrategy.classifyAll(cached, productIntent);
                } else {
                    cachedClassified = cached.stream()
                            .map(p -> new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                                    p, IntentGate.IntentTier.EXACT_MAIN))
                            .toList();
                }
                SearchCandidatePool cachedPool = new SearchCandidatePool(
                        request.sessionId(), searchRunToken, searchIdentity,
                        productIntent, cachedStrategy.getClass().getSimpleName(),
                        filter, cachedClassified, cached.size(), cached, filter, filter.sortBy(), page);
                sessionCache.saveClassifiedPool(request.sessionId(), cachedPool);
            }
            List<SuggestionCard> cards = mergeInsightCards(request.sessionId(), searchRunToken, request.clientType(), cached, attributes, filter,
                    suggestionService.cards(request.clientType(), page, attributes, filter));
            return new SearchResult(cached.size(), page, stats(cached), cards, false, searchRunToken);
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
        java.util.concurrent.CompletionService<PlatformSearchOutcome> completionService =
                new java.util.concurrent.ExecutorCompletionService<>(platformSearchExecutor);
        Map<Future<PlatformSearchOutcome>, String> submittedPlatforms = new LinkedHashMap<>();
        List<PlatformSearchOutcome> platformOutcomes = new ArrayList<>();
        for (PlatformSearchService ps : eligible) {
            try {
                Future<PlatformSearchOutcome> future =
                        completionService.submit(() -> fetchSinglePlatform(ps, attributes, filter, request, domestic));
                submittedPlatforms.put(future, ps.platform());
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("{} search skipped: executor queue full", ps.platform());
                PlatformSearchOutcome outcome = PlatformSearchOutcome.failed(ps.platform(), "executor_queue_full");
                platformOutcomes.add(outcome);
                recordPlatformOutcome(outcome, "main");
            }
        }

        // Collect results as each platform completes — push staged results via WebSocket
        List<ProductCard> all = new ArrayList<>();
        List<PlatformSearchOutcome> collectedOutcomes = collectPlatformOutcomes(completionService, submittedPlatforms, "main");
        for (PlatformSearchOutcome outcome : collectedOutcomes) {
            platformOutcomes.add(outcome);
            recordPlatformOutcome(outcome, "main");
            if (outcome.products() != null && !outcome.products().isEmpty()) {
                all.addAll(outcome.products());
                // Push partial results to client via WebSocket (staged delivery)
                if (sessionId != null && !sessionId.isBlank()
                        && isCurrentSearchRun(sessionId, searchRunToken)) {
                    sendSearchProgress(sessionId, all, request, searchRunToken, productIntent, strategy);
                }
            }
        }
        boolean cacheablePlatformResult = cacheablePlatformResult(eligible, platformOutcomes);

        log.info("Search results: {} raw products from {} platforms, attributes={}", all.size(), eligible.size(), attributes);

        List<ProductCard> scored = ranker.withSimilarity(all, intent);
        List<ProductCard> deduped = deduplicator.deduplicate(scored);
        List<ProductCard> afterFilter = applyFilter(deduped, filter);
        List<ProductCard> filtered = applyIntentFilter(afterFilter, intent, productIntent, filter, targetCount);
        if (filtered.size() < targetCount) {
            Map<String, String> supplementalAttributes = supplementalAttributes(attributes);
            if (!supplementalAttributes.equals(attributes)) {
                // Problem 3 fix: supplemental search must rebuild ProductIntent + QueryPlan
                // to prevent bypassing the intent gate with stale/raw attributes
                ProductIntent suppProductIntent = productIntentBuilder.build(supplementalAttributes, request.sessionId());
                com.visioncart.service.search.strategy.VerticalSearchStrategy suppStrategy =
                        strategyRegistry.resolve(suppProductIntent);
                QueryPlan suppPlan = suppStrategy.buildQueryPlan(suppProductIntent, filter);
                // Inject secondary queries for supplemental recall (wider net than primary)
                injectSupplementalPlanQueries(supplementalAttributes, suppPlan);

                // Parallel supplemental recall
                java.util.concurrent.CompletionService<PlatformSearchOutcome> suppCompletion =
                        new java.util.concurrent.ExecutorCompletionService<>(platformSearchExecutor);
                Map<Future<PlatformSearchOutcome>, String> suppSubmitted = new LinkedHashMap<>();
                for (PlatformSearchService ps : eligible) {
                    try {
                        Future<PlatformSearchOutcome> future =
                                suppCompletion.submit(() -> fetchSinglePlatform(ps, supplementalAttributes, filter, request, domestic));
                        suppSubmitted.put(future, ps.platform());
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        log.warn("{} supplemental search skipped: executor queue full", ps.platform());
                        recordPlatformOutcome(
                                PlatformSearchOutcome.failed(ps.platform(), "executor_queue_full"),
                                "supplemental");
                    }
                }
                List<ProductCard> supplemental = new ArrayList<>();
                for (PlatformSearchOutcome suppOutcome : collectPlatformOutcomes(suppCompletion, suppSubmitted, "supplemental")) {
                    recordPlatformOutcome(suppOutcome, "supplemental");
                    if (suppOutcome.products() != null) supplemental.addAll(suppOutcome.products());
                }
                if (!supplemental.isEmpty()) {
                    List<ProductCard> merged = new ArrayList<>(all);
                    merged.addAll(supplemental);
                    scored = ranker.withSimilarity(merged, intent);
                    deduped = deduplicator.deduplicate(scored);
                    afterFilter = applyFilter(deduped, filter);
                    // Problem 3 fix: use the ORIGINAL productIntent for filtering (not the supplemental one)
                    // because supplementalAttributes strips brand info — we still want brand-gated filtering
                    filtered = applyIntentFilter(afterFilter, intent, productIntent, filter, targetCount);
                    all = merged;
                    log.info("Supplemental recall: {} products, intentFiltered={}", supplemental.size(), filtered.size());
                }
            }
        }
        // Problem 1 fix: tier-based sorting — EXACT_MAIN > COMPATIBLE_MAIN > SAME_FAMILY > RELATED_ACCESSORY
        // Prevents RELATED_ACCESSORY from appearing before EXACT_MAIN due to price/sales/rating
        if (filtered.size() < targetCount
                && !intent.strictIntent()
                && !queryPlan.fallbackQueries().isEmpty()) {
            Map<String, String> fallbackAttributes = new LinkedHashMap<>(attributes);
            injectFallbackPlanQueries(fallbackAttributes, queryPlan);

            java.util.concurrent.CompletionService<PlatformSearchOutcome> fallbackCompletion =
                    new java.util.concurrent.ExecutorCompletionService<>(platformSearchExecutor);
            Map<Future<PlatformSearchOutcome>, String> fallbackSubmitted = new LinkedHashMap<>();
            for (PlatformSearchService ps : eligible) {
                try {
                    Future<PlatformSearchOutcome> future =
                            fallbackCompletion.submit(() -> fetchSinglePlatform(ps, fallbackAttributes, filter, request, domestic));
                    fallbackSubmitted.put(future, ps.platform());
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    log.warn("{} fallback search skipped: executor queue full", ps.platform());
                    recordPlatformOutcome(
                            PlatformSearchOutcome.failed(ps.platform(), "executor_queue_full"),
                            "fallback");
                }
            }
            List<ProductCard> fallback = new ArrayList<>();
            for (PlatformSearchOutcome fallbackOutcome : collectPlatformOutcomes(fallbackCompletion, fallbackSubmitted, "fallback")) {
                recordPlatformOutcome(fallbackOutcome, "fallback");
                if (fallbackOutcome.products() != null) fallback.addAll(fallbackOutcome.products());
            }
            if (!fallback.isEmpty()) {
                List<ProductCard> merged = new ArrayList<>(all);
                merged.addAll(fallback);
                scored = ranker.withSimilarity(merged, intent);
                deduped = deduplicator.deduplicate(scored);
                afterFilter = applyFilter(deduped, filter);
                filtered = applyIntentFilter(afterFilter, intent, productIntent, filter, targetCount);
                all = merged;
                log.info("Fallback recall: {} products, intentFiltered={}", fallback.size(), filtered.size());
            }
        }

        List<ProductCard> ranked = rankForIntent(filtered, productIntent, strategy, intent, filter);
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
            relaxedAttributes.remove(SearchQueryBuilder.ATTR_PLAN_QUERIES); // 品牌降级时移除旧 plan queries
            SearchIntent relaxedIntent = SearchIntent.from(relaxedAttributes, filter);
            ProductIntent relaxedProductIntent = productIntentBuilder.build(relaxedAttributes, request.sessionId());
            List<ProductCard> relaxedScored = ranker.withSimilarity(all, relaxedIntent);
            List<ProductCard> relaxedFiltered = applyIntentFilter(
                    applyFilter(deduplicator.deduplicate(relaxedScored), filter),
                    relaxedIntent, relaxedProductIntent, filter, targetCount);
            ranked = rankForIntent(relaxedFiltered, relaxedProductIntent, strategy, relaxedIntent, filter);
            relaxed = !ranked.isEmpty();
            if (relaxed) {
                // 品牌降级成功：重建 classifiedPool 使用放宽后的 intent
                afterFilter = relaxedFiltered;
                productIntent = relaxedProductIntent;
                log.info("Brand fallback found {} results", ranked.size());
            }
        }

        boolean hasProductIntent = productIntent != null && !productIntent.canonicalProduct().isBlank();
        if (ranked.isEmpty()
                && !hasProductIntent
                && !afterFilter.isEmpty()
                && !intent.strictIntent()
                && (intent.brand().isBlank() || !intent.hasReliableBrand())) {
            ranked = applySort(ranker.rank(afterFilter, intent), filter);
            relaxed = true;
            log.info("Intent fallback returning {} user-filtered products from {} raw products",
                    ranked.size(), all.size());
        }

        // Save final candidates AFTER all fallbacks — ensures cache reflects best available pool.
        // 关键改动：保存 classifiedPool（Top300）作为会话事实源，排序/筛选/NLP 都基于它重算 displayPage
        List<ProductCard> enrichedRanked = enrichRatingLabels(ranked, filter);
        List<ProductCard> displayCandidates = diversifyUnknownBrandResults(enrichedRanked, productIntent, targetCount);
        List<ProductCard> rawPage = diversifyPlatforms(displayCandidates, targetCount);
        boolean currentSearchRun = sessionId == null || sessionId.isBlank()
                || isCurrentSearchRun(sessionId, searchRunToken);
        if (!currentSearchRun) {
            log.info("Ignoring stale search result for session={}, run={}", sessionId, searchRunToken);
            metricsService.stopSearchTotalTimer(searchTimer, false);
            return new SearchResult(0, List.of(), List.of(), List.of(), false, searchRunToken);
        }
        // 保存 classifiedPool（Top300 带 tier 分类）
        // 关键：所有搜索都生成 classifiedPool，即使没有垂直意图也用默认 tier
        int classifiedPoolSize = enrichedRanked.size(); // fallback
        if (sessionId != null && !sessionId.isBlank()) {
            sessionCache.saveCandidates(sessionId, enrichedRanked, searchIdentity);

            com.visioncart.service.search.strategy.VerticalSearchStrategy resolvedStrategy =
                    strategyRegistry.resolve(productIntent);
            List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> classifiedPool;
            if (hasProductIntent) {
                // 有垂直意图：用策略的 classify 做精细分类
                classifiedPool = resolvedStrategy.classifyAll(afterFilter, productIntent);
            } else {
                // 无垂直意图：所有商品统一归为 EXACT_MAIN（保持排序即可）
                classifiedPool = afterFilter.stream()
                        .map(p -> new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                                p, IntentGate.IntentTier.EXACT_MAIN))
                        .toList();
            }
            SearchCandidatePool pool = new SearchCandidatePool(
                    sessionId, searchRunToken, searchIdentity,
                    productIntent, resolvedStrategy.getClass().getSimpleName(),
                    filter, classifiedPool, all.size(), all, filter, filter.sortBy(), rawPage);
            sessionCache.saveClassifiedPool(sessionId, pool);
            classifiedPoolSize = classifiedPool.size();
            log.info("Saved classifiedPool: {} products (from {} afterFilter), hasIntent={}, strategy={}",
                    classifiedPoolSize, afterFilter.size(), hasProductIntent,
                    resolvedStrategy.getClass().getSimpleName());

            // Send final WebSocket message with staging=false to indicate search completion
            // totalCount 使用 classifiedPool.size() 而非 displayPage.size()
            try {
                var doneMsg = new com.visioncart.api.dto.SearchProgressMessage(sessionId, rawPage, classifiedPoolSize, false, searchRunToken);
                messagingTemplate.convertAndSend("/topic/search/" + sessionId, doneMsg);
            } catch (Exception e) {
                log.debug("Failed to send search completion message: {}", e.getMessage());
            }
        }
        if (classifiedPoolSize <= 0 || (request.effectivePage() <= 1 && rawPage.isEmpty())) {
            metricsService.recordSearchFinalEmpty(finalEmptyReason(eligible, platformOutcomes, all));
        }
        List<SuggestionCard> cards = mergeInsightCards(request.sessionId(), searchRunToken, request.clientType(), enrichedRanked, attributes, filter,
                suggestionService.cards(request.clientType(), rawPage, attributes, filter));

        // Write cache only when required enabled platforms completed successfully.
        if (cacheablePlatformResult) {
            putToCache(cacheKey, enrichedRanked);
        } else {
            log.info("Search cache skipped because one or more required platforms failed: {}",
                    platformOutcomes.stream().map(PlatformSearchOutcome::summary).toList());
            metricsService.recordSearchCacheSkipped("platform_failure");
        }

        telemetry.put("relaxed", relaxed)
                .put("tierCounts", tierCounts(ranked, productIntent, strategy))
                .put("top20", top20TierShare(ranked, productIntent, strategy))
                .recordLatency(Duration.ofNanos(System.nanoTime() - telemetryStartNanos));
        log.info("SearchTelemetry {}", telemetry.fields());

        // Stop total search timer
        metricsService.stopSearchTotalTimer(searchTimer, false);

        return new SearchResult(classifiedPoolSize, rawPage, stats(enrichedRanked), cards, relaxed, searchRunToken);
    }

    // ==================== classifiedPool 重排序 ====================

    /**
     * 基于 classifiedPool 重新排序，不重新搜索。
     * 用于排序切换、NLP 排序、标签删除后的重算。
     *
     * <p>核心流程：
     * <ol>
     *   <li>从 sessionCache 获取 classifiedPool（Top300 带 tier）</li>
     *   <li>对 classifiedPool 应用新的筛选条件</li>
     *   <li>tier-aware 排序</li>
     *   <li>strategy.mix() 生成 displayPage（Top50）</li>
     *   <li>口碑标注 + 平台多样化</li>
     * </ol>
     */
    public SearchResult resortSession(String sessionId, SearchFilter newFilter, int pageSize) {
        SearchCandidatePool pool = sessionCache.getClassifiedPool(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No classified pool for session " + sessionId));

        // 1. 对 classifiedPool 应用新的筛选条件
        List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> filteredPool =
                applyFilterToClassifiedPool(pool.classifiedPool(), newFilter);

        // 2. tier-aware 排序（每个 tier 内按用户排序）
        List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> sorted =
                intentAwareSorter.sortClassified(filteredPool, newFilter);

        // 3. mix 生成 displayPage
        com.visioncart.service.search.strategy.VerticalSearchStrategy strategy =
                strategyRegistry.resolve(pool.productIntent());
        List<ProductCard> displayPage = strategy.mix(pool.productIntent(), sorted, pageSize);

        // 4. 口碑标注 + 平台多样化（仅在 displayPage 上）
        displayPage = enrichRatingLabels(displayPage, newFilter);
        displayPage = diversifyPlatforms(displayPage, pageSize);

        // 5. 更新 session candidates cache（供后续 NLP 使用）
        sessionCache.saveCandidates(sessionId, displayPage, pool.searchIdentity());

        SearchCandidatePool updatedPool = pool.withCurrentState(newFilter, displayPage);
        sessionCache.saveClassifiedPool(sessionId, updatedPool);
        sessionCache.saveCandidates(sessionId, updatedPool.toProductCards(), pool.searchIdentity());

        return new SearchResult(
                filteredPool.size(),  // total = filteredPool.size(), 不是 displayPage.size()
                displayPage,
                stats(displayPage),
                List.of(),  // suggestionCards（重排序不重新生成）
                false,
                pool.searchRunId()
        );
    }

    /**
     * 对 classifiedPool 应用筛选条件，保留 tier 信息。
     * 复用 applyFilter() 的单产品过滤逻辑，但不重新做 validity/dedup（classifiedPool 已经做过）。
     */
    private List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> applyFilterToClassifiedPool(
            List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> pool,
            SearchFilter filter) {
        // 先提取 ProductCard，复用现有的 applyFilter 逻辑
        List<ProductCard> products = pool.stream()
                .map(com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct::product)
                .toList();
        List<ProductCard> filtered = applyFilter(products, filter);

        // 重建 ClassifiedProduct（保持 tier）
        java.util.Set<String> filteredIds = filtered.stream()
                .map(ProductCard::id)
                .collect(Collectors.toSet());
        return pool.stream()
                .filter(cp -> filteredIds.contains(cp.product().id()))
                .toList();
    }

    /**
     * 获取当前 session 的 classifiedPool（供外部调用）。
     */
    public java.util.Optional<SearchCandidatePool> getClassifiedPool(String sessionId) {
        return sessionCache.getClassifiedPool(sessionId);
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

    private String currentSearchRunId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            String current = redisTemplate.opsForValue().get(SEARCH_RUN_PREFIX + sessionId);
            if (current != null && !current.isBlank()) {
                return current;
            }
        } catch (Exception e) {
            log.debug("Redis unavailable for search run token lookup, using local memory: {}", e.getMessage());
        }
        return localSearchRuns.get(sessionId);
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

    private List<PlatformSearchOutcome> collectPlatformOutcomes(
            java.util.concurrent.CompletionService<PlatformSearchOutcome> completionService,
            Map<Future<PlatformSearchOutcome>, String> submittedPlatforms,
            String phase) {
        if (submittedPlatforms == null || submittedPlatforms.isEmpty()) {
            return List.of();
        }

        List<PlatformSearchOutcome> outcomes = new ArrayList<>();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(platformTimeout.toMillis() + 500);
        int remaining = submittedPlatforms.size();

        while (remaining > 0) {
            long waitNanos = deadlineNanos - System.nanoTime();
            if (waitNanos <= 0) {
                break;
            }
            try {
                Future<PlatformSearchOutcome> completed = completionService.poll(waitNanos, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    break;
                }
                remaining--;
                String platform = submittedPlatforms.remove(completed);
                try {
                    outcomes.add(completed.get());
                } catch (Exception e) {
                    log.warn("{} platform {} search result collection failed: {}",
                            phase, platform, e.getMessage());
                    outcomes.add(PlatformSearchOutcome.failed(
                            StringUtils.defaultIfBlank(platform, "unknown"),
                            phase + "_collection_failed:" + e.getMessage()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} platform search result collection interrupted", phase);
                break;
            }
        }

        for (Map.Entry<Future<PlatformSearchOutcome>, String> entry : submittedPlatforms.entrySet()) {
            if (!entry.getKey().isDone()) {
                entry.getKey().cancel(true);
                outcomes.add(PlatformSearchOutcome.failed(entry.getValue(), phase + "_timeout"));
            }
        }
        return outcomes;
    }

    private void recordPlatformOutcome(PlatformSearchOutcome outcome, String stage) {
        if (outcome == null) {
            return;
        }
        int productCount = outcome.products() == null ? 0 : outcome.products().size();
        if (outcome.success()) {
            metricsService.recordSearchPlatformOutcome(outcome.platform(), stage, "success", "none");
            metricsService.recordSearchPlatformProducts(outcome.platform(), stage, productCount);
            log.info("Platform outcome: platform={}, status=success, products={}, durationMs={}",
                    outcome.platform(), productCount, outcome.durationMs());
            return;
        }
        if (outcome.skipped()) {
            metricsService.recordSearchPlatformOutcome(outcome.platform(), stage, "skipped", outcome.reason());
            metricsService.recordSearchPlatformProducts(outcome.platform(), stage, 0);
            log.info("Platform outcome: platform={}, status=skipped, reason={}",
                    outcome.platform(), outcome.reason());
            return;
        }
        metricsService.recordSearchPlatformOutcome(outcome.platform(), stage, "failed", outcome.reason());
        metricsService.recordSearchPlatformProducts(outcome.platform(), stage, 0);
        log.warn("Platform outcome: platform={}, status=failed, reason={}",
                outcome.platform(), outcome.reason());
    }

    private String finalEmptyReason(List<PlatformSearchService> eligible,
                                    List<PlatformSearchOutcome> outcomes,
                                    List<ProductCard> rawProducts) {
        if (eligible == null || eligible.isEmpty()) {
            return "no_platform_enabled";
        }
        boolean anyPlatformProducts = outcomes != null && outcomes.stream()
                .anyMatch(outcome -> outcome != null
                        && outcome.success()
                        && outcome.products() != null
                        && !outcome.products().isEmpty());
        if (!anyPlatformProducts || rawProducts == null || rawProducts.isEmpty()) {
            return "all_platform_failed";
        }
        return "filtered_zero";
    }

    private boolean cacheablePlatformResult(List<PlatformSearchService> eligible,
                                            List<PlatformSearchOutcome> outcomes) {
        if (eligible == null || eligible.isEmpty()) {
            return true;
        }
        Map<String, PlatformSearchOutcome> byPlatform = outcomes == null ? Map.of() : outcomes.stream()
                .collect(Collectors.toMap(
                        PlatformSearchOutcome::platform,
                        outcome -> outcome,
                        (left, right) -> right,
                        LinkedHashMap::new));
        for (PlatformSearchService ps : eligible) {
            PlatformSearchOutcome outcome = byPlatform.get(ps.platform());
            if (outcome == null || (!outcome.success() && !outcome.skipped())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 同步执行单个平台搜索（含重试、熔断、指标）。
     * 调用方应确保在异步线程中调用（如 CompletionService.submit），
     * 本方法内部不再开子线程，避免同一线程池嵌套导致饥饿。
     */
    private PlatformSearchOutcome fetchSinglePlatform(PlatformSearchService ps,
                                                       Map<String, String> attributes,
                                                       SearchFilter filter,
                                                       SearchRequest request,
                                                       boolean domestic) {
        // Check if platform is enabled via config
        VisionCartProperties.Platform platformConfig = properties.getPlatforms().getPlatform(ps.platform());
        if (!platformConfig.isEnabled()) {
            log.info("Platform {} disabled via config, skipping", ps.platform());
            return PlatformSearchOutcome.skipped(ps.platform(), "disabled");
        }

        // Region strategy filtering (replaces simple domesticOnly() check)
        if (!matchesRegionStrategy(platformConfig, domestic)) {
            log.info("Platform {} regionStrategy={} not suitable for {} request, skipping",
                    ps.platform(), platformConfig.getRegionStrategy(), domestic ? "domestic" : "international");
            return PlatformSearchOutcome.skipped(ps.platform(), "region_mismatch");
        }

        boolean circuitBreakerEnabled = platformConfig.isCircuitBreakerEnabled();
        if (circuitBreakerEnabled && !circuitBreaker.allowRequest(ps.platform())) {
            log.info("Circuit open, skipping {}", ps.platform());
            return PlatformSearchOutcome.failed(ps.platform(), "circuit_open");
        }

        int maxRetries = platformConfig.getMaxRetries();
        long retryDelayMs = platformConfig.getRetryDelayMs();
        long platformBudgetMs = Math.max(1_000, platformConfig.getTimeoutMs());
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(platformBudgetMs);

        // Start platform search timer
        io.micrometer.core.instrument.Timer.Sample platformTimer = metricsService.startPlatformSearchTimer();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (System.nanoTime() >= deadlineNanos) {
                metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
                log.warn("Platform {} search budget exhausted before attempt {} (budget={}ms)",
                        ps.platform(), attempt + 1, platformBudgetMs);
                return PlatformSearchOutcome.failed(ps.platform(), "timeout_budget_exhausted");
            }
            if (attempt > 0) {
                long remainingDelayMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingDelayMs <= 0) {
                    metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
                    log.warn("Platform {} search budget exhausted before retry {} (budget={}ms)",
                            ps.platform(), attempt + 1, platformBudgetMs);
                    return PlatformSearchOutcome.failed(ps.platform(), "timeout_budget_exhausted");
                }
                try {
                    Thread.sleep(Math.min(retryDelayMs, remainingDelayMs));
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
                return PlatformSearchOutcome.success(ps.platform(), result, durationMs);
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                if (circuitBreakerEnabled) {
                    circuitBreaker.recordFailure(ps.platform(), durationMs);
                }
                if (attempt >= maxRetries || System.nanoTime() >= deadlineNanos) {
                    metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
                    log.warn("Platform {} search error after {} attempts: {}", ps.platform(), attempt + 1, e.getMessage());
                    return PlatformSearchOutcome.failed(ps.platform(), "error:" + e.getMessage());
                }
                log.warn("Platform {} search error on attempt {}: {}", ps.platform(), attempt + 1, e.getMessage());
            }
        }

        metricsService.stopPlatformSearchTimer(platformTimer, ps.platform(), false);
        return PlatformSearchOutcome.failed(ps.platform(), "interrupted");
    }

    private void sendSearchProgress(String sessionId, List<ProductCard> products, SearchRequest request,
                                     String searchRunToken, ProductIntent productIntent,
                                     com.visioncart.service.search.strategy.VerticalSearchStrategy strategy) {
        try {
            if (!isCurrentSearchRun(sessionId, searchRunToken)) {
                return;
            }
            int pageSize = targetPageSize(request);
            // 轻量净化：去重 + 基本有效性过滤（有标题、价格 > 0）
            List<ProductCard> deduped = deduplicator.deduplicate(products);
            List<ProductCard> valid = deduped.stream()
                    .filter(p -> p.title() != null && !p.title().isBlank())
                    .filter(p -> p.price() != null && p.price().compareTo(java.math.BigDecimal.ZERO) > 0)
                    .toList();

            // Problem 12 fix: apply lightweight intent gate to progress results
            // Filter out REJECT tier products to prevent irrelevant items flashing on screen
            if (productIntent != null && !productIntent.canonicalProduct().isBlank() && strategy != null) {
                valid = valid.stream()
                        .filter(p -> {
                            IntentGate.IntentTier tier = strategy.classify(productIntent, p);
                            return tier != IntentGate.IntentTier.REJECT;
                        })
                        .toList();
            }

            List<ProductCard> displayCandidates = diversifyUnknownBrandResults(valid, productIntent, pageSize);
            List<ProductCard> page = enrichRatingLabels(diversifyPlatforms(displayCandidates, pageSize), request.effectiveFilter());
            var msg = new com.visioncart.api.dto.SearchProgressMessage(sessionId, page, products.size(), true, searchRunToken);
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
        // 补充搜索不使用 plan queries（使用更简单的查询）
        return relaxed;
    }

    private void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (StringUtils.isNotBlank(SearchTextUtils.useful(value))) {
            target.put(key, value);
        }
    }

    /**
     * 将 QueryPlan 的分层查询注入 attributes，让 SearchQueryBuilder 优先使用。
     *
     * <p>Problem 2 fix: 分层注入，不一次性把所有 tier 打出去。
     * <ul>
     *   <li>主流程只注入 primaryQueries（精准查询）</li>
     *   <li>补召回时追加 secondaryQueries</li>
     *   <li>fallbackQueries 作为最后手段</li>
     * </ul>
     */
    private void injectPlanQueries(Map<String, String> attributes, QueryPlan plan) {
        if (plan == null) return;
        // Problem 2 fix: only inject primary queries initially.
        // Secondary and fallback queries are added during supplemental recall.
        List<String> primary = new ArrayList<>(queryBudgetAllocator.queries(plan, RetrievalLevel.PRIMARY));
        if (!primary.isEmpty()) {
            try {
                attributes.put(SearchQueryBuilder.ATTR_PLAN_QUERIES,
                        objectMapper.writeValueAsString(primary));
            } catch (JsonProcessingException e) {
                log.warn("Failed to inject plan queries: {}", e.getMessage());
            }
        }
    }

    /**
     * 补召回时注入 primary + secondary 查询（比主流程更宽，但仍不含 fallback 泛词）。
     */
    private void injectSupplementalPlanQueries(Map<String, String> attributes, QueryPlan plan) {
        if (plan == null) return;
        List<String> queries = new ArrayList<>();
        queries.addAll(queryBudgetAllocator.queries(plan, RetrievalLevel.SECONDARY));
        List<String> unique = queries.stream().distinct().limit(6).toList();
        if (!unique.isEmpty()) {
            try {
                attributes.put(SearchQueryBuilder.ATTR_PLAN_QUERIES,
                        objectMapper.writeValueAsString(unique));
            } catch (JsonProcessingException e) {
                log.warn("Failed to inject supplemental plan queries: {}", e.getMessage());
            }
        }
    }

    private void injectFallbackPlanQueries(Map<String, String> attributes, QueryPlan plan) {
        if (plan == null) return;
        List<String> unique = queryBudgetAllocator.queries(plan, RetrievalLevel.FALLBACK)
                .stream()
                .distinct()
                .limit(8)
                .toList();
        if (!unique.isEmpty()) {
            try {
                attributes.put(SearchQueryBuilder.ATTR_PLAN_QUERIES,
                        objectMapper.writeValueAsString(unique));
            } catch (JsonProcessingException e) {
                log.warn("Failed to inject fallback plan queries: {}", e.getMessage());
            }
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

    private List<ProductCard> diversifyUnknownBrandResults(List<ProductCard> products,
                                                           ProductIntent productIntent,
                                                           int pageSize) {
        if (products == null || products.size() <= 1 || pageSize <= 1
                || productIntent == null || productIntent.hasReliableBrand()) {
            return products == null ? List.of() : products;
        }
        Map<String, List<ProductCard>> byBrand = new LinkedHashMap<>();
        for (ProductCard product : products) {
            byBrand.computeIfAbsent(displayBrandKey(product), ignored -> new ArrayList<>()).add(product);
        }
        if (byBrand.size() <= 1) {
            return products;
        }

        int windowSize = Math.min(pageSize, products.size());
        List<ProductCard> window = new ArrayList<>(windowSize);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        boolean added;
        do {
            added = false;
            for (List<ProductCard> bucket : byBrand.values()) {
                if (window.size() >= windowSize) {
                    break;
                }
                if (!bucket.isEmpty()) {
                    ProductCard product = bucket.remove(0);
                    if (seen.add(displayProductKey(product))) {
                        window.add(product);
                        added = true;
                    }
                }
            }
        } while (added && window.size() < windowSize);

        seen = window.stream()
                .map(this::displayProductKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ProductCard product : products) {
            if (seen.add(displayProductKey(product))) {
                window.add(product);
            }
        }
        return window;
    }

    private String displayBrandKey(ProductCard product) {
        String brand = SearchTextUtils.useful(product == null ? null : product.brand());
        if (!brand.isBlank()) {
            return BrandMatcher.canonical(brand);
        }
        String inferred = BrandMatcher.inferBrand(product == null ? "" : product.title(),
                product == null ? "" : product.shopName());
        String canonical = BrandMatcher.canonical(inferred);
        return canonical.isBlank() ? "__unknown__" : canonical;
    }

    private String displayProductKey(ProductCard product) {
        if (product == null) return "";
        String id = StringUtils.defaultString(product.id());
        if (!id.isBlank()) return id;
        return StringUtils.defaultString(product.platform()) + "|"
                + StringUtils.defaultString(product.title());
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

    private List<ProductCard> applyIntentFilter(List<ProductCard> products, SearchIntent intent,
                                                 ProductIntent productIntent, SearchFilter filter, int targetCount) {
        if (!intent.hasSpecificSignals()) {
            return products;
        }

        // 有 ProductIntent 时使用垂直策略分层过滤
        if (productIntent != null && !productIntent.canonicalProduct().isBlank()) {
            return strategyFilter(products, productIntent, intent, filter, targetCount);
        }

        // 降级：使用 RelevanceRanker 的 tier 过滤
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

    /**
     * 基于垂直策略的分层过滤。
     * 使用 VerticalSearchStrategy.classify() 分类，strategy.mix() 混合结果。
     *
     * <p>Problem 4 fix: 策略返回空时，不绕开门控降级到旧 ranker，
     * 而是用放宽的策略重新分类（去掉品牌硬约束）。
     */
    private List<ProductCard> strategyFilter(List<ProductCard> products, ProductIntent productIntent,
                                              SearchIntent intent, SearchFilter filter, int targetCount) {
        com.visioncart.service.search.strategy.VerticalSearchStrategy strategy =
                strategyRegistry.resolve(productIntent);

        // 使用策略的 classify 方法对每个商品分类
        List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> classified =
                products.stream()
                        .map(p -> new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                                p, strategy.classify(productIntent, p)))
                        .toList();

        // 统计分类结果
        Map<IntentGate.IntentTier, Long> tierStats = classified.stream()
                .collect(Collectors.groupingBy(
                        com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct::tier,
                        Collectors.counting()));
        log.info("Strategy filter ({}): {} products -> {}", strategy.getClass().getSimpleName(),
                products.size(), tierStats);

        // 使用策略的 mix 方法混合结果
        List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> sortedClassified =
                intentAwareSorter.sortClassified(classified, filter);
        int mixTargetCount = displayCandidateWindow(productIntent, targetCount, sortedClassified.size());
        List<ProductCard> result = strategy.mix(productIntent, sortedClassified, mixTargetCount);

        // P0-1 fix: 策略返回空时，先尝试策略自身的放宽（去掉品牌约束）。
        // 仍为空时，用 ranker REJECTED 兜底（保留非 REJECT 商品），但不再绕开策略。
        if (result.isEmpty() && !intent.strictIntent() && strategy.allowBrandRelaxation(productIntent)) {
            ProductIntent relaxedIntent = productIntent.withRelaxedBrand();
            List<com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct> relaxedClassified =
                    products.stream()
                            .map(p -> new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                                    p, strategy.classify(relaxedIntent, p)))
                            .toList();
            int relaxedMixTargetCount = displayCandidateWindow(
                    relaxedIntent, targetCount, relaxedClassified.size());
            List<ProductCard> relaxedResult = strategy.mix(
                    relaxedIntent, intentAwareSorter.sortClassified(relaxedClassified, filter), relaxedMixTargetCount);
            if (!relaxedResult.isEmpty()) {
                log.info("Strategy relaxed (no brand): {} products", relaxedResult.size());
                return relaxedResult;
            }
        }

        // P0-1 fix: 不再 fallback 到旧 ranker。可以放宽策略，但不能绕开策略。
        // 品牌不可放宽或放宽后仍为空 → 返回空，由上层决定是否补召回。

        log.info("Strategy result: {} products", result.size());
        return result;
    }

    /**
     * Problem 1 fix: 按 IntentTier 排序，确保 EXACT_MAIN 排在 RELATED_ACCESSORY 前面。
     * 解决 RELATED_ACCESSORY 靠低价/高销量/口碑排序压过 EXACT_MAIN 的问题。
     */
    private List<ProductCard> applyTierSort(List<ProductCard> products,
                                             ProductIntent productIntent,
                                             com.visioncart.service.search.strategy.VerticalSearchStrategy strategy) {
        if (productIntent == null || productIntent.canonicalProduct().isBlank() || products.size() <= 1) {
            return products;
        }
        Map<String, IntentGate.IntentTier> tierMap = new LinkedHashMap<>();
        for (ProductCard p : products) {
            tierMap.put(p.id(), strategy.classify(productIntent, p));
        }
        return products.stream()
                .sorted(Comparator.comparingInt(
                        (ProductCard p) -> tierOrdinal(tierMap.getOrDefault(p.id(), IntentGate.IntentTier.REJECT))))
                .toList();
    }

    private int tierOrdinal(IntentGate.IntentTier tier) {
        return switch (tier) {
            case EXACT_MAIN -> 0;
            case COMPATIBLE_MAIN -> 1;
            case SAME_FAMILY -> 2;
            case RELATED_ACCESSORY -> 3;
            case SUBSTITUTE -> 4;
            case REJECT -> 5;
        };
    }

    private List<ProductCard> rankForIntent(List<ProductCard> products,
                                             ProductIntent productIntent,
                                             com.visioncart.service.search.strategy.VerticalSearchStrategy strategy,
                                             SearchIntent intent,
                                             SearchFilter filter) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        if (productIntent != null && !productIntent.canonicalProduct().isBlank() && strategy != null) {
            return intentAwareSorter.sortProducts(products, productIntent, strategy, filter);
        }
        return applySort(ranker.rank(products, intent), filter);
    }

    private int displayCandidateWindow(ProductIntent productIntent, int targetCount, int availableCount) {
        if (targetCount <= 0 || availableCount <= 0) {
            return Math.max(0, targetCount);
        }
        if (productIntent == null || productIntent.hasReliableBrand()) {
            return targetCount;
        }
        int expanded = Math.max(UNKNOWN_BRAND_POOL_MIN, targetCount * UNKNOWN_BRAND_POOL_MULTIPLIER);
        return Math.min(availableCount, Math.max(targetCount, expanded));
    }

    private List<ProductCard> applySort(List<ProductCard> products, SearchFilter filter) {
        return productSortService.applySort(products, filter);
    }

    private Map<IntentGate.IntentTier, Long> tierCounts(List<ProductCard> products,
                                                         ProductIntent productIntent,
                                                         com.visioncart.service.search.strategy.VerticalSearchStrategy strategy) {
        if (productIntent == null || strategy == null || products == null) {
            return Map.of();
        }
        return products.stream()
                .collect(Collectors.groupingBy(product -> strategy.classify(productIntent, product),
                        java.util.LinkedHashMap::new,
                        Collectors.counting()));
    }

    private Map<String, Long> top20TierShare(List<ProductCard> products,
                                             ProductIntent productIntent,
                                             com.visioncart.service.search.strategy.VerticalSearchStrategy strategy) {
        if (productIntent == null || strategy == null || products == null) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        List<ProductCard> top20 = products.stream().limit(20).toList();
        long exact = top20.stream()
                .filter(product -> strategy.classify(productIntent, product) == IntentGate.IntentTier.EXACT_MAIN)
                .count();
        long related = top20.stream()
                .filter(product -> strategy.classify(productIntent, product) == IntentGate.IntentTier.RELATED_ACCESSORY)
                .count();
        result.put("exactMain", exact);
        result.put("relatedAccessory", related);
        return result;
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

    private record PlatformSearchOutcome(
            String platform,
            List<ProductCard> products,
            boolean success,
            boolean skipped,
            String reason,
            long durationMs
    ) {
        private static PlatformSearchOutcome success(String platform, List<ProductCard> products, long durationMs) {
            return new PlatformSearchOutcome(platform, products == null ? List.of() : products, true, false, null, durationMs);
        }

        private static PlatformSearchOutcome skipped(String platform, String reason) {
            return new PlatformSearchOutcome(platform, List.of(), false, true, reason, 0);
        }

        private static PlatformSearchOutcome failed(String platform, String reason) {
            return new PlatformSearchOutcome(platform, List.of(), false, false, reason, 0);
        }

        private String summary() {
            if (success) {
                return platform + ":success(" + products.size() + ")";
            }
            return platform + ":" + (skipped ? "skipped" : "failed") + "(" + reason + ")";
        }
    }
}
