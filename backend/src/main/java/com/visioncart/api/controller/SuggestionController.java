package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.filter.ActionCompiler;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.metrics.PerformanceMetricsService;
import com.visioncart.service.suggestion.DeepSuggestionService;
import com.visioncart.service.suggestion.SuggestionCardCache;
import com.visioncart.service.suggestion.SuggestionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1/suggestions")
public class SuggestionController {
    private static final Logger log = LoggerFactory.getLogger(SuggestionController.class);
    private static final long INSIGHT_TIMEOUT_MS = 800;
    private final SuggestionService suggestionService;
    private final DeepSuggestionService deepSuggestionService;
    private final RecognitionHistoryRepository historyRepository;
    private final CandidateSessionCache sessionCache;
    private final AsyncRecognitionTaskManager taskManager;
    private final ExecutorService searchExecutor;
    private final PerformanceMetricsService metricsService;
    private final SafeActionExecutor safeActionExecutor;
    private final ActionCompiler actionCompiler;
    private final NlpConversationManager conversationManager;
    private final SessionContextService sessionContextService;
    private final SuggestionCardCache suggestionCardCache;
    private final CandidateFilterService candidateFilterService;

    public SuggestionController(SuggestionService suggestionService,
                                DeepSuggestionService deepSuggestionService,
                                RecognitionHistoryRepository historyRepository,
                                CandidateSessionCache sessionCache,
                                AsyncRecognitionTaskManager taskManager,
                                PerformanceMetricsService metricsService,
                                SafeActionExecutor safeActionExecutor,
                                ActionCompiler actionCompiler,
                                NlpConversationManager conversationManager,
                                SessionContextService sessionContextService,
                                SuggestionCardCache suggestionCardCache,
                                CandidateFilterService candidateFilterService,
                                @org.springframework.beans.factory.annotation.Qualifier("searchExecutor") ExecutorService searchExecutor) {
        this.suggestionService = suggestionService;
        this.deepSuggestionService = deepSuggestionService;
        this.historyRepository = historyRepository;
        this.sessionCache = sessionCache;
        this.taskManager = taskManager;
        this.metricsService = metricsService;
        this.safeActionExecutor = safeActionExecutor;
        this.actionCompiler = actionCompiler;
        this.conversationManager = conversationManager;
        this.sessionContextService = sessionContextService;
        this.suggestionCardCache = suggestionCardCache;
        this.candidateFilterService = candidateFilterService;
        this.searchExecutor = searchExecutor;
    }

    /**
     * 统一 session 归属校验：taskManager 或 history 表任一命中即放行。
     */
    private boolean ownsSession(String sessionId, Long userId) {
        return taskManager.belongsToUser(sessionId, userId)
                || historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
    }

    @GetMapping("/cards")
    public ApiResponse<Map<String, Object>> cards(@RequestParam(defaultValue = "app") String clientType,
                                                                @RequestParam(name = "client_type", required = false) String snakeClientType,
                                                                @RequestParam(name = "session_id", required = false) String sessionId) {
        String resolvedClientType = snakeClientType != null ? snakeClientType : clientType;
        if (sessionId == null || sessionId.isBlank()) {
            return ApiResponse.ok(cardsResponse(List.of(), "READY"));
        }
        Long userId = SecurityUtils.currentUserId();
        if (!ownsSession(sessionId, userId)) {
            return ApiResponse.fail(403, "无权访问该识别任务");
        }

        List<SuggestionCard> cachedCards = suggestionCardCache.get(sessionId);
        boolean hasInsight = cachedCards.stream().anyMatch(c -> c.id() != null && c.id().startsWith("insight_"));

        // 如果缓存里既有普通卡又有 insight 卡，直接返回
        if (!cachedCards.isEmpty() && hasInsight) {
            return ApiResponse.ok(cardsResponse(cachedCards, "READY"));
        }

        List<com.visioncart.api.dto.ProductCard> candidates = sessionCache.getCandidates(sessionId);

        // 缓存里只有普通卡没有 insight：尝试补生成 insight
        List<SuggestionCard> baseCards = cachedCards.isEmpty()
                ? suggestionService.cards(resolvedClientType, candidates)
                : cachedCards;

        // 合并 AI 导购分析卡（限时 800ms，超时返回 PENDING 让客户端稍后重试）
        java.util.concurrent.atomic.AtomicBoolean aiFailed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean aiPending = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            CompletableFuture<List<SuggestionCard>> insightFuture = CompletableFuture.supplyAsync(
                    () -> deepSuggestionService.insightCards(resolvedClientType, candidates, Map.of(), null),
                    searchExecutor);
            List<SuggestionCard> baseCardsSnapshot = List.copyOf(baseCards);
            insightFuture.thenAccept(insights -> {
                if (insights != null && !insights.isEmpty()) {
                    List<SuggestionCard> merged = new ArrayList<>(baseCardsSnapshot);
                    merged.addAll(insights);
                    suggestionCardCache.save(sessionId, merged);
                }
            }).exceptionally(e -> {
                log.debug("Deep suggestion background completion failed: {}", e.getMessage());
                return null;
            });
            List<SuggestionCard> insights = insightFuture.get(INSIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (insights != null && !insights.isEmpty()) {
                List<SuggestionCard> merged = new ArrayList<>(baseCards);
                merged.addAll(insights);
                baseCards = merged;
            }
        } catch (TimeoutException e) {
            log.debug("Deep suggestion still pending after {}ms", INSIGHT_TIMEOUT_MS);
            metricsService.recordDeepSuggestionTimeout();
            aiPending.set(true);
        } catch (Exception e) {
            log.debug("Deep suggestion generation failed (non-critical): {}", e.getMessage());
            aiFailed.set(true);
        }

        boolean finalHasInsight = baseCards.stream().anyMatch(c -> c.id() != null && c.id().startsWith("insight_"));
        String insightStatus;
        if (finalHasInsight) {
            insightStatus = "READY";
        } else if (aiPending.get()) {
            insightStatus = "PENDING";
        } else if (aiFailed.get()) {
            insightStatus = "FAILED";
            baseCards = addGeneralInsightCard(baseCards);
        } else {
            // AI returned no insights and didn't fail — analysis complete but no strong facts
            insightStatus = "EMPTY";
            baseCards = addGeneralInsightCard(baseCards);
        }
        suggestionCardCache.save(sessionId, baseCards);
        return ApiResponse.ok(cardsResponse(baseCards, insightStatus));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cardsResponse(List<SuggestionCard> cards, String insightStatus) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("cards", cards);
        map.put("insight_status", insightStatus);
        return map;
    }

    /**
     * Add a general insight card when AI analysis completes but finds no strong facts.
     * Ensures the user always sees an AI guidance card instead of a blank space.
     */
    private List<SuggestionCard> addGeneralInsightCard(List<SuggestionCard> cards) {
        if (cards.stream().anyMatch(c -> "insight_general".equals(c.id()))) {
            return cards;
        }
        SuggestionCard generalInsight = new SuggestionCard(
                "insight_general",
                "AI 导购分析",
                "我已根据价格、平台和商品类型整理了结果。你可以继续输入「便宜点」「只看主商品」「适合送礼」等条件继续筛选。",
                null,   // icon
                null,   // action — no filter action
                0,      // priority
                null,   // badge
                null,   // reason
                null,   // metric
                "AI 分析", // actionLabel
                null,   // tone
                null    // actionType
        );
        List<SuggestionCard> merged = new ArrayList<>(cards);
        merged.add(generalInsight);
        return merged;
    }

    /**
     * @deprecated Use {@link #executeAction} (POST /action) for unified ActionResult.
     * This endpoint forwards to the unified action path internally.
     */
    @Deprecated(since = "2026-06", forRemoval = true)
    @PostMapping("/execute")
    public ApiResponse<SuggestionExecuteResult> execute(@Valid @RequestBody SuggestionExecuteRequest request) {
        // Forward to unified action endpoint, then wrap result for backward compatibility
        ApiResponse<ActionResult> actionResponse = executeAction(request);
        if (actionResponse.code() != 200 || actionResponse.data() == null) {
            return ApiResponse.fail(actionResponse.code(), actionResponse.message());
        }
        ActionResult ar = actionResponse.data();
        SuggestionExecuteResult legacyResult = new SuggestionExecuteResult(
                ar.products(), ar.appliedFilter(), ar.suggestionCards(),
                ar.message(), ar.canUndo(), ar.filterApplied()
        );
        return ApiResponse.ok(legacyResult);
    }

    /**
     * Unified suggestion action endpoint — returns ActionResult.
     * All suggestion actions (filter, sort, flow) go through SafeActionExecutor.
     * Replaces /execute for new clients; old /execute kept for backward compatibility.
     */
    @PostMapping("/action")
    public ApiResponse<ActionResult> executeAction(@Valid @RequestBody SuggestionExecuteRequest request) {
        Long userId = SecurityUtils.currentUserId();
        if (request.sessionId() != null && !request.sessionId().isBlank()
                && !ownsSession(request.sessionId(), userId)) {
            return ApiResponse.fail(403, "无权访问该识别任务");
        }

        String sessionId = request.sessionId();
        String action = request.action();
        List<com.visioncart.api.dto.ProductCard> candidates = safeSessionProducts(sessionId, request.currentProducts());
        SearchFilter currentFilter = request.currentFilter() != null ? request.currentFilter() : SearchFilter.empty();
        // P2-9: Resolve category from server-side context
        String clientCategory = request.context() != null ? request.context().category() : null;
        String category = sessionContextService.resolve(sessionId, userId, clientCategory).normalizedCategory();

        // Build UserAction
        UserAction userAction = UserAction.suggestion(sessionId, action, action);

        // Compile action to FilterClauses
        List<com.visioncart.service.filter.FilterClause> clauses = safeActionExecutor.compileSuggestion(action, currentFilter);

        // Build tentative filter (action applied)
        SearchFilter tentativeFilter = actionCompiler.applyActionToFilter(action, currentFilter);

        // previousProducts = candidates filtered by current filter (what user is currently seeing)
        List<com.visioncart.api.dto.ProductCard> previousProducts = candidateFilterService
                .filter(candidates, currentFilter, Map.of(), 100, 1).products();

        // Execute through unified SafeActionExecutor
        ActionResult result = safeActionExecutor.executeAction(
                userAction, clauses, candidates,
                previousProducts,
                currentFilter,  // previousFilter
                tentativeFilter,
                currentFilter,  // baseFilter = current filter for suggestions
                category
        );

        // Fill suggestion cards if filter was applied
        if (result.filterApplied() && result.suggestionCards() == null) {
            List<SuggestionCard> newCards = suggestionService.cards("app", result.products(), Map.of(),
                    result.appliedFilter());
            result = new ActionResult(
                    result.products(), result.appliedFilter(), result.filterTags(),
                    result.filterApplied(), result.keptPreviousResults(), result.canUndo(),
                    result.message(), result.warnings(), result.explanations(),
                    result.uiAction(), result.totalInPool(),
                    newCards,  // suggestionCards
                    result.updatedAttributes(), result.actionSource(), result.undoToken(),
                    result.fallbackProducts(), result.displayMode(),
                    result.messageCode(), result.attributesUpdated(), result.productsUpdated()
            );
        }

        log.info("Unified suggestion action: session={}, action='{}', products={}, committed={}, source={}",
                sessionId, action, result.products().size(), result.filterApplied(), result.actionSource());

        return ApiResponse.ok(result);
    }

    @PostMapping("/undo")
    public ApiResponse<SuggestionExecuteResult> undo(@RequestParam String sessionId,
                                                     @RequestBody(required = false) List<com.visioncart.api.dto.ProductCard> currentProducts) {
        Long userId = SecurityUtils.currentUserId();
        if (sessionId != null && !sessionId.isBlank() && !ownsSession(sessionId, userId)) {
            return ApiResponse.fail(403, "无权访问该识别任务");
        }
        List<com.visioncart.api.dto.ProductCard> products = safeSessionProducts(sessionId, currentProducts);
        return ApiResponse.ok(suggestionService.undo(sessionId, products));
    }

    /**
     * 优先使用 sessionCache 中的完整候选池（服务端可信数据），
     * 客户端传入的 currentProducts 仅在 cache 为空时作为 fallback，且限制最大 100 条。
     */
    private List<com.visioncart.api.dto.ProductCard> safeSessionProducts(String sessionId,
                                                                          List<com.visioncart.api.dto.ProductCard> clientProducts) {
        if (sessionId != null && !sessionId.isBlank()) {
            List<com.visioncart.api.dto.ProductCard> cached = sessionCache.getCandidates(sessionId);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        // Fallback: 客户端数据，限制 100 条防止滥用
        if (clientProducts == null) return List.of();
        return clientProducts.stream().limit(100).toList();
    }
}
