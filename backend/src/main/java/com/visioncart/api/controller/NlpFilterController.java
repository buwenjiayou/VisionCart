package com.visioncart.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.domain.RecognitionHistoryProduct;
import com.visioncart.repository.RecognitionHistoryProductRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.filter.*;
import com.visioncart.service.filter.capability.CapabilitySynonymRegistry;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.nlp.NlpModelService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.search.SearchTextUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * NLP filter endpoint: parse natural language → apply to session candidate cache → return filtered results.
 * Replaces the two-step flow (parse NLP → search) with a single endpoint.
 */
@RestController
@RequestMapping("/api/v1/nlp")
public class NlpFilterController {

    private static final Logger log = LoggerFactory.getLogger(NlpFilterController.class);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int LOW_RESULT_THRESHOLD = 5;
    private static final int RELAX_HINT_THRESHOLD = 20;

    private final NlpModelService nlpService;
    private final NlpConversationManager conversationManager;
    private final CandidateSessionCache sessionCache;
    private final CandidateFilterService filterService;
    private final SearchOrchestrator searchOrchestrator;
    private final RecognitionHistoryRepository historyRepository;
    private final RecognitionHistoryProductRepository productRepository;
    private final ObjectMapper objectMapper;
    private final com.visioncart.service.recognition.SessionHistoryService sessionHistoryService;
    private final com.visioncart.service.metrics.PerformanceMetricsService metricsService;
    private final com.visioncart.service.recognition.AsyncRecognitionTaskManager taskManager;
    private final FilterPlanner filterPlanner;
    private final FilterExecutionService filterExecutionService;
    private final NlpUndoService undoService;
    private final SafeActionExecutor safeActionExecutor;
    private final SessionContextService sessionContextService;
    private final CapabilitySynonymRegistry capabilityRegistry;

    public NlpFilterController(NlpModelService nlpService,
                               NlpConversationManager conversationManager,
                               CandidateSessionCache sessionCache,
                               CandidateFilterService filterService,
                               SearchOrchestrator searchOrchestrator,
                               RecognitionHistoryRepository historyRepository,
                               RecognitionHistoryProductRepository productRepository,
                               ObjectMapper objectMapper,
                               com.visioncart.service.recognition.SessionHistoryService sessionHistoryService,
                               com.visioncart.service.metrics.PerformanceMetricsService metricsService,
                               com.visioncart.service.recognition.AsyncRecognitionTaskManager taskManager,
                               FilterPlanner filterPlanner,
                               FilterExecutionService filterExecutionService,
                               NlpUndoService undoService,
                               SafeActionExecutor safeActionExecutor,
                               SessionContextService sessionContextService,
                               CapabilitySynonymRegistry capabilityRegistry) {
        this.nlpService = nlpService;
        this.conversationManager = conversationManager;
        this.sessionCache = sessionCache;
        this.filterService = filterService;
        this.searchOrchestrator = searchOrchestrator;
        this.historyRepository = historyRepository;
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
        this.sessionHistoryService = sessionHistoryService;
        this.metricsService = metricsService;
        this.taskManager = taskManager;
        this.filterPlanner = filterPlanner;
        this.filterExecutionService = filterExecutionService;
        this.undoService = undoService;
        this.safeActionExecutor = safeActionExecutor;
        this.sessionContextService = sessionContextService;
        this.capabilityRegistry = capabilityRegistry;
    }

    @PostMapping("/filter")
    public ResponseEntity<ApiResponse<NlpFilterResult>> filterCandidates(
            @Valid @RequestBody NlpFilterRequest request) {
        try {
            String sessionId = request.sessionId();
            String userInput = request.userInput();

            // 权限校验（active task 或 completed history 均可）
            Long userId = com.visioncart.config.SecurityUtils.currentUserId();
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.ok(ApiResponse.fail(403, "无权访问该识别任务"));
            }

            // 1. Check if session candidates exist
            boolean cacheExists = sessionCache.exists(sessionId);
            if (!cacheExists) {
                // Try to re-search from recognition history with full attributes
                log.info("Session cache expired for {}, attempting re-search from recognition history", sessionId);
                try {
                    Map<String, String> reSearchAttributes = buildReSearchAttributes(sessionId, request.context());
                    SearchRequest reSearchRequest = new SearchRequest(
                            sessionId, reSearchAttributes, SearchFilter.empty(), 1, 50, 300,
                            "overlay", request.regionMode());
                    SearchResult reSearchResult = searchOrchestrator.search(reSearchRequest);
                    if (reSearchResult.products() != null && !reSearchResult.products().isEmpty()) {
                        log.info("Re-search found {} candidates for session {}", reSearchResult.products().size(), sessionId);
                        // Continue with filtering below
                    } else {
                        // Fallback: load historical snapshot products
                        List<ProductCard> historicalProducts = loadHistoricalProducts(sessionId);
                        if (!historicalProducts.isEmpty()) {
                            log.info("Loaded {} historical products for expired session {}", historicalProducts.size(), sessionId);
                            sessionCache.saveDefaultClassifiedPool(sessionId, historicalProducts);
                            // Continue with filtering below
                        } else {
                            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                                    List.of(), SearchFilter.empty(), List.of(), 0, 0,
                                    false, false, true, false, null, false,
                                    "候选池已过期，未找到历史结果。请重新拍照搜索",
                                    false, false, false, List.of(), List.of())));
                        }
                    }
                } catch (Exception reSearchError) {
                    log.warn("Re-search failed for session {}: {}", sessionId, reSearchError.getMessage());
                    // Fallback: load historical snapshot products
                    List<ProductCard> historicalProducts = loadHistoricalProducts(sessionId);
                    if (!historicalProducts.isEmpty()) {
                        log.info("Loaded {} historical products as fallback for session {}", historicalProducts.size(), sessionId);
                        sessionCache.saveDefaultClassifiedPool(sessionId, historicalProducts);
                        // Continue with filtering below
                    } else {
                        return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                                List.of(), SearchFilter.empty(), List.of(), 0, 0,
                                false, false, true, false, null, false,
                                "候选池已过期，请重新拍照搜索",
                                false, false, false, List.of(), List.of())));
                    }
                }
            }

            // 2. NLP parse (with metrics)
            metricsService.recordNlpRequest();
            String nlpEngine = nlpService instanceof com.visioncart.service.nlp.SpringAiNlpService ? "llm" : "rule";
            io.micrometer.core.instrument.Timer.Sample nlpTimer = metricsService.startNlpParseTimer();
            NlpParseResult nlpResult;
            try {
                nlpResult = nlpService.parse(new NlpParseRequest(
                        sessionId, userInput, request.context()));
                metricsService.stopNlpParseTimer(nlpTimer, nlpEngine, true);
            } catch (Exception nlpError) {
                metricsService.stopNlpParseTimer(nlpTimer, nlpEngine, false);
                if ("llm".equals(nlpEngine)) {
                    metricsService.recordNlpLlmFallback();
                }
                throw nlpError;
            }

            // 3. Get candidates from session cache
            List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);

            // 4. Intent checks BEFORE any filter state mutation
            //    These must not corrupt the filter state.

            // 4a. Check for new search intent (e.g., "换成手机")
            if (isNewSearchIntent(userInput)) {
                String newCategory = extractNewCategory(userInput);
                String productName = request.context() != null ? request.context().productName() : "商品";
                // Do NOT update filter state — preserve existing results
                List<ProductCard> currentProducts = sessionCache.getBestCandidates(sessionId);
                SearchFilter currentFilter = conversationManager.getFilterState(sessionId);
                CandidateFilterService.FilterResult currentResult = filterService.filter(
                        currentProducts, currentFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
                return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                        currentResult.products(), currentFilter,
                        generateFilterTags(currentFilter), List.of(), candidates.size(), currentResult.resultCount(),
                        false, false, false, true, newCategory, false,
                        "你想找「" + newCategory + "」，这和当前图片不是同一商品",
                        false, false, false, List.of(), List.of())));
            }

            // 4b. Check for chat/invalid intent (e.g., "这个好不好")
            if (isChatIntent(userInput, nlpResult)) {
                // Do NOT update filter state
                SearchFilter currentFilter = conversationManager.getFilterState(sessionId);
                List<String> currentTags = generateFilterTags(currentFilter);
                List<ProductCard> currentProducts = sessionCache.getBestCandidates(sessionId);
                CandidateFilterService.FilterResult currentResult = filterService.filter(
                        currentProducts, currentFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
                return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                        currentResult.products(), currentFilter, currentTags,
                        currentResult.totalInPool(), currentResult.resultCount(),
                        false, false, false, false, null, false,
                        "你可以继续说：黑色的、1000元以内、评分高一点、只看官方旗舰店",
                        undoService.canUndo(sessionId), false, false, List.of(), List.of())));
            }

            // 5. NOW we know this is a real filter operation.
            SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
            // previousProducts = what user is currently seeing (filtered by existing filter state)
            List<ProductCard> previousProducts = filterService.filter(
                    sessionCache.getBestCandidates(sessionId), previousFilter, Map.of(), DEFAULT_PAGE_SIZE, 1).products();

            // 6. Merge filter WITHOUT committing to Redis — trial first
            SearchFilter newFilter = nlpResult.filter();
            SearchFilter tentativeFilter = conversationManager.mergeFilterWithoutCommit(sessionId, newFilter);

            // 7. Build FilterClauses from NLP result (also detects capabilities from user input)
            FilterClauseResult clauseResult = buildFilterClauses(nlpResult, tentativeFilter, userInput);
            List<FilterClause> clauses = clauseResult.clauses();
            String clientCategory = request.context() != null ? request.context().category() : null;
            String category = sessionContextService.resolve(sessionId, userId, clientCategory).normalizedCategory();

            // 7b. Merge detected capabilities into tentative filter so they persist in filter state
            if (!clauseResult.detectedCapabilities().isEmpty()) {
                Map<String, Boolean> mergedCaps = new java.util.LinkedHashMap<>();
                if (tentativeFilter.capabilities() != null) mergedCaps.putAll(tentativeFilter.capabilities());
                mergedCaps.putAll(clauseResult.detectedCapabilities());
                tentativeFilter = new SearchFilter(
                        tentativeFilter.priceRange(), tentativeFilter.platforms(), tentativeFilter.selfOperated(),
                        tentativeFilter.colors(), tentativeFilter.brands(), tentativeFilter.ratingMin(),
                        tentativeFilter.sortBy(), tentativeFilter.sortOrder(), tentativeFilter.keyword(),
                        tentativeFilter.attributes(), tentativeFilter.excludeRoles(), mergedCaps);
            }

            // 8. Generate execution plan
            FilterPlan plan = filterPlanner.plan(clauses, "NLP筛选: " + userInput);

            // 9. Build search attributes
            Map<String, String> attributes = request.context() != null
                    ? Map.of(SearchTextUtils.ATTR_KEYWORD, request.context().productName() != null
                            ? request.context().productName() : "")
                    : Map.of();

            // 10. Build a CLEAN base filter for the semantic pipeline.
            //     Strips: semantic keyword, semantic attributes, capabilities map.
            //     Keeps: price, platform, selfOperated, colors, brands, ratingMin, sortBy, sortOrder, excludeRoles.
            //     WHY: if "可以带上飞机" leaks into keyword, CandidateFilterService.applyFilter()
            //     will text-contains-match it and remove all normal power banks BEFORE capability evaluation.
            //
            //     Any semantic clause (capability, preference, exclusion, rerank, safe_filter) needs
            //     the semantic pool to avoid applyIntentFilter removing products that don't match
            //     semantic keywords like "性价比高", "适合女生", "不要山寨" etc.
            boolean hasSemanticClause = clauses.stream().anyMatch(c ->
                    c.type() == FilterClause.ClauseType.CAPABILITY
                 || c.type() == FilterClause.ClauseType.PREFERENCE
                 || c.type() == FilterClause.ClauseType.EXCLUSION
                 || c.applyMode() == FilterClause.ApplyMode.RERANK
                 || c.applyMode() == FilterClause.ApplyMode.EXCLUSION
                 || c.applyMode() == FilterClause.ApplyMode.SAFE_FILTER);
            SearchFilter baseFilter = stripSemanticForBaseFilter(tentativeFilter, clauses);

            log.info("NLP filter pipeline: clauses={}, hasSemantic={}, baseKeyword='{}', baseAttrs={}",
                    clauses.stream().map(c -> c.type() + ":" + c.field() + "[" + c.applyMode() + "]").toList(),
                    hasSemanticClause, baseFilter.keyword(),
                    baseFilter.attributes() != null ? baseFilter.attributes().keySet() : "null");

            // 10a. Prepare semantic pool — skips applyIntentFilter() to avoid relevance over-filtering.
            //     Evaluators need ALL structurally valid products, not just keyword-matched ones.
            List<ProductCard> semanticPool;
            if (hasSemanticClause) {
                semanticPool = filterService.prepareSemanticPool(
                        candidates, baseFilter, Math.min(candidates.size(), 1000));
            } else {
                // No semantic clause — use standard filter pipeline (validity → dedup → filter → intent → sort → paginate)
                CandidateFilterService.FilterResult baseResult = filterService.filter(
                        candidates, baseFilter, attributes, DEFAULT_PAGE_SIZE, 1);
                semanticPool = baseResult.products();
            }

            log.info("Semantic pool size before execution: {} (from {} candidates), category='{}'",
                    semanticPool.size(), candidates.size(), category);

            // Diagnostic: log first 3 products in semantic pool for debugging
            if (hasSemanticClause && !semanticPool.isEmpty()) {
                for (int i = 0; i < Math.min(3, semanticPool.size()); i++) {
                    ProductCard p = semanticPool.get(i);
                    log.info("  Semantic pool [{}]: title='{}', brand='{}', shop='{}', platform={}",
                            i, p.title(), p.brand(), p.shopName(), p.platform());
                }
            }

            // 10b. Apply semantic filter (capabilities, preferences, exclusions) on the pool
            FilterExecutionService.FilterExecutionResult execResult = filterExecutionService.execute(
                    plan, semanticPool, previousProducts, baseFilter, category);

            // 11. Expand search if semantic results are insufficient (regardless of commit status)
            //     Critical: when ZeroResultGuard rolls back (0 results, committed=false),
            //     we must still try expanding before giving up.
            if (hasSemanticClause
                    && execResult.products().size() < LOW_RESULT_THRESHOLD
                    && !candidates.isEmpty()) {
                log.info("Capability filter yielded {} results (committed={}), expanding to Top1000 for session {}",
                        execResult.products().size(), execResult.committed(), sessionId);
                try {
                    SearchFilter broadFilter = new SearchFilter(
                            null, tentativeFilter.platforms(), null, null, null, null, null, null,
                            null, Map.of(), List.of(), Map.of());
                    SearchRequest expandRequest = new SearchRequest(
                            sessionId, attributes, broadFilter, 1, 50, 1000,
                            "overlay", request.regionMode());
                    searchOrchestrator.search(expandRequest);
                    List<ProductCard> expandedCandidates = sessionCache.getBestCandidates(sessionId);
                    if (!expandedCandidates.isEmpty() && expandedCandidates.size() > candidates.size()) {
                        log.info("Expanded pool from {} to {} candidates", candidates.size(), expandedCandidates.size());
                        // Re-run semantic pool preparation on expanded pool
                        List<ProductCard> expandedSemanticPool = filterService.prepareSemanticPool(
                                expandedCandidates, baseFilter, Math.min(expandedCandidates.size(), 1000));
                        log.info("Expanded semantic pool size: {}", expandedSemanticPool.size());
                        // Re-run semantic filter on expanded pool
                        FilterExecutionService.FilterExecutionResult expandedExec = filterExecutionService.execute(
                                plan, expandedSemanticPool, previousProducts, baseFilter, category);
                        if (expandedExec.products().size() > execResult.products().size()) {
                            execResult = expandedExec;
                            log.info("Expanded search improved capability results to {} (committed={})",
                                    execResult.products().size(), execResult.committed());
                        }
                    }
                } catch (Exception expandError) {
                    log.warn("Expanded search failed, using original results: {}", expandError.getMessage());
                }
            }

            // 12. Determine response filter and tags based on commit decision
            SearchFilter responseFilter;
            List<String> filterTags;
            List<FilterTag> structuredFilterTags;

            if (execResult.committed()) {
                // Commit: save undo point, update filter state, use tentative filter
                // 保存 undo point 时同时保存 classifiedPool 快照
                com.visioncart.service.search.SearchCandidatePool currentPool =
                        sessionCache.getClassifiedPool(sessionId).orElse(null);
                undoService.saveUndoPoint(sessionId, previousFilter, previousProducts, userInput, "nlp", userInput, null, currentPool);
                conversationManager.setFilterState(sessionId, tentativeFilter);
                responseFilter = tentativeFilter;
                filterTags = generateFilterTags(tentativeFilter);
                structuredFilterTags = generateStructuredFilterTags(tentativeFilter, clauses);
                // Add capability/preference/exclusion tags from clauses
                for (FilterClause clause : clauses) {
                    if (clause.type() == FilterClause.ClauseType.CAPABILITY && clause.rawText() != null) {
                        filterTags.add(clause.rawText());
                    }
                }
            } else {
                // Rollback: keep previous filter state, don't save undo
                // P0-3: Do NOT include failed clauses in tags — only return previous filter's tags
                responseFilter = previousFilter;
                filterTags = generateFilterTags(previousFilter);
                structuredFilterTags = generateStructuredFilterTags(previousFilter, List.of());
            }

            // 13. Build message
            String message = execResult.warnings().isEmpty() ? null
                    : String.join("；", execResult.warnings());

            log.info("NLP filter: session={}, input='{}', pool={}, results={}, committed={}, keptPrevious={}",
                    sessionId, userInput, candidates.size(), execResult.products().size(),
                    execResult.committed(), execResult.keptPrevious());

            // Update MySQL product snapshot
            if (execResult.products() != null && !execResult.products().isEmpty()) {
                sessionHistoryService.archiveDisplayedProducts(sessionId, execResult.products());
            }

            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                    execResult.products(), responseFilter, filterTags, structuredFilterTags,
                    execResult.totalInPool(), execResult.products().size(),
                    execResult.products().size() < 5, execResult.products().size() < 20,
                    false, false, null, false, message,
                    undoService.canUndo(sessionId),
                    execResult.committed(),
                    execResult.keptPrevious(),
                    execResult.warnings(),
                    execResult.explanations())));

        } catch (Exception e) {
            log.error("NLP filter failed", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "筛选失败，请稍后重试"));
        }
    }

    /**
     * Delete a specific filter field from the current filter state.
     */
    @DeleteMapping("/filter/{sessionId}/field/{fieldName}")
    public ResponseEntity<ApiResponse<NlpFilterResult>> removeFilterField(
            @PathVariable String sessionId,
            @PathVariable String fieldName,
            @org.springframework.web.bind.annotation.RequestBody(required = false) SearchFilter currentFilter) {
        try {
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.fail(403, "无权访问该识别任务"));
            }
            // 如果 Redis 中没有 filter state（非 NLP 路径），用客户端传入的当前 filter 兜底
            if (currentFilter != null) {
                conversationManager.ensureFilterState(sessionId, currentFilter);
            }
            SearchFilter updated = conversationManager.removeFilterField(sessionId, fieldName);

            List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
            CandidateFilterService.FilterResult result = filterService.filter(
                    candidates, updated, Map.of(), DEFAULT_PAGE_SIZE, 1);
            List<String> filterTags = generateFilterTags(updated);
            List<FilterTag> structuredFilterTags = generateStructuredFilterTags(updated, List.of());

            // 更新 MySQL 商品快照
            if (result.products() != null && !result.products().isEmpty()) {
                sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
            }

            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                    result.products(), updated, filterTags, structuredFilterTags,
                    result.totalInPool(), result.resultCount(),
                    result.needExpand(), result.needRelaxHint(),
                    false, false, null, false, null,
                    false, true, false, List.of(), List.of())));
        } catch (Exception e) {
            log.error("Remove filter field failed", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "操作失败，请稍后重试"));
        }
    }

    /**
     * Delete a specific filter tag by its structured tag ID or filterPath.
     * Supports precise deletion using the structured FilterTag's filterPath
     * (e.g. "capabilities.airplane_allowed", "colors.black", "brands.小米").
     *
     * Action-only tags (preferences/exclusions/semantic) are routed to undo
     * because they were applied as side effects of the NLP action, not as
     * direct filter field mutations.
     */
    @DeleteMapping("/filter/{sessionId}/tag/{tagId}")
    public ResponseEntity<ApiResponse<NlpFilterResult>> removeFilterTag(
            @PathVariable String sessionId,
            @PathVariable String tagId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) SearchFilter currentFilter) {
        try {
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.fail(403, "无权访问该识别任务"));
            }
            if (currentFilter != null) {
                conversationManager.ensureFilterState(sessionId, currentFilter);
            }

            // Resolve tagId to a filterPath
            // tagId can be: "clause-airplane_allowed", "field-colors-black", "capabilities.airplane_allowed",
            //               "preference-适合年轻人", "exclusion-不要山寨"
            String filterPath = resolveTagIdToFilterPath(tagId);
            if (filterPath == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.fail(400, "无法识别的标签ID: " + tagId));
            }

            // P0-2: Action-only tags (preferences/exclusions/semantic) cannot be removed
            // by mutating filter state — they were applied as side effects of the NLP action.
            // Route to undo instead.
            if (isActionOnlyTag(filterPath)) {
                NlpUndoService.UndoResult undoResult = undoService.undo(sessionId);
                if (undoResult == null) {
                    return ResponseEntity.ok(ApiResponse.fail(404, "没有可撤回的筛选"));
                }
                SearchFilter restoredFilter = undoResult.filter() != null ? undoResult.filter() : SearchFilter.empty();
                conversationManager.setFilterState(sessionId, restoredFilter);

                List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
                CandidateFilterService.FilterResult result = filterService.filter(
                        candidates, restoredFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
                List<String> filterTags = generateFilterTags(restoredFilter);
                List<FilterTag> structuredFilterTags = generateStructuredFilterTags(restoredFilter, List.of());

                if (result.products() != null && !result.products().isEmpty()) {
                    sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
                }

                return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                        result.products(), restoredFilter, filterTags, structuredFilterTags,
                        result.totalInPool(), result.resultCount(),
                        false, false, false, false, null, false,
                        "已撤回筛选「" + undoResult.undoneQuery() + "」",
                        undoService.canUndo(sessionId),
                        true, false, List.of(), List.of())));
            }

            // Use the existing removeFilterField with dot-notation support
            SearchFilter updated = conversationManager.removeFilterField(sessionId, filterPath);

            List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
            CandidateFilterService.FilterResult result = filterService.filter(
                    candidates, updated, Map.of(), DEFAULT_PAGE_SIZE, 1);
            List<String> filterTags = generateFilterTags(updated);
            List<FilterTag> structuredFilterTags = generateStructuredFilterTags(updated, List.of());

            if (result.products() != null && !result.products().isEmpty()) {
                sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
            }

            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                    result.products(), updated, filterTags, structuredFilterTags,
                    result.totalInPool(), result.resultCount(),
                    result.needExpand(), result.needRelaxHint(),
                    false, false, null, false, null,
                    false, true, false, List.of(), List.of())));
        } catch (Exception e) {
            log.error("Remove filter tag failed for tagId={}", tagId, e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "操作失败，请稍后重试"));
        }
    }

    /**
     * Resolve a tag ID to a filterPath for precise deletion.
     * Supports formats:
     * - "clause-airplane_allowed" → "capabilities.airplane_allowed"
     * - "field-colors-black" → "colors.black"
     * - "preference-适合年轻人" → "preferences.适合年轻人"
     * - "exclusion-不要山寨" → "exclusions.不要山寨"
     * - "capabilities.airplane_allowed" (direct filterPath)
     * - "airplane_allowed" (capability code → "capabilities.airplane_allowed")
     */
    private String resolveTagIdToFilterPath(String tagId) {
        if (tagId == null || tagId.isBlank()) return null;

        // Direct filterPath (contains dot)
        if (tagId.contains(".")) return tagId;

        // clause-{code} → capabilities.{code}
        if (tagId.startsWith("clause-")) {
            String code = tagId.substring("clause-".length());
            return "capabilities." + code;
        }

        // preference-{label} → preferences.{label}
        if (tagId.startsWith("preference-")) {
            return "preferences." + tagId.substring("preference-".length());
        }

        // exclusion-{label} → exclusions.{label}
        if (tagId.startsWith("exclusion-")) {
            return "exclusions." + tagId.substring("exclusion-".length());
        }

        // semantic-{label} → semantic.{label}
        if (tagId.startsWith("semantic-")) {
            return "semantic." + tagId.substring("semantic-".length());
        }

        // field-{path} → replace first hyphen with dot
        if (tagId.startsWith("field-")) {
            String path = tagId.substring("field-".length());
            // "field-colors-black" → "colors.black"
            int firstHyphen = path.indexOf('-');
            if (firstHyphen > 0) {
                return path.substring(0, firstHyphen) + "." + path.substring(firstHyphen + 1);
            }
            return path;
        }

        // Try as capability code directly
        String resolved = conversationManager.resolveCapabilityCode(tagId);
        if (resolved != null) {
            return "capabilities." + resolved;
        }

        return null;
    }

    /**
     * P0-2: Detect action-only tags that cannot be removed by filter field mutation.
     * These were applied as side effects of NLP actions (preferences, exclusions, semantic judgments)
     * and must be reverted via undo, not removeFilterField.
     */
    private boolean isActionOnlyTag(String filterPath) {
        return filterPath != null && (
                filterPath.startsWith("preferences.")
                || filterPath.startsWith("exclusions.")
                || filterPath.startsWith("semantic.")
                || filterPath.startsWith("criteria.")
        );
    }

    /**
     * Clear all filters for a session.
     */
    @DeleteMapping("/filter/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> clearFilters(@PathVariable String sessionId) {
        try {
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.fail(403, "无权访问该识别任务"));
            }
            conversationManager.clearFilterState(sessionId);
            conversationManager.clear(sessionId);
            undoService.clearUndoStack(sessionId);
            return ResponseEntity.ok(ApiResponse.ok(null));
        } catch (Exception e) {
            log.error("Clear filters failed for session {}", sessionId, e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "清空筛选条件失败，请稍后重试"));
        }
    }

    /**
     * Replace the entire filter state (for frontend tag interactions).
     * NOW routes through SafeActionExecutor + ZeroResultGuard to prevent
     * committing unhealthy filter states (e.g., 0 results).
     */
    @PutMapping("/filter/{sessionId}")
    public ResponseEntity<ApiResponse<NlpFilterResult>> replaceFilterState(
            @PathVariable String sessionId,
            @RequestBody SearchFilter newFilter) {
        try {
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.fail(403, "无权访问该识别任务"));
            }

            List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
            SearchFilter previousFilter = conversationManager.getFilterState(sessionId);

            // Build FilterClauses from the new filter's structural fields
            List<FilterClause> clauses = buildClausesFromSearchFilter(newFilter);

            // Use SafeActionExecutor for trial-first execution with ZeroResultGuard
            SafeActionExecutor.SafeActionResult result = safeActionExecutor.execute(
                    sessionId, candidates, clauses,
                    previousFilter,   // baseFilter
                    newFilter,        // tentativeFilter
                    previousFilter,   // previousFilter
                    candidates,       // previousProducts
                    null,             // category
                    "manual_filter",
                    "手动筛选"
            );

            SearchFilter responseFilter = result.committed() ? newFilter : previousFilter;
            List<String> filterTags = generateFilterTags(responseFilter);
            List<FilterTag> structuredFilterTags = generateStructuredFilterTags(responseFilter, clauses);

            // 更新 MySQL 商品快照
            if (result.products() != null && !result.products().isEmpty()) {
                sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
            }

            String message = result.committed() ? null
                    : (result.message() != null ? result.message() : "筛选结果为空，已保留原结果");

            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                    result.products(), responseFilter, filterTags, structuredFilterTags,
                    candidates.size(), result.products().size(),
                    result.products().size() < 5, result.products().size() < 20,
                    false, false, null, false, message,
                    undoService.canUndo(sessionId),
                    result.committed(),
                    result.keptPrevious(),
                    result.warnings(),
                    result.explanations())));
        } catch (Exception e) {
            log.error("Replace filter state failed", e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "操作失败，请稍后重试"));
        }
    }

    /**
     * Build FilterClauses from a SearchFilter's structural fields.
     * Used by replaceFilterState to route through SafeActionExecutor.
     */
    private List<FilterClause> buildClausesFromSearchFilter(SearchFilter filter) {
        List<FilterClause> clauses = new ArrayList<>();
        if (filter == null) return clauses;
        int counter = 0;

        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) {
                clauses.add(new FilterClause("mf" + (++counter),
                        "价格≤" + filter.priceRange().max(), FilterClause.ClauseType.STRUCTURED,
                        "price_max", "<=", filter.priceRange().max(),
                        FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
            }
            if (filter.priceRange().min() != null) {
                clauses.add(new FilterClause("mf" + (++counter),
                        "价格≥" + filter.priceRange().min(), FilterClause.ClauseType.STRUCTURED,
                        "price_min", ">=", filter.priceRange().min(),
                        FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
            }
        }
        if (filter.ratingMin() != null) {
            clauses.add(new FilterClause("mf" + (++counter),
                    "评分≥" + filter.ratingMin(), FilterClause.ClauseType.STRUCTURED,
                    "rating_min", ">=", filter.ratingMin(),
                    FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
        }
        if (filter.platforms() != null) {
            for (String platform : filter.platforms()) {
                clauses.add(new FilterClause("mf" + (++counter),
                        "平台=" + platform, FilterClause.ClauseType.STRUCTURED,
                        "platform", "eq", platform,
                        FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
            }
        }
        if (filter.colors() != null) {
            for (String color : filter.colors()) {
                clauses.add(new FilterClause("mf" + (++counter),
                        "颜色=" + color, FilterClause.ClauseType.STRUCTURED,
                        "color", "contains", color,
                        FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
            }
        }
        if (filter.brands() != null) {
            for (String brand : filter.brands()) {
                clauses.add(new FilterClause("mf" + (++counter),
                        "品牌=" + brand, FilterClause.ClauseType.STRUCTURED,
                        "brand", "contains", brand,
                        FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
            }
        }
        if (Boolean.TRUE.equals(filter.selfOperated())) {
            clauses.add(new FilterClause("mf" + (++counter),
                    "自营", FilterClause.ClauseType.STRUCTURED,
                    "self_operated", "eq", true,
                    FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE));
        }
        return clauses;
    }

    private boolean isNewSearchIntent(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();
        // Use CategoryNormalizer to detect any known category keyword
        boolean hasCategory = !com.visioncart.service.search.CategoryNormalizer.normalizeFromText(lower).isEmpty();
        // "换成X" or "改搜X" → always new search if category present
        boolean switchIntent = (lower.contains("换成") || lower.contains("改搜")) && hasCategory;
        // "不要X了" → new search only with "了" + category
        boolean rejectIntent = lower.contains("不要") && lower.contains("了") && hasCategory;
        return switchIntent || rejectIntent;
    }

    private String extractNewCategory(String input) {
        if (input == null) return "新商品";
        String code = com.visioncart.service.search.CategoryNormalizer.normalizeFromText(input.toLowerCase());
        if (!code.isEmpty()) {
            return com.visioncart.service.search.CategoryNormalizer.displayName(code);
        }
        return "新商品";
    }

    private boolean isChatIntent(String input, NlpParseResult nlpResult) {
        // LLM detected chat intent
        if (nlpResult != null && "chat".equals(nlpResult.decision())) {
            return true;
        }
        // Keyword-based fallback detection
        if (input == null) return false;
        String lower = input.trim().toLowerCase();
        return lower.equals("这个怎么样") || lower.equals("帮我看看")
                || lower.equals("随便推荐一下") || lower.equals("推荐一下")
                || lower.equals("怎么样") || lower.equals("好不好");
    }

    private List<String> generateFilterTags(SearchFilter filter) {
        List<String> tags = new ArrayList<>();
        if (filter == null) return tags;

        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) {
                tags.add("≤¥" + formatPrice(filter.priceRange().max()));
            }
            if (filter.priceRange().min() != null) {
                tags.add("≥¥" + formatPrice(filter.priceRange().min()));
            }
        }
        if (filter.platforms() != null) {
            filter.platforms().stream().limit(2).forEach(p -> tags.add(p));
        }
        if (Boolean.TRUE.equals(filter.selfOperated())) {
            tags.add("自营");
        }
        if (filter.colors() != null) {
            filter.colors().forEach(c -> tags.add(c));
        }
        if (filter.brands() != null) {
            filter.brands().forEach(b -> tags.add(b));
        }
        if (filter.ratingMin() != null) {
            tags.add("≥" + filter.ratingMin() + "分");
        }
        if (filter.keyword() != null && !filter.keyword().isBlank() && !filter.keyword().startsWith("!")) {
            tags.add(filter.keyword());
        }
        return tags;
    }

    private List<FilterTag> generateStructuredFilterTags(SearchFilter filter, List<FilterClause> clauses) {
        List<FilterTag> tags = new ArrayList<>();
        if (filter == null) return tags;

        // Structured field tags
        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) {
                tags.add(FilterTag.ofField("≤¥" + formatPrice(filter.priceRange().max()), "price_range.max"));
            }
            if (filter.priceRange().min() != null) {
                tags.add(FilterTag.ofField("≥¥" + formatPrice(filter.priceRange().min()), "price_range.min"));
            }
        }
        if (filter.platforms() != null) {
            for (String p : filter.platforms()) {
                tags.add(FilterTag.ofField(p, "platforms." + p));
            }
        }
        if (Boolean.TRUE.equals(filter.selfOperated())) {
            tags.add(FilterTag.ofField("自营", "self_operated"));
        }
        if (filter.colors() != null) {
            for (String c : filter.colors()) {
                tags.add(FilterTag.ofField(c, "colors." + c));
            }
        }
        if (filter.brands() != null) {
            for (String b : filter.brands()) {
                tags.add(FilterTag.ofField(b, "brands." + b));
            }
        }
        if (filter.ratingMin() != null) {
            tags.add(FilterTag.ofField("≥" + filter.ratingMin() + "分", "rating_min"));
        }
        if (filter.keyword() != null && !filter.keyword().isBlank() && !filter.keyword().startsWith("!")) {
            tags.add(FilterTag.ofField(filter.keyword(), "keyword"));
        }

        // Capability/preference/exclusion tags from clauses
        if (clauses != null) {
            for (FilterClause clause : clauses) {
                String code = clause.field();
                String label = capabilityCodeToDisplayText(code);
                String rawText = clause.rawText();
                switch (clause.type()) {
                    case CAPABILITY:
                        tags.add(FilterTag.ofCapability(code, label, rawText));
                        break;
                    case PREFERENCE:
                        tags.add(FilterTag.ofPreference(code, label, rawText));
                        break;
                    case EXCLUSION:
                        tags.add(FilterTag.ofExclusion(code, label, rawText));
                        break;
                    default:
                        break;
                }
            }
        }

        // Capability tags from filter.capabilities map (for restored/undo states)
        if (filter.capabilities() != null) {
            for (Map.Entry<String, Boolean> entry : filter.capabilities().entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    String code = entry.getKey();
                    // Avoid duplicates from clauses
                    boolean alreadyAdded = tags.stream().anyMatch(t ->
                            t.filterPath() != null && t.filterPath().equals("capabilities." + code));
                    if (!alreadyAdded) {
                        String label = capabilityCodeToDisplayText(code);
                        tags.add(FilterTag.ofCapability(code, label, null));
                    }
                }
            }
        }

        return tags;
    }

    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.valueOf((long) price);
        }
        return String.format("%.0f", price);
    }

    private boolean verifySessionOwnership(String sessionId) {
        Long userId = com.visioncart.config.SecurityUtils.currentUserId();
        return taskManager.belongsToUser(sessionId, userId)
                || historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
    }

    /**
     * Build a "clean" base filter for the semantic capability pipeline.
     * Strips ALL semantic signals: attributes, keyword, and capabilities.
     * Only keeps structural fields: price, platform, selfOperated, colors, brands, ratingMin, sortBy, sortOrder, excludeRoles.
     *
     * Critical: if keyword="可以带上飞机" leaks into CandidateFilterService,
     * applyFilter() will text-contains-match it and remove all normal power banks
     * BEFORE they reach the capability evaluator.
     */
    private SearchFilter stripSemanticForBaseFilter(SearchFilter filter, List<FilterClause> clauses) {
        if (filter == null) return SearchFilter.empty();

        // Strip for ANY semantic clause (capability, preference, exclusion, rerank, safe_filter)
        boolean hasSemantic = clauses.stream().anyMatch(c ->
                c.type() == FilterClause.ClauseType.CAPABILITY
             || c.type() == FilterClause.ClauseType.PREFERENCE
             || c.type() == FilterClause.ClauseType.EXCLUSION
             || c.applyMode() == FilterClause.ApplyMode.RERANK
             || c.applyMode() == FilterClause.ApplyMode.EXCLUSION
             || c.applyMode() == FilterClause.ApplyMode.SAFE_FILTER);

        // Strip semantic keyword
        String keyword = filter.keyword();
        if (hasSemantic && isSemanticKeyword(keyword)) {
            log.info("Stripping semantic keyword from base filter: {}", keyword);
            keyword = null;
        }

        // Strip semantic attributes
        Map<String, String> filteredAttrs = new java.util.LinkedHashMap<>();
        if (filter.attributes() != null) {
            for (Map.Entry<String, String> entry : filter.attributes().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.startsWith("exclude_")) {
                    filteredAttrs.put(key, value);
                    continue;
                }
                if (!isSemanticAttributeValue(value)) {
                    filteredAttrs.put(key, value);
                } else {
                    log.debug("Stripping semantic attribute from base filter: {}={}", key, value);
                }
            }
        }

        // Base filter does NOT carry capabilities — those are executed by FilterExecutionService
        return new SearchFilter(
                filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), filter.ratingMin(),
                filter.sortBy(), filter.sortOrder(), keyword,
                filteredAttrs, filter.excludeRoles(), Map.of());
    }

    private boolean isSemanticKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return false;
        String lower = keyword.toLowerCase();
        // Capability keywords
        return lower.contains("带上飞机") || lower.contains("可登机") || lower.contains("登机")
                || lower.contains("民航") || lower.contains("航空") || lower.contains("飞机")
                || lower.contains("防水") || lower.contains("防尘") || lower.contains("防摔")
                || lower.contains("快充") || lower.contains("闪充")
                || lower.contains("护眼") || lower.contains("不伤眼")
                || lower.contains("降噪") || lower.contains("主动降噪")
                || lower.contains("适合长跑") || lower.contains("跑步")
                || lower.contains("适合婴儿") || lower.contains("婴儿")
                || lower.contains("长续航") || lower.contains("续航")
                || lower.contains("性价比") || lower.contains("轻便")
                || lower.contains("好看") || lower.contains("高级")
                || lower.contains("适合送礼") || lower.contains("送礼")
                // Preference keywords
                || lower.contains("适合女生") || lower.contains("女生")
                || lower.contains("适合通勤") || lower.contains("通勤")
                || lower.contains("质量好") || lower.contains("耐用")
                || lower.contains("品牌好") || lower.contains("大牌")
                || lower.contains("新款") || lower.contains("时尚")
                || lower.contains("实用") || lower.contains("百搭")
                // Exclusion keywords
                || lower.contains("不要山寨") || lower.contains("不要配件")
                || lower.contains("不要赠品") || lower.contains("不要低配")
                || lower.contains("排除") || lower.contains("不要");
    }

    private boolean isSemanticAttributeValue(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        return lower.contains("带上飞机") || lower.contains("可登机") || lower.contains("登机")
                || lower.contains("飞机") || lower.contains("民航")
                || lower.contains("防水") || lower.contains("防尘") || lower.contains("防摔")
                || lower.contains("快充") || lower.contains("闪充") || lower.contains("快速充电")
                || lower.contains("护眼") || lower.contains("不伤眼") || lower.contains("无频闪")
                || lower.contains("降噪") || lower.contains("主动降噪")
                || lower.contains("适合长跑") || lower.contains("跑步") || lower.contains("长跑")
                || lower.contains("适合婴儿") || lower.contains("婴儿") || lower.contains("母婴")
                || lower.contains("长续航") || lower.contains("续航久")
                || lower.contains("性价比") || lower.contains("轻便")
                || lower.contains("好看") || lower.contains("高级")
                || lower.contains("适合送礼") || lower.contains("送礼") || lower.contains("送女生");
    }

    /**
     * Build FilterClauses from NLP parse result and merged filter.
     * Converts structured filter fields + capability attributes into typed clauses.
     */
    private record FilterClauseResult(List<FilterClause> clauses, Map<String, Boolean> detectedCapabilities) {}

    private FilterClauseResult buildFilterClauses(NlpParseResult nlpResult, SearchFilter mergedFilter,
                                                   String userInput) {
        List<FilterClause> clauses = new ArrayList<>();
        Map<String, Boolean> detectedCaps = new java.util.LinkedHashMap<>();
        int counter = 0;

        // Structured clauses from price range
        if (mergedFilter.priceRange() != null) {
            if (mergedFilter.priceRange().max() != null) {
                clauses.add(FilterClauseStructured("c" + (++counter),
                        "价格≤" + mergedFilter.priceRange().max(), "price_max", "<=", mergedFilter.priceRange().max()));
            }
            if (mergedFilter.priceRange().min() != null) {
                clauses.add(FilterClauseStructured("c" + (++counter),
                        "价格≥" + mergedFilter.priceRange().min(), "price_min", ">=", mergedFilter.priceRange().min()));
            }
        }

        // Structured clauses from rating
        if (mergedFilter.ratingMin() != null) {
            clauses.add(FilterClauseStructured("c" + (++counter),
                    "评分≥" + mergedFilter.ratingMin(), "rating_min", ">=", mergedFilter.ratingMin()));
        }

        // Structured clauses from platform
        if (mergedFilter.platforms() != null) {
            for (String platform : mergedFilter.platforms()) {
                clauses.add(FilterClauseStructured("c" + (++counter),
                        "平台=" + platform, "platform", "eq", platform));
            }
        }

        // LLM-detected clauses (capabilities, preferences, exclusions from LLM output)
        if (nlpResult.clauses() != null) {
            for (NlpParseResult.DetectedClause dc : nlpResult.clauses()) {
                boolean alreadyExists = clauses.stream()
                        .anyMatch(c -> c.field() != null && c.field().equals(dc.field()));
                if (!alreadyExists) {
                    switch (dc.type()) {
                        case "capability" -> {
                            clauses.add(FilterClause.capability("c" + (++counter),
                                    dc.rawText() != null ? dc.rawText() : capabilityCodeToDisplayText(dc.field()),
                                    dc.field(), dc.confidence()));
                            detectedCaps.put(dc.field(), true);
                        }
                        case "preference" -> clauses.add(FilterClause.preference("c" + (++counter),
                                dc.rawText() != null ? dc.rawText() : dc.field(), dc.field()));
                        case "exclusion" -> clauses.add(FilterClause.exclusion("c" + (++counter),
                                dc.rawText() != null ? dc.rawText() : dc.field(), dc.field(), true));
                    }
                }
            }
        }

        // Capability clauses from the capabilities map (explicit capability conditions)
        if (mergedFilter.capabilities() != null) {
            for (Map.Entry<String, Boolean> entry : mergedFilter.capabilities().entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    boolean alreadyExists = clauses.stream()
                            .anyMatch(c -> c.field() != null && c.field().equals(entry.getKey()));
                    if (!alreadyExists) {
                        String rawText = capabilityCodeToDisplayText(entry.getKey());
                        clauses.add(FilterClause.capability("c" + (++counter),
                                rawText, entry.getKey(), 0.9));
                    }
                }
            }
        }

        // Capability clauses from attributes (the key innovation)
        if (mergedFilter.attributes() != null) {
            for (Map.Entry<String, String> entry : mergedFilter.attributes().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.startsWith("exclude_")) continue;

                // Detect capability-type attributes
                if (isCapabilityAttribute(key, value)) {
                    String capField = mapCapabilityField(key, value);
                    clauses.add(FilterClause.capability("c" + (++counter), value, capField, 0.8));
                    detectedCaps.put(capField, true);
                } else if (isPreferenceAttribute(key, value)) {
                    clauses.add(FilterClause.preference("c" + (++counter), value, key));
                } else if (key.startsWith("exclude_") || isExclusionValue(value)) {
                    clauses.add(FilterClause.exclusion("c" + (++counter), value, key, value));
                }
            }
        }

        // Capability/preference clauses from keyword detection in user input
        // Uses centralized CapabilitySynonymRegistry for all synonym matching
        String lowerInput = userInput != null ? userInput.toLowerCase() : "";
        for (String code : capabilityRegistry.allCodes()) {
            boolean alreadyExists = clauses.stream()
                    .anyMatch(c -> c.field() != null && c.field().equals(code));
            if (alreadyExists) continue;

            // Check if any alias for this capability appears in user input
            String matchedAlias = null;
            for (String alias : capabilityRegistry.aliases(code)) {
                if (lowerInput.contains(alias.toLowerCase())) {
                    matchedAlias = alias;
                    break;
                }
            }
            if (matchedAlias != null) {
                if (capabilityRegistry.isPreferenceCode(code)) {
                    clauses.add(FilterClause.preference("c" + (++counter), matchedAlias, code));
                } else {
                    clauses.add(FilterClause.capability("c" + (++counter), matchedAlias, code, 0.85));
                    detectedCaps.put(code, true);
                }
            }
        }

        // Classify any remaining unclassified clauses
        List<FilterClause> classified = filterPlanner.classifyClauses(clauses);
        return new FilterClauseResult(classified, detectedCaps);
    }

    private FilterClause FilterClauseStructured(String id, String rawText, String field, String op, Object value) {
        return new FilterClause(id, rawText, FilterClause.ClauseType.STRUCTURED, field, op, value,
                FilterClause.ApplyMode.HARD_FILTER, 1.0, null, FilterClause.FallbackPolicy.NONE);
    }

    private boolean isCapabilityAttribute(String key, String value) {
        String lowerKey = key.toLowerCase();
        String lowerValue = value != null ? value.toLowerCase() : "";
        return lowerKey.contains("usage") || lowerKey.contains("场景") ||
                lowerValue.contains("可以") || lowerValue.contains("能") ||
                lowerValue.contains("适合") || lowerValue.contains("支持") ||
                lowerValue.contains("防水") || lowerValue.contains("护眼") ||
                lowerValue.contains("降噪") || lowerValue.contains("快充") ||
                lowerValue.contains("登机") || lowerValue.contains("婴儿");
    }

    private boolean isPreferenceAttribute(String key, String value) {
        String lowerValue = value != null ? value.toLowerCase() : "";
        return lowerValue.contains("好看") || lowerValue.contains("高级") ||
                lowerValue.contains("轻便") || lowerValue.contains("性价比") ||
                lowerValue.contains("时尚") || lowerValue.contains("简约");
    }

    private boolean isExclusionValue(String value) {
        if (value == null) return false;
        return value.startsWith("不要") || value.startsWith("排除") || value.startsWith("exclude");
    }

    private String capabilityCodeToDisplayText(String code) {
        return capabilityRegistry.displayText(code);
    }

    private String mapCapabilityField(String key, String value) {
        String resolved = capabilityRegistry.resolveToCode(value);
        return resolved != null ? resolved : key;
    }

    /**
     * Undo the last NLP filter operation.
     */
    @PostMapping("/filter/{sessionId}/undo")
    public ResponseEntity<ApiResponse<NlpFilterResult>> undoFilter(@PathVariable String sessionId) {
        try {
            if (!verifySessionOwnership(sessionId)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(ApiResponse.fail(403, "无权访问该识别任务"));
            }

            NlpUndoService.UndoResult undoResult = undoService.undo(sessionId);
            if (undoResult == null) {
                return ResponseEntity.ok(ApiResponse.fail(404, "没有可撤回的筛选"));
            }

            // Restore the previous filter state
            SearchFilter restoredFilter = undoResult.filter();
            if (restoredFilter != null) {
                conversationManager.setFilterState(sessionId, restoredFilter);
            } else {
                conversationManager.clearFilterState(sessionId);
                restoredFilter = SearchFilter.empty();
            }

            // Re-filter with restored filter
            if (undoResult.restoredClassifiedPool() != null) {
                sessionCache.saveClassifiedPool(sessionId, undoResult.restoredClassifiedPool());
                sessionCache.saveCandidates(sessionId,
                        undoResult.restoredClassifiedPool().toProductCards(),
                        undoResult.restoredClassifiedPool().searchIdentity());
            }
            List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
            CandidateFilterService.FilterResult result = filterService.filter(
                    candidates, restoredFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
            List<String> filterTags = generateFilterTags(restoredFilter);
            List<FilterTag> structuredFilterTags = generateStructuredFilterTags(restoredFilter, List.of());

            // Update MySQL snapshot
            if (result.products() != null && !result.products().isEmpty()) {
                sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
            }

            return ResponseEntity.ok(ApiResponse.ok(new NlpFilterResult(
                    result.products(), restoredFilter, filterTags, structuredFilterTags,
                    result.totalInPool(), result.resultCount(),
                    false, false, false, false, null, false,
                    "已撤回筛选「" + undoResult.undoneQuery() + "」",
                    undoService.canUndo(sessionId),
                    true, false, List.of(), List.of())));
        } catch (Exception e) {
            log.error("Undo filter failed for session {}", sessionId, e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "撤回失败，请稍后重试"));
        }
    }

    /**
     * Load historical snapshot products for a session (fallback when cache expires).
     */
    private List<ProductCard> loadHistoricalProducts(String sessionId) {
        List<RecognitionHistoryProduct> entities = productRepository.findByHistoryIdOrderBySortNoAsc(sessionId);
        if (entities.isEmpty()) return List.of();

        return entities.stream().map(e -> new ProductCard(
                e.getProductId(),
                e.getTitle() != null ? e.getTitle() : "",
                e.getCoverImage() != null ? e.getCoverImage() : "",
                e.getPrice() != null ? e.getPrice() : java.math.BigDecimal.ZERO,
                e.getOriginalPrice(),
                e.getPlatform() != null ? e.getPlatform() : "",
                false, // selfOperated not stored in snapshot
                "", // shopName not stored
                e.getRating() != null ? e.getRating().doubleValue() : 0.0,
                e.getSales() != null ? e.getSales() : 0L,
                e.getSimilarityScore() != null ? e.getSimilarityScore().doubleValue() : 0.0,
                List.of(), // tags not stored
                e.getDetailUrl() != null ? e.getDetailUrl() : "",
                e.getBrand(),
                null, null,
                e.getMainCategoryCode(),
                e.getProductRole()
        )).toList();
    }

    /**
     * Build re-search attributes from history when session cache expires.
     * Restores full recognition attributes (category, brand, color, material, etc.)
     * instead of just productName.
     */
    private Map<String, String> buildReSearchAttributes(String sessionId,
                                                         com.visioncart.api.dto.NlpParseRequest.NlpContext context) {
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        Long userId = com.visioncart.config.SecurityUtils.currentUserId();
        // Try to restore from history
        historyRepository.findBySessionIdAndUserId(sessionId, userId).ifPresent(history -> {
            try {
                if (history.getAttributesJson() != null && !history.getAttributesJson().isBlank()) {
                    Map<String, com.visioncart.api.dto.AttributeValue> parsed = objectMapper.readValue(
                            history.getAttributesJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, com.visioncart.api.dto.AttributeValue>>() {});
                    parsed.forEach((key, av) -> {
                        if (av.value() != null && !av.value().isBlank() && !"未知".equals(av.value())) {
                            attrs.put(key, av.value());
                        }
                    });
                }
            } catch (Exception e) {
                log.debug("Failed to parse history attributes for re-search: {}", e.getMessage());
            }
            if (history.getKeywords() != null && !history.getKeywords().isBlank()) {
                attrs.putIfAbsent(SearchTextUtils.ATTR_KEYWORD, history.getKeywords().split(",")[0].trim());
            }
        });
        // Fallback: use context productName if history had nothing useful
        if (attrs.isEmpty() && context != null && context.productName() != null && !context.productName().isBlank()) {
            attrs.put(SearchTextUtils.ATTR_KEYWORD, context.productName());
        }
        return attrs;
    }
}
