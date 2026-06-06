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
import com.visioncart.service.recognition.AttributeCorrectionService;
import com.visioncart.service.recognition.SessionHistoryService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.suggestion.SuggestionService;
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
                                  SemanticActionExecutor semanticActionExecutor) {
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

        // 1. Resolve session context
        UserAction.ActionPayload payload = action.payload();
        Map<String, String> contextMap = payload != null && payload.context() != null
                ? payload.context() : Map.of();
        String clientCategory = contextMap.get("category");
        // Use real userId so session history lookup can find recognition records
        SessionContextService.SessionContext ctx = sessionContextService.resolve(sessionId, userId, clientCategory);

        // 2. Get current candidates and previous filter state
        List<ProductCard> candidates = sessionCache.getCandidates(sessionId);
        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> previousProducts = filterService.filter(
                candidates, previousFilter, Map.of(), DEFAULT_PAGE_SIZE, 1).products();

        // 3. Generate semantic plan via LLM
        LlmSemanticPlanner.ProductPoolSummary poolSummary = LlmSemanticPlanner.ProductPoolSummary.from(candidates);
        SemanticActionPlan plan = llmSemanticPlanner.plan(userInput, ctx, poolSummary);

        // 4. Execute the plan
        ActionResult result = semanticActionExecutor.execute(
                sessionId, candidates, previousFilter, plan, previousProducts);

        // 5. Save undo point if filter, product order, or tags changed
        boolean filterChanged = result.appliedFilter() != null
                && !java.util.Objects.equals(previousFilter, result.appliedFilter());
        boolean productsChanged = result.products() != null
                && !sameProductIds(previousProducts, result.products());
        boolean tagsChanged = result.filterTags() != null && !result.filterTags().isEmpty();

        if (result.filterApplied() && (filterChanged || productsChanged || tagsChanged)) {
            undoService.saveUndoPoint(sessionId, previousFilter, previousProducts, userInput, "nlp", null);
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
        archiveIfNeeded(action.sessionId(), result.products());
        return withSuggestionCards(result);
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
        List<ProductCard> previousProducts = filterProducts(currentProducts(sessionId), userFilter);

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

        // Products: use correction results only if productsUpdated; otherwise keep previous
        List<ProductCard> products = productsUpdated ? correctionProducts : previousProducts;
        boolean keptPrevious = !productsUpdated && correction.correctionApplied();

        // Save undo point when attributes or products changed
        if (correction.correctionApplied()) {
            // Convert AttributeValue map to Object map for undo storage
            Map<String, Object> prevAttrs = new HashMap<>();
            if (correction.previousAttributes() != null) {
                correction.previousAttributes().forEach(prevAttrs::put);
            }
            undoService.saveUndoPoint(sessionId, userFilter, previousProducts,
                    action.rawText(), "correction", action.actionId(), prevAttrs);
        }

        // Tags: generate from USER's filter, NOT from correction state
        // Attribute correction changes search intent, not filter conditions
        List<FilterTag> tags = generateStructuredTags(userFilter);

        Map<String, Object> updatedAttributes = new HashMap<>();
        if (correction.updatedAttributes() != null) {
            correction.updatedAttributes().forEach(updatedAttributes::put);
        }

        // Resolve messageCode
        String messageCode = correction.messageCode();
        if (messageCode == null) {
            if (keptPrevious) {
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
                userFilter,
                tags,
                false,                            // filterApplied — correction is NOT a filter action
                keptPrevious,
                undoService.canUndo(sessionId),
                correction.message(),
                List.of(),
                List.of(),
                null,
                currentProducts(sessionId).size(),
                null,
                updatedAttributes,
                "correction",
                sessionId + ":correction:" + System.currentTimeMillis(),
                null,
                keptPrevious ? "KEPT_PREVIOUS" : "NORMAL",
                messageCode,
                correction.correctionApplied(),   // attributesUpdated
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

        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> candidates = currentProducts(sessionId);
        List<ProductCard> previousProducts = filterProducts(candidates, previousFilter);
        undoService.saveUndoPoint(sessionId, previousFilter, previousProducts,
                action.rawText(), "tag_delete", action.actionId());

        SearchFilter updatedFilter = conversationManager.removeFilterField(sessionId, filterPath);
        CandidateFilterService.FilterResult filterResult = filterService.filter(
                candidates, updatedFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
        archiveIfNeeded(sessionId, filterResult.products());

        return withSuggestionCards(new ActionResult(
                filterResult.products(),
                updatedFilter,
                generateStructuredTags(updatedFilter),
                true,
                false,
                undoService.canUndo(sessionId),
                "已删除筛选标签",
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

    private ActionResult executeClearFilter(UserAction action) {
        String sessionId = action.sessionId();
        SearchFilter previousFilter = conversationManager.getFilterState(sessionId);
        List<ProductCard> candidates = currentProducts(sessionId);
        List<ProductCard> previousProducts = filterProducts(candidates, previousFilter);
        undoService.saveUndoPoint(sessionId, previousFilter, previousProducts,
                action.rawText(), "clear_filter", action.actionId());

        SearchFilter emptyFilter = SearchFilter.empty();
        conversationManager.setFilterState(sessionId, emptyFilter);
        conversationManager.clear(sessionId);
        CandidateFilterService.FilterResult filterResult = filterService.filter(
                candidates, emptyFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);
        archiveIfNeeded(sessionId, filterResult.products());

        return withSuggestionCards(new ActionResult(
                filterResult.products(),
                emptyFilter,
                List.of(),
                true,
                false,
                undoService.canUndo(sessionId),
                "已清空筛选条件",
                List.of(),
                List.of(),
                null,
                candidates.size(),
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
        return sessionCache.getCandidates(sessionId);
    }

    private List<ProductCard> filterProducts(List<ProductCard> candidates, SearchFilter filter) {
        return filterService.filter(candidates, filter, Map.of(), DEFAULT_PAGE_SIZE, 1).products();
    }

    private String actionString(UserAction action) {
        UserAction.ActionPayload payload = action.payload();
        if (payload == null) {
            return null;
        }
        if (!isBlank(payload.action())) {
            return payload.action();
        }
        if ("sort".equals(action.source()) && !isBlank(payload.sortBy())) {
            return switch (payload.sortBy()) {
                case "relevance" -> "sort_relevance";
                case "price", "price_asc" -> "sort_by_price_asc";
                case "sales", "sales_desc" -> "sort_by_sales_desc";
                case "rating", "rating_desc", "review_quality" -> "sort_by_review_quality";
                default -> payload.sortBy().startsWith("sort_") ? payload.sortBy() : "sort_by_" + payload.sortBy();
            };
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
