package com.visioncart.service.action;

import com.visioncart.api.dto.*;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.filter.ActionCompiler;
import com.visioncart.service.filter.FilterClause;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.filter.semantic.SemanticActionExecutor;
import com.visioncart.service.nlp.LlmSemanticPlanner;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.nlp.NlpStateStackService;
import com.visioncart.service.recognition.AttributeCorrectionService;
import com.visioncart.service.recognition.SessionHistoryService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.nlp.SemanticExecutionPolicy;
import com.visioncart.service.search.ProductReputationService;
import com.visioncart.service.search.ProductSortService;
import com.visioncart.service.search.SearchRunService;
import com.visioncart.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single backend entry point for user actions.
 * Legacy controllers keep their old routes, but new clients should call this service
 * through /api/v1/actions/execute and consume the unified ActionResult shape.
 */
@Service
public class ActionExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AttributeCorrectionService attributeCorrectionService;
    private final SafeActionExecutor safeActionExecutor;
    private final ActionCompiler actionCompiler;
    private final CandidateSessionCache sessionCache;
    private final CandidateFilterService filterService;
    private final NlpConversationManager conversationManager;
    private final NlpUndoService undoService;
    private final SuggestionService suggestionService;
    private final SessionContextService sessionContextService;
    private final SessionHistoryService sessionHistoryService;
    private final LlmSemanticPlanner llmSemanticPlanner;
    private final SemanticActionExecutor semanticActionExecutor;
    private final ProductSortService productSortService;
    private final ProductReputationService reputationService;
    private final SemanticExecutionPolicy semanticExecutionPolicy;
    private final SearchRunService searchRunService;
    private final NlpStateStackService nlpStateStackService;

    public ActionExecutionService(AttributeCorrectionService attributeCorrectionService,
                                  SafeActionExecutor safeActionExecutor,
                                  ActionCompiler actionCompiler,
                                  CandidateSessionCache sessionCache,
                                  CandidateFilterService filterService,
                                  NlpConversationManager conversationManager,
                                  NlpUndoService undoService,
                                  SuggestionService suggestionService,
                                  SessionContextService sessionContextService,
                                  SessionHistoryService sessionHistoryService,
                                  LlmSemanticPlanner llmSemanticPlanner,
                                  SemanticActionExecutor semanticActionExecutor,
                                  ProductSortService productSortService,
                                  ProductReputationService reputationService,
                                  SemanticExecutionPolicy semanticExecutionPolicy,
                                  SearchRunService searchRunService,
                                  NlpStateStackService nlpStateStackService) {
        this.attributeCorrectionService = attributeCorrectionService;
        this.safeActionExecutor = safeActionExecutor;
        this.actionCompiler = actionCompiler;
        this.sessionCache = sessionCache;
        this.filterService = filterService;
        this.conversationManager = conversationManager;
        this.undoService = undoService;
        this.suggestionService = suggestionService;
        this.sessionContextService = sessionContextService;
        this.sessionHistoryService = sessionHistoryService;
        this.llmSemanticPlanner = llmSemanticPlanner;
        this.semanticActionExecutor = semanticActionExecutor;
        this.productSortService = productSortService;
        this.reputationService = reputationService;
        this.semanticExecutionPolicy = semanticExecutionPolicy;
        this.searchRunService = searchRunService;
        this.nlpStateStackService = nlpStateStackService;
    }

    public ActionResult execute(UserAction action, Long userId) {
        if (action == null || isBlank(action.source())) {
            throw new IllegalArgumentException("操作类型不能为空");
        }
        return switch (action.source()) {
            case "nlp" -> executeNlp(action, userId);
            case "suggestion", "sort" -> executeCompiledAction(action, userId);
            case "manual_filter" -> executeManualFilter(action, userId);
            case "correction" -> executeCorrection(action, userId);
            case "tag_delete" -> executeTagDelete(action);
            case "clear_filter" -> executeClearFilter(action);
            default -> throw new IllegalArgumentException("不支持的操作类型: " + action.source());
        };
    }

    private ActionResult executeNlp(UserAction action, Long userId) {
        String sessionId = action.sessionId();
        String userInput = action.rawText();

        if (nlpStateStackService.limitReached(sessionId)) {
            SearchFilter currentFilter = conversationManager.getFilterState(sessionId);
            List<ProductCard> currentCandidates = currentProducts(sessionId);
            java.util.Optional<NlpStateStackService.NlpState> currentNlpState = peekNlpState(sessionId);
            List<ProductCard> currentDisplay = currentNlpState
                    .map(NlpStateStackService.NlpState::products)
                    .filter(products -> products != null && !products.isEmpty())
                    .orElseGet(() -> filterProducts(currentCandidates, currentFilter));
            List<FilterTag> currentTags = currentNlpState
                    .map(NlpStateStackService.NlpState::tags)
                    .filter(tags -> tags != null && !tags.isEmpty())
                    .orElseGet(() -> generateStructuredTags(currentFilter));
            return withSuggestionCards(new ActionResult(
                    currentDisplay,
                    currentFilter,
                    currentTags,
                    false,
                    true,
                    undoService.canUndo(sessionId),
                    "已达到本次识别的筛选次数上限，可回退上一步或清空筛选",
                    List.of(),
                    List.of(),
                    null,
                    currentCandidates.size(),
                    null,
                    null,
                    "nlp",
                    null,
                    null,
                    "KEPT_PREVIOUS",
                    "nlp.limit_reached",
                    null,
                    null));
        }

        // 1. Resolve session context
        UserAction.ActionPayload payload = action.payload();
        Map<String, String> contextMap = payload != null && payload.context() != null
                ? payload.context() : Map.of();
        String clientCategory = contextMap.get("category");
        // Use real userId so session history lookup can find recognition records
        SessionContextService.SessionContext ctx = sessionContextService.resolve(sessionId, userId, clientCategory);

        // 2. Get current candidates (优先 classifiedPool Top300) and previous filter state
        List<ProductCard> candidates = sessionCache.getBestCandidates(sessionId);
        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        java.util.Optional<NlpStateStackService.NlpState> previousNlpState = peekNlpState(sessionId);
        List<ProductCard> previousProducts = previousNlpState
                .map(NlpStateStackService.NlpState::products)
                .filter(products -> products != null && !products.isEmpty())
                .orElseGet(() -> filterService.filter(
                        candidates, previousFilter, Map.of(), DEFAULT_PAGE_SIZE, 1).products());

        // 3. Generate semantic plan via LLM
        LlmSemanticPlanner.ProductPoolSummary poolSummary = LlmSemanticPlanner.ProductPoolSummary.from(candidates);
        String historyText = nlpStateStackService.historyText(sessionId);
        SemanticActionPlan rawPlan = llmSemanticPlanner.plan(userInput, ctx, poolSummary, historyText);

        // 4. Apply execution policy: prevent LLM Judge on large pools for subjective preferences
        SemanticActionPlan plan = semanticExecutionPolicy.normalizeForSyncExecution(
                userInput, rawPlan, previousProducts.size());
        log.info("NLP semantic plan: session={}, mode={}, hardFilters={}, preferences={}, semanticFilters={}, historyPresent={}",
                sessionId,
                plan.executionMode(),
                plan.hardFilters() == null ? 0 : plan.hardFilters().size(),
                plan.preferences() == null ? 0 : plan.preferences().size(),
                plan.semanticFilters() == null ? 0 : plan.semanticFilters().size(),
                historyText != null && !historyText.isBlank());

        // 5. Rerank-only plans operate on the current display page, not the full candidate pool
        List<ProductCard> executionPool = isRerankOnly(plan) ? previousProducts : candidates;

        // Each successful NLP turn is a complete new state. History is sent to the LLM,
        // and only conditions present in the returned plan should be committed.
        SearchFilter executionFilterBase = SearchFilter.empty();
        ActionResult result = semanticActionExecutor.execute(
                sessionId, executionPool, executionFilterBase, plan, previousProducts);

        // 5. Save undo point if filter, product order, or tags changed
        List<ProductCard> resultDisplayProducts = displayProducts(result);
        List<FilterTag> resultTags = safeFilterTags(result.filterTags());
        List<FilterTag> previousTags = previousNlpState
                .map(NlpStateStackService.NlpState::tags)
                .filter(tags -> tags != null && !tags.isEmpty())
                .orElseGet(() -> generateStructuredTags(previousFilter));
        boolean filterChanged = result.appliedFilter() != null
                && !java.util.Objects.equals(previousFilter, result.appliedFilter());
        boolean productsChanged = !sameProductIds(previousProducts, resultDisplayProducts);
        boolean tagsChanged = !sameFilterTags(previousTags, resultTags);

        if (result.filterApplied() && result.canUndo() && (filterChanged || productsChanged || tagsChanged)) {
            // 保存 undo point 时同时保存 classifiedPool 快照
            com.visioncart.service.search.SearchCandidatePool currentPool =
                    sessionCache.getClassifiedPool(sessionId).orElse(null);
            undoService.saveUndoPoint(sessionId, previousFilter, previousProducts, userInput, "nlp", null,
                    null, currentPool);
            nlpStateStackService.push(
                    sessionId,
                    action.actionId(),
                    userInput,
                    result.appliedFilter() != null ? result.appliedFilter() : previousFilter,
                    !resultTags.isEmpty() ? resultTags : generateStructuredTags(result.appliedFilter()),
                    resultDisplayProducts);
        }

        return withSuggestionCards(result);
    }

    private ActionResult executeCompiledAction(UserAction action, Long userId) {
        String actionString = actionString(action);
        if (isBlank(actionString)) {
            return ActionResult.passThrough(currentProducts(action.sessionId()));
        }

        SearchFilter currentFilter = conversationManager.getFilterState(action.sessionId());
        List<ProductCard> candidates = currentProducts(action.sessionId());
        List<ProductCard> previousProducts = filterProducts(candidates, currentFilter);
        List<FilterClause> clauses = safeActionExecutor.compileSuggestion(actionString, currentFilter);
        SearchFilter tentativeFilter = actionCompiler.applyActionToFilter(actionString, currentFilter);
        String category = sessionContextService.resolve(action.sessionId(), userId).normalizedCategory();

        ActionResult result = safeActionExecutor.executeAction(
                action,
                clauses,
                candidates,
                previousProducts,
                currentFilter,
                tentativeFilter,
                currentFilter,
                category
        );

        // Reputation sort must use the SearchRun classified pool even when SafeActionExecutor
        // already committed a rerank clause; otherwise it only reorders the current candidate list.
        if (isSortAction(actionString) && isReputationSort(tentativeFilter) && action.sessionId() != null) {
            conversationManager.setFilterState(action.sessionId(), tentativeFilter);
            java.util.Optional<SearchResult> resortResult =
                    searchRunService.recomputeDisplayPage(action.sessionId(), tentativeFilter, DEFAULT_PAGE_SIZE);
            if (resortResult.isPresent()) {
                logReputationSortPath("search_run", action.sessionId(), actionString, tentativeFilter,
                        resortResult.get().products().size());
                result = ActionResult.filtered(
                        resortResult.get().products(), tentativeFilter, result.filterTags(),
                        result.canUndo(), result.message(), result.warnings(), result.explanations());
            }
        }

        // Sort/highlight actions compile to empty clauses → SafeActionExecutor returns passThrough
        // with null appliedFilter, which skips conversationManager.setFilterState().
        // 关键改动：如果 classifiedPool 存在，基于 Top300 重排序（而非只排当前 Top50）
        if (result.appliedFilter() == null && isSortAction(actionString) && action.sessionId() != null) {
            conversationManager.setFilterState(action.sessionId(), tentativeFilter);

            // 优先使用 classifiedPool 重排序
            java.util.Optional<SearchResult> resortResult =
                    searchRunService.recomputeDisplayPage(action.sessionId(), tentativeFilter, DEFAULT_PAGE_SIZE);
            if (resortResult.isPresent()) {
                logReputationSortPath("search_run", action.sessionId(), actionString, tentativeFilter,
                        resortResult.get().products().size());
                result = ActionResult.filtered(
                        resortResult.get().products(), tentativeFilter, result.filterTags(),
                        result.canUndo(), result.message(), result.warnings(), result.explanations());
            } else {
                // fallback：旧逻辑，只在当前 Top50 内排序
                String sortKey = sortKeyFromAction(actionString);
                List<ProductCard> sorted = sortKey != null
                        ? productSortService.sortByKey(new java.util.ArrayList<>(result.products()), sortKey)
                        : result.products();
                if ("review_quality".equals(sortKey) || "rating_desc".equals(sortKey)) {
                    sorted = enrichRatingLabels(sorted);
                }
                logReputationSortPath("legacy_candidates", action.sessionId(), actionString, tentativeFilter, sorted.size());
                result = ActionResult.filtered(
                        sorted, tentativeFilter, result.filterTags(),
                        result.canUndo(), result.message(), result.warnings(), result.explanations());
            }
        }

        archiveIfNeeded(action.sessionId(), result.products());
        return withSuggestionCards(result);
    }

    private static boolean isSortAction(String action) {
        return action.startsWith("sort_") || "sort_relevance".equals(action)
                || "highlight_best_value".equals(action);
    }

    /** Rerank-only plans (PREFERENCE_RERANK, LLM_RERANK) operate on current display page only. */
    private static boolean isRerankOnly(SemanticActionPlan plan) {
        return plan != null && (
                "PREFERENCE_RERANK".equals(plan.executionMode())
                        || "LLM_RERANK".equals(plan.executionMode()));
    }

    /**
     * Map action string to ProductSortService sort key.
     * sort_by_review_quality → review_quality, sort_by_rating_desc → rating_desc, etc.
     */
    private static String sortKeyFromAction(String action) {
        if (action == null) return null;
        return switch (action) {
            case "sort_relevance" -> "relevance";
            case "sort_by_price_asc" -> "price_asc";
            case "sort_by_sales_desc" -> "sales_desc";
            case "sort_by_rating_desc", "sort_by_review_quality" -> "review_quality";
            case "highlight_best_value" -> "value_score";
            default -> {
                // strip "sort_by_" prefix if present
                String key = action.startsWith("sort_by_") ? action.substring("sort_by_".length()) : action;
                yield key;
            }
        };
    }

    private void logReputationSortPath(String path, String sessionId, String actionString,
                                       SearchFilter filter, int products) {
        if (!isReputationSort(filter)) {
            return;
        }
        log.info("Action reputation sort path={}, sessionId={}, action={}, sortBy={}, products={}",
                path, sessionId, actionString, filter.sortBy(), products);
    }

    private static boolean isReputationSort(SearchFilter filter) {
        if (filter == null || filter.sortBy() == null) {
            return false;
        }
        return switch (filter.sortBy()) {
            case "rating", "reviews", "review_quality", "rating_desc", "shop_trust", "seller_trust" -> true;
            default -> false;
        };
    }

    /** Enrich products with shop/seller reputation labels from ProductReputationService. */
    private List<ProductCard> enrichRatingLabels(List<ProductCard> products) {
        if (products == null || products.isEmpty()) return products;
        return reputationService.attachShopTrustReputation(products);
    }

    private ActionResult executeManualFilter(UserAction action, Long userId) {
        String actionString = manualActionString(action.payload());
        if (!isBlank(actionString)) {
            UserAction normalized = new UserAction(
                    action.actionId(),
                    "manual_filter",
                    action.sessionId(),
                    action.rawText(),
                    new UserAction.ActionPayload(actionString, field(action.payload()), value(action.payload()),
                            null, null, null, action.payload() != null ? action.payload().context() : null,
                            action.payload() != null ? action.payload().filterSpec() : null),
                    action.clientRequestId()
            );
            return executeCompiledAction(normalized, userId);
        }

        SearchFilter currentFilter = conversationManager.getFilterState(action.sessionId());
        List<ProductCard> candidates = currentProducts(action.sessionId());
        List<ProductCard> previousProducts = filterProducts(candidates, currentFilter);
        SearchFilter tentativeFilter = applyManualFilter(currentFilter, action.payload());
        List<FilterClause> clauses = safeActionExecutor.compileCorrection(
                field(action.payload()),
                value(action.payload())
        );
        String category = sessionContextService.resolve(action.sessionId(), userId).normalizedCategory();
        ActionResult result = safeActionExecutor.executeAction(
                action, clauses, candidates, previousProducts, currentFilter,
                tentativeFilter, currentFilter, category);
        archiveIfNeeded(action.sessionId(), result.products());
        return withSuggestionCards(result);
    }

    private ActionResult executeCorrection(UserAction action, Long userId) {
        UserAction.ActionPayload payload = action.payload();
        if (payload == null || isBlank(payload.field()) || isBlank(payload.value())) {
            throw new IllegalArgumentException("修正操作需要指定字段和值");
        }

        String sessionId = action.sessionId();

        // Capture user's filter BEFORE correction (attribute correction must NOT modify filter state)
        SearchFilter userFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> previousCandidatePool = currentProducts(sessionId);
        List<ProductCard> previousProducts = filterProducts(previousCandidatePool, userFilter);

        ApiResponse<AttributeCorrectionResult> body = attributeCorrectionService.correctAttribute(
                sessionId,
                new AttributeCorrectionRequest(sessionId, payload.field(), null, payload.value()),
                userId);
        if (body == null || body.code() != 200 || body.data() == null) {
            throw new IllegalArgumentException(body != null ? body.message() : "修正操作失败");
        }

        AttributeCorrectionResult correction = body.data();

        // Determine if products were actually updated — trust the correction service's explicit signal.
        // Do NOT use sameProductIds() to reverse-engineer, because on search failure the correction
        // service returns sessionCache candidates which may differ from the filtered display list,
        // causing a false positive productsUpdated=true.
        List<ProductCard> correctionProducts = correction.products() != null
                ? safeProducts(correction.products().products()) : List.of();
        boolean productsUpdated = Boolean.TRUE.equals(correction.productsUpdated())
                && !Boolean.TRUE.equals(correction.keptPreviousResults());

        Map<String, Object> updatedAttributes = new HashMap<>();
        if (correction.updatedAttributes() != null) {
            correction.updatedAttributes().forEach(updatedAttributes::put);
        }
        boolean attributesUpdated = !updatedAttributes.isEmpty();
        boolean filterCorrection = productsUpdated && !attributesUpdated && correction.previousAttributes() == null;

        // Products: use correction results only if productsUpdated; otherwise keep previous
        List<ProductCard> products = productsUpdated ? correctionProducts : previousProducts;
        boolean keptPrevious = !productsUpdated && correction.correctionApplied();

        // Recall-refresh corrections need an attribute snapshot undo point here.
        // Local filter corrections are committed through SafeActionExecutor, which
        // already saved the filter undo point.
        if (correction.correctionApplied() && attributesUpdated) {
            // Convert AttributeValue map to Object map for undo storage
            Map<String, Object> prevAttrs = new HashMap<>();
            if (correction.previousAttributes() != null) {
                correction.previousAttributes().forEach(prevAttrs::put);
            }
            undoService.saveUndoPoint(sessionId, userFilter, previousCandidatePool,
                    action.rawText(), "correction", action.actionId(), prevAttrs);
        }

        SearchFilter resultFilter = filterCorrection
                ? conversationManager.getFilterState(sessionId)
                : userFilter;
        List<FilterTag> tags = generateStructuredTags(resultFilter);

        // Resolve messageCode
        String messageCode = correction.messageCode();
        if (messageCode == null) {
            if (filterCorrection) {
                messageCode = "filter.applied";
            } else if (keptPrevious) {
                messageCode = correctionProducts.isEmpty()
                        ? "attribute.updated.search.empty"
                        : "attribute.updated.products.kept";
            } else if (productsUpdated) {
                messageCode = "attribute.updated.products.refreshed";
            } else {
                messageCode = "attribute.updated.only";
            }
        }

        ActionResult result = new ActionResult(
                products,
                resultFilter,
                tags,
                filterCorrection,
                keptPrevious,
                undoService.canUndo(sessionId),
                correction.message(),
                List.of(),
                List.of(),
                null,
                currentProducts(sessionId).size(),
                null,
                attributesUpdated ? updatedAttributes : null,
                filterCorrection ? "filter_correction" : "correction",
                sessionId + ":correction:" + System.currentTimeMillis(),
                null,
                keptPrevious ? "KEPT_PREVIOUS" : "NORMAL",
                messageCode,
                attributesUpdated,
                productsUpdated                    // productsUpdated
        );
        archiveIfNeeded(sessionId, result.products());
        return withSuggestionCards(result);
    }

    private ActionResult executeTagDelete(UserAction action) {
        String sessionId = action.sessionId();
        UserAction.ActionPayload payload = action.payload();
        String filterPath = payload != null && !isBlank(payload.filterPath())
                ? payload.filterPath()
                : payload != null ? payload.tagId() : null;
        if (isBlank(filterPath)) {
            throw new IllegalArgumentException("删除标签需要指定 filterPath 或 tagId");
        }

        // P0-2: Action-only tags (preferences/exclusions/semantic) cannot be removed
        // by mutating filter state — they were applied as side effects of the NLP action.
        // Route to undo instead.
        if (isActionOnlyTag(filterPath)) {
            NlpUndoService.UndoResult undoResult = undoService.undo(sessionId);
            if (undoResult == null) {
                List<ProductCard> currentProds = currentProducts(sessionId);
                return ActionResult.passThrough(currentProds);
            }
            java.util.Optional<NlpStateStackService.NlpState> restoredNlpState = java.util.Optional.empty();
            if ("nlp".equals(undoResult.undoneSource())) {
                nlpStateStackService.pop(sessionId);
                restoredNlpState = peekNlpState(sessionId);
            }
            SearchFilter restoredFilter = undoResult.filter() != null
                    ? undoResult.filter() : SearchFilter.empty();
            restoredFilter = restoredNlpState
                    .map(NlpStateStackService.NlpState::filter)
                    .orElse(restoredFilter);
            conversationManager.setFilterState(sessionId, restoredFilter);
            List<ProductCard> restoredSnapshot = safeProducts(undoResult.products());
            List<ProductCard> candidates = restoredSnapshot.isEmpty()
                    ? currentProducts(sessionId)
                    : restoredSnapshot;
            List<ProductCard> restoredProducts = restoredNlpState
                    .map(NlpStateStackService.NlpState::products)
                    .filter(products -> products != null && !products.isEmpty())
                    .orElse(restoredSnapshot);
            if (restoredProducts.isEmpty()) {
                CandidateFilterService.FilterResult filterResult = filterService.filter(
                        candidates, restoredFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
                restoredProducts = safeProducts(filterResult.products());
            }
            SearchFilter finalRestoredFilter = restoredFilter;
            List<FilterTag> restoredTags = restoredNlpState
                    .map(NlpStateStackService.NlpState::tags)
                    .filter(tags -> tags != null && !tags.isEmpty())
                    .orElseGet(() -> generateStructuredTags(finalRestoredFilter));
            archiveIfNeeded(sessionId, restoredProducts);

            return withSuggestionCards(new ActionResult(
                    restoredProducts,
                    restoredFilter,
                    restoredTags,
                    true,
                    false,
                    undoService.canUndo(sessionId),
                    "已撤回筛选「" + undoResult.undoneQuery() + "」",
                    List.of(),
                    List.of(),
                    null,
                    candidates.size(),
                    null,
                    null,
                    "tag_delete",
                    sessionId + ":tag_delete:" + System.currentTimeMillis(),
                    null,
                    "NORMAL",
                    "filter.removed",
                    null,
                    null
            ));
        }

        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> candidates = currentProducts(sessionId);
        List<ProductCard> previousProducts = filterProducts(candidates, previousFilter);
        // 保存 undo point 时同时保存 classifiedPool 快照
        com.visioncart.service.search.SearchCandidatePool currentPool =
                sessionCache.getClassifiedPool(sessionId).orElse(null);
        undoService.saveUndoPoint(sessionId, previousFilter, previousProducts,
                action.rawText(), "tag_delete", action.actionId(), null, currentPool);

        SearchFilter updatedFilter = conversationManager.removeFilterField(sessionId, filterPath);
        java.util.Optional<SearchResult> runResult =
                searchRunService.recomputeDisplayPage(sessionId, updatedFilter, DEFAULT_PAGE_SIZE);
        List<ProductCard> products = runResult.map(SearchResult::products).orElseGet(() -> {
            CandidateFilterService.FilterResult filterResult = filterService.filter(
                    candidates, updatedFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
            return filterResult.products();
        });
        int totalInPool = runResult.isPresent()
                ? Math.toIntExact(runResult.get().total())
                : candidates.size();
        archiveIfNeeded(sessionId, products);

        return withSuggestionCards(new ActionResult(
                products,
                updatedFilter,
                generateStructuredTags(updatedFilter),
                true,
                false,
                undoService.canUndo(sessionId),
                "已删除筛选标签",
                List.of(),
                List.of(),
                null,
                totalInPool,
                null,
                null,
                "tag_delete",
                sessionId + ":tag_delete:" + System.currentTimeMillis(),
                null,
                "NORMAL",
                "filter.removed",
                null,
                null
        ));
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

    private ActionResult executeClearFilter(UserAction action) {
        String sessionId = action.sessionId();
        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> candidates = currentProducts(sessionId);
        List<ProductCard> previousProducts = filterProducts(candidates, previousFilter);
        // 保存 undo point 时同时保存 classifiedPool 快照
        com.visioncart.service.search.SearchCandidatePool currentPool =
                sessionCache.getClassifiedPool(sessionId).orElse(null);
        undoService.saveUndoPoint(sessionId, previousFilter, previousProducts,
                action.rawText(), "clear_filter", action.actionId(), null, currentPool);

        SearchFilter emptyFilter = SearchFilter.empty();
        conversationManager.setFilterState(sessionId, emptyFilter);
        conversationManager.clear(sessionId);
        nlpStateStackService.clear(sessionId);
        undoService.clearUndoStack(sessionId);
        java.util.Optional<SearchResult> runResult =
                searchRunService.recomputeDisplayPage(sessionId, emptyFilter, DEFAULT_PAGE_SIZE);
        List<ProductCard> products = runResult.map(SearchResult::products).orElseGet(() -> {
            CandidateFilterService.FilterResult filterResult = filterService.filter(
                    candidates, emptyFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
            return filterResult.products();
        });
        int totalInPool = runResult.isPresent()
                ? Math.toIntExact(runResult.get().total())
                : candidates.size();
        archiveIfNeeded(sessionId, products);

        return withSuggestionCards(new ActionResult(
                products,
                emptyFilter,
                List.of(),
                true,
                false,
                undoService.canUndo(sessionId),
                "已清空筛选条件",
                List.of(),
                List.of(),
                null,
                totalInPool,
                null,
                null,
                "clear_filter",
                sessionId + ":clear_filter:" + System.currentTimeMillis(),
                null,
                "NORMAL",
                "filter.cleared",
                null,
                null
        ));
    }

    private ActionResult withSuggestionCards(ActionResult result) {
        if (result == null || !result.filterApplied() || result.suggestionCards() != null) {
            return result;
        }
        List<ProductCard> products = safeProducts(result.products());
        List<SuggestionCard> cards = suggestionService.cards("app", products, Map.of(), result.appliedFilter());
        return new ActionResult(
                result.products(),
                result.appliedFilter(),
                result.filterTags(),
                result.filterApplied(),
                result.keptPreviousResults(),
                result.canUndo(),
                result.message(),
                result.warnings(),
                result.explanations(),
                result.uiAction(),
                result.totalInPool(),
                cards,
                result.updatedAttributes(),
                result.actionSource(),
                result.undoToken(),
                result.fallbackProducts(),
                result.displayMode(),
                result.messageCode(),
                result.attributesUpdated(),
                result.productsUpdated()
        );
    }

    private void archiveIfNeeded(String sessionId, List<ProductCard> products) {
        if (!isBlank(sessionId) && products != null && !products.isEmpty()) {
            sessionHistoryService.archiveDisplayedProducts(sessionId, products);
        }
    }

    private List<ProductCard> currentProducts(String sessionId) {
        if (isBlank(sessionId)) {
            return List.of();
        }
        return sessionCache.getBestCandidates(sessionId);
    }

    private List<ProductCard> filterProducts(List<ProductCard> candidates, SearchFilter filter) {
        return filterService.filter(candidates, filter, Map.of(), DEFAULT_PAGE_SIZE, 1).products();
    }

    private String actionString(UserAction action) {
        UserAction.ActionPayload payload = action.payload();
        if (payload == null) {
            return null;
        }
        if ("sort".equals(action.source()) && !isBlank(payload.sortBy())) {
            return switch (payload.sortBy()) {
                case "relevance" -> "sort_relevance";
                case "price", "price_asc" -> "sort_by_price_asc";
                case "sales", "sales_desc" -> "sort_by_sales_desc";
                case "rating", "reviews", "rating_desc", "review_quality", "shop_trust", "seller_trust" ->
                        "sort_by_review_quality";
                default -> payload.sortBy().startsWith("sort_") ? payload.sortBy() : "sort_by_" + payload.sortBy();
            };
        }
        if (!isBlank(payload.action())) {
            return payload.action();
        }
        return null;
    }

    private String manualActionString(UserAction.ActionPayload payload) {
        if (payload == null) {
            return null;
        }
        if (!isBlank(payload.action())) {
            return payload.action();
        }
        String field = field(payload);
        String value = value(payload);
        if (isBlank(field) || isBlank(value)) {
            return null;
        }
        return switch (field) {
            case "brand", "brands" -> "filter_brand:" + value;
            case "color", "colors" -> "filter_color:" + value;
            case "platform", "platforms" -> "filter_platform:" + value;
            case "price_max", "budget_under" -> "filter_budget_under:" + value;
            default -> null;
        };
    }

    private SearchFilter applyManualFilter(SearchFilter filter, UserAction.ActionPayload payload) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        String field = field(payload);
        String value = value(payload);
        return switch (field == null ? "" : field) {
            case "rating_min" -> new SearchFilter(base.priceRange(), base.platforms(), base.selfOperated(),
                    base.colors(), base.brands(), parseDouble(value), base.sortBy(), base.sortOrder(),
                    base.keyword(), base.attributes(), base.excludeRoles(), base.capabilities());
            case "price_min" -> {
                PriceRange price = base.priceRange() != null ? base.priceRange() : new PriceRange(null, null);
                yield new SearchFilter(new PriceRange(parseDouble(value), price.max()), base.platforms(),
                        base.selfOperated(), base.colors(), base.brands(), base.ratingMin(), base.sortBy(),
                        base.sortOrder(), base.keyword(), base.attributes(), base.excludeRoles(), base.capabilities());
            }
            default -> base;
        };
    }

    public List<FilterTag> generateStructuredTags(SearchFilter filter) {
        if (filter == null) {
            return List.of();
        }
        java.util.ArrayList<FilterTag> tags = new java.util.ArrayList<>();
        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) {
                tags.add(FilterTag.ofField("<=" + formatPrice(filter.priceRange().max()), "price_range.max"));
            }
            if (filter.priceRange().min() != null) {
                tags.add(FilterTag.ofField(">=" + formatPrice(filter.priceRange().min()), "price_range.min"));
            }
        }
        if (filter.platforms() != null) {
            filter.platforms().forEach(platform -> tags.add(FilterTag.ofField(platform, "platforms." + platform)));
        }
        if (Boolean.TRUE.equals(filter.selfOperated())) {
            tags.add(FilterTag.ofField("自营", "self_operated"));
        }
        if (filter.colors() != null) {
            filter.colors().forEach(color -> tags.add(FilterTag.ofField(color, "colors." + color)));
        }
        if (filter.brands() != null) {
            filter.brands().forEach(brand -> tags.add(FilterTag.ofField(brand, "brands." + brand)));
        }
        if (filter.ratingMin() != null) {
            tags.add(FilterTag.ofField(">=" + filter.ratingMin(), "rating_min"));
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            tags.add(FilterTag.ofField(filter.keyword(), "keyword"));
        }
        // Attribute-based filters (model, material, type, core_product, category, etc.)
        if (filter.attributes() != null) {
            filter.attributes().forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    tags.add(FilterTag.ofField(value, "attributes." + key));
                }
            });
        }
        if (filter.capabilities() != null) {
            filter.capabilities().forEach((code, enabled) -> {
                if (Boolean.TRUE.equals(enabled)) {
                    tags.add(FilterTag.ofCapability(code, code, null));
                }
            });
        }
        return tags;
    }

    private List<ProductCard> safeProducts(List<ProductCard> products) {
        return products == null ? List.of() : products;
    }

    private List<ProductCard> displayProducts(ActionResult result) {
        if (result == null) {
            return List.of();
        }
        java.util.ArrayList<ProductCard> display = new java.util.ArrayList<>(safeProducts(result.products()));
        if ("MIXED_RESULTS".equals(result.displayMode()) && result.fallbackProducts() != null) {
            display.addAll(result.fallbackProducts());
        }
        return display.stream().limit(DEFAULT_PAGE_SIZE).toList();
    }

    private List<FilterTag> safeFilterTags(List<FilterTag> tags) {
        return tags == null ? List.of() : tags;
    }

    private java.util.Optional<NlpStateStackService.NlpState> peekNlpState(String sessionId) {
        java.util.Optional<NlpStateStackService.NlpState> state = nlpStateStackService.peek(sessionId);
        return state != null ? state : java.util.Optional.empty();
    }

    /**
     * Compare product ID lists to detect reorder / content change.
     */
    private boolean sameProductIds(List<ProductCard> a, List<ProductCard> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Objects.equals(a.get(i).id(), b.get(i).id())) return false;
        }
        return true;
    }

    private boolean sameFilterTags(List<FilterTag> a, List<FilterTag> b) {
        List<FilterTag> safeA = safeFilterTags(a);
        List<FilterTag> safeB = safeFilterTags(b);
        if (safeA.size() != safeB.size()) {
            return false;
        }
        for (int i = 0; i < safeA.size(); i++) {
            FilterTag left = safeA.get(i);
            FilterTag right = safeB.get(i);
            if (!java.util.Objects.equals(left.id(), right.id())
                    || !java.util.Objects.equals(left.filterPath(), right.filterPath())
                    || !java.util.Objects.equals(left.label(), right.label())) {
                return false;
            }
        }
        return true;
    }

    private String field(UserAction.ActionPayload payload) {
        return payload != null ? payload.field() : null;
    }

    private String value(UserAction.ActionPayload payload) {
        return payload != null ? payload.value() : null;
    }

    private Double parseDouble(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.valueOf((long) price);
        }
        return String.format("%.0f", price);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
