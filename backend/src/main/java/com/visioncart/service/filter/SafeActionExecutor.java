package com.visioncart.service.filter;

import com.visioncart.api.dto.*;
import com.visioncart.service.filter.capability.CapabilitySynonymRegistry;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified safe action executor for ALL user actions.
 * Every user action (NLP, Suggestion, AttributeCorrection, FilterTag deletion)
 * goes through this executor to ensure consistent:
 * - Trial-first execution
 * - Zero-result guard
 * - Undo support
 * - Explanation generation
 * - Logging and metrics
 *
 * Core rules:
 * 1. No action directly commits state
 * 2. Trial first, then commit/rollback
 * 3. Zero results never overwrites old products
 * 4. Fuzzy semantics prefer rerank or safe_filter
 * 5. User must be able to undo
 * 6. Every filter must have an explanation
 */
@Service
public class SafeActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SafeActionExecutor.class);
    private static final int LOW_RESULT_THRESHOLD = 5;
    private static final int EXPANSION_LIMIT = 1000;

    private final FilterExecutionService filterExecutionService;
    private final CandidateFilterService filterService;
    private final NlpUndoService undoService;
    private final NlpConversationManager conversationManager;
    private final ActionCompiler actionCompiler;
    private final FilterPlanner filterPlanner;
    private final CapabilitySynonymRegistry capabilityRegistry;

    @Autowired
    public SafeActionExecutor(FilterExecutionService filterExecutionService,
                              CandidateFilterService filterService,
                              NlpUndoService undoService,
                              NlpConversationManager conversationManager,
                              ActionCompiler actionCompiler,
                              FilterPlanner filterPlanner,
                              CapabilitySynonymRegistry capabilityRegistry) {
        this.filterExecutionService = filterExecutionService;
        this.filterService = filterService;
        this.undoService = undoService;
        this.conversationManager = conversationManager;
        this.actionCompiler = actionCompiler;
        this.filterPlanner = filterPlanner;
        this.capabilityRegistry = capabilityRegistry;
    }

    public SafeActionExecutor(FilterExecutionService filterExecutionService,
                              CandidateFilterService filterService,
                              NlpUndoService undoService,
                              NlpConversationManager conversationManager,
                              ActionCompiler actionCompiler) {
        this(filterExecutionService, filterService, undoService, conversationManager,
                actionCompiler, new FilterPlanner(), new CapabilitySynonymRegistry());
    }

    /**
     * Result of a safe action execution.
     */
    public record SafeActionResult(
            List<ProductCard> products,
            SearchFilter appliedFilter,
            List<FilterTag> structuredFilterTags,
            List<String> filterTags,
            boolean committed,
            boolean keptPrevious,
            String message,
            List<String> warnings,
            List<String> explanations,
            boolean canUndo
    ) {}

    /**
     * Execute a filter action safely with trial-first semantics.
     *
     * @param sessionId    The session ID for undo/filter state
     * @param candidates   The candidate product pool
     * @param clauses      The FilterClauses to execute
     * @param baseFilter   The base filter (structural fields only, no semantic signals)
     * @param tentativeFilter The tentative filter with all fields (for state persistence on commit)
     * @param previousFilter  The previous filter state (for rollback)
     * @param previousProducts The previous product list (for rollback)
     * @param category     The product category for capability evaluation
     * @param actionSource The source of the action (nlp, suggestion, correction, tag_delete)
     * @param userQuery    The original user query (for undo tracking)
     * @return SafeActionResult with products, filter, tags, and status
     */
    public SafeActionResult execute(String sessionId,
                                    List<ProductCard> candidates,
                                    List<FilterClause> clauses,
                                    SearchFilter baseFilter,
                                    SearchFilter tentativeFilter,
                                    SearchFilter previousFilter,
                                    List<ProductCard> previousProducts,
                                    String category,
                                    String actionSource,
                                    String userQuery) {
        // 1. Build FilterPlan
        FilterPlan plan = filterPlanner.plan(clauses, actionSource + " action");

        // 2. Check for semantic clauses (capability, preference, exclusion, rerank)
        boolean hasSemanticClause = clauses.stream().anyMatch(c ->
                c.type() == FilterClause.ClauseType.CAPABILITY
                        || c.type() == FilterClause.ClauseType.PREFERENCE
                        || c.type() == FilterClause.ClauseType.EXCLUSION
                        || c.applyMode() == FilterClause.ApplyMode.RERANK
                        || c.applyMode() == FilterClause.ApplyMode.EXCLUSION
                        || c.applyMode() == FilterClause.ApplyMode.SAFE_FILTER);

        // 3. Prepare semantic pool (skip applyIntentFilter for semantic clauses)
        List<ProductCard> semanticPool;
        if (hasSemanticClause) {
            semanticPool = filterService.prepareSemanticPool(
                    candidates, baseFilter, Math.min(candidates.size(), EXPANSION_LIMIT));
        } else {
            CandidateFilterService.FilterResult baseResult = filterService.filter(
                    candidates, baseFilter, Map.of(), 50, 1);
            semanticPool = baseResult.products();
        }

        log.info("SafeActionExecutor [{}]: clauses={}, hasSemantic={}, poolSize={}, category={}",
                actionSource, clauses.size(), hasSemanticClause, semanticPool.size(), category);

        // 4. Execute through FilterExecutionService
        FilterExecutionService.FilterExecutionResult execResult =
                filterExecutionService.execute(plan, semanticPool, previousProducts, baseFilter, category);

        // 5. Expansion search if semantic results are insufficient
        if (hasSemanticClause && execResult.products().size() < LOW_RESULT_THRESHOLD) {
            log.info("SafeActionExecutor [{}]: Results {} < {}, attempting expansion",
                    actionSource, execResult.products().size(), LOW_RESULT_THRESHOLD);
            // Expansion would be handled by the caller (NlpFilterController has access to SearchOrchestrator)
            // For Suggestion/Correction, expansion is not needed since candidates are already the full pool
        }

        // 6. Generate structured tags
        // P0-3: On rollback, only return previous filter's tags — do NOT include failed clauses
        List<FilterClause> tagClauses = execResult.committed() ? clauses : List.of();
        List<FilterTag> structuredTags = generateStructuredTags(
                execResult.committed() ? tentativeFilter : previousFilter, tagClauses);
        List<String> filterTags = generateFlatTags(
                execResult.committed() ? tentativeFilter : previousFilter, tagClauses);

        // 7. Commit or rollback
        SearchFilter responseFilter;
        if (execResult.committed()) {
            if (sessionId != null) {
                if (!"sort".equals(actionSource)) {
                    undoService.saveUndoPoint(sessionId, previousFilter, previousProducts, userQuery, actionSource, userQuery);
                }
                conversationManager.setFilterState(sessionId, tentativeFilter);
            }
            responseFilter = tentativeFilter;
        } else {
            responseFilter = previousFilter;
        }

        // 8. Build message
        String message = execResult.warnings().isEmpty() ? null
                : String.join("；", execResult.warnings());

        log.info("SafeActionExecutor [{}]: results={}, committed={}, keptPrevious={}",
                actionSource, execResult.products().size(), execResult.committed(), execResult.keptPrevious());

        return new SafeActionResult(
                execResult.products(),
                responseFilter,
                structuredTags,
                filterTags,
                execResult.committed(),
                execResult.keptPrevious(),
                message,
                execResult.warnings(),
                execResult.explanations(),
                sessionId != null && !"sort".equals(actionSource) && undoService.canUndo(sessionId)
        );
    }

    /**
     * Unified entry point: execute a UserAction and return ActionResult.
     * ALL user actions (NLP, suggestion, correction, tag_delete, sort, manual_filter)
     * go through this method.
     *
     * @param action           The unified user action
     * @param clauses          Pre-compiled FilterClauses (caller is responsible for compilation)
     * @param candidates       The candidate product pool
     * @param previousProducts The previous product list (for rollback)
     * @param previousFilter   The previous filter state (for rollback)
     * @param tentativeFilter  The tentative filter with all fields (for state persistence on commit)
     * @param baseFilter       The base filter (structural fields only)
     * @param category         The product category for capability evaluation
     * @return ActionResult — unified response for Android
     */
    public ActionResult executeAction(UserAction action,
                                      List<FilterClause> clauses,
                                      List<ProductCard> candidates,
                                      List<ProductCard> previousProducts,
                                      SearchFilter previousFilter,
                                      SearchFilter tentativeFilter,
                                      SearchFilter baseFilter,
                                      String category) {
        String sessionId = action.sessionId();
        String source = action.source();
        String rawText = action.rawText();

        // Handle flow actions (no filter change, UI action instead)
        if (clauses.isEmpty() && "suggestion".equals(source) && action.payload() != null) {
            String actionStr = action.payload().action();
            if ("set_price_alert".equals(actionStr)) {
                log.info("SafeActionExecutor [{}]: flow action '{}', returning uiAction", source, actionStr);
                return ActionResult.flowAction(
                        new ActionResult.UiAction("OPEN_PRICE_ALERT_DIALOG", Map.of()),
                        "请设置降价提醒");
            }
        }

        // Empty clauses = pass-through
        if (clauses.isEmpty()) {
            log.info("SafeActionExecutor [{}]: empty clauses, pass-through", source);
            return ActionResult.passThrough(candidates);
        }

        // Delegate to core execute method
        SafeActionResult result = execute(
                sessionId, candidates, clauses,
                baseFilter, tentativeFilter, previousFilter, previousProducts,
                category, source, rawText);

        // Convert SafeActionResult → ActionResult
        // P2-8: For sparse results, include previous products as fallback
        if (result.committed() && !result.products().isEmpty()
                && result.products().size() < LOW_RESULT_THRESHOLD
                && previousProducts != null && !previousProducts.isEmpty()
                && result.products().size() < previousProducts.size()) {
            // Deduplicate: exclude primary product IDs from fallback
            var primaryIds = result.products().stream().map(ProductCard::id).collect(java.util.stream.Collectors.toSet());
            List<ProductCard> fallback = previousProducts.stream()
                    .filter(p -> !primaryIds.contains(p.id()))
                    .limit(50)
                    .toList();
            if (!fallback.isEmpty()) {
                log.info("Sparse results ({} primary), adding {} fallback products", result.products().size(), fallback.size());
                return ActionResult.sparseWithFallback(
                        result.products(), fallback,
                        result.appliedFilter(), result.structuredFilterTags(),
                        result.canUndo(),
                        result.warnings().isEmpty()
                                ? "找到 " + result.products().size() + " 个明确符合的商品，下方保留原结果供参考"
                                : result.message(),
                        result.warnings(), result.explanations());
            }
        }

        return new ActionResult(
                result.products(),
                result.appliedFilter(),
                result.structuredFilterTags(),
                result.committed(),
                result.keptPrevious(),
                result.canUndo(),
                result.message(),
                result.warnings(),
                result.explanations(),
                null,  // uiAction (not a flow action)
                candidates.size(),
                null,  // suggestionCards (caller fills this)
                null,  // updatedAttributes (caller fills this)
                source,
                sessionId != null ? sessionId + ":" + System.currentTimeMillis() : null,
                null,  // fallbackProducts
                result.committed() ? "NORMAL" : "ROLLED_BACK",
                null,  // messageCode
                null,  // attributesUpdated
                null   // productsUpdated
        );
    }

    /**
     * Compile a suggestion action string into FilterClauses.
     * Convenience method for callers that need to compile before executeAction.
     */
    public List<FilterClause> compileSuggestion(String action, SearchFilter currentFilter) {
        return actionCompiler.compile(action, currentFilter);
    }

    /**
     * Compile a correction into FilterClauses.
     */
    public List<FilterClause> compileCorrection(String field, String newValue) {
        return switch (field) {
            case "color", "颜色" -> List.of(
                    FilterClause.structured("correction-color-" + newValue,
                            newValue, "color", "contains", newValue));
            case "brand", "品牌" -> List.of(
                    FilterClause.structured("correction-brand-" + newValue,
                            newValue, "brand", "contains", newValue));
            case "platform", "平台" -> List.of(
                    FilterClause.structured("correction-platform-" + newValue,
                            newValue, "platform", "eq", newValue));
            case "rating_min", "评分" -> {
                try {
                    double rating = Double.parseDouble(newValue);
                    yield List.of(FilterClause.structured("correction-rating",
                            "≥" + newValue + "分", "rating_min", "gte", rating));
                } catch (NumberFormatException e) {
                    yield List.of();
                }
            }
            default -> List.of(
                    FilterClause.structured("correction-" + field,
                            newValue, field, "eq", newValue));
        };
    }

    /**
     * Compile a tag deletion into FilterClauses (exclusion of the tag's filter path).
     */
    public List<FilterClause> compileTagDelete(String filterPath) {
        if (filterPath == null) return List.of();
        // Tag deletion is handled by removing the filter field, not by adding a clause.
        // Return empty — the caller should call conversationManager.removeFilterField() directly.
        return List.of();
    }

    /**
     * Generate structured filter tags from filter and clauses.
     */
    private List<FilterTag> generateStructuredTags(SearchFilter filter, List<FilterClause> clauses) {
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

        // Clause tags
        if (clauses != null) {
            for (FilterClause clause : clauses) {
                String code = clause.field();
                String label = capabilityCodeToDisplayText(code);
                String rawText = clause.rawText();
                switch (clause.type()) {
                    case CAPABILITY -> tags.add(FilterTag.ofCapability(code, label, rawText));
                    case PREFERENCE -> tags.add(FilterTag.ofPreference(code, label, rawText));
                    case EXCLUSION -> tags.add(FilterTag.ofExclusion(code, label, rawText));
                    default -> {}
                }
            }
        }

        // Capability tags from filter.capabilities map
        if (filter.capabilities() != null) {
            for (Map.Entry<String, Boolean> entry : filter.capabilities().entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    String code = entry.getKey();
                    boolean alreadyAdded = tags.stream().anyMatch(t ->
                            t.filterPath() != null && t.filterPath().equals("capabilities." + code));
                    if (!alreadyAdded) {
                        tags.add(FilterTag.ofCapability(code, capabilityCodeToDisplayText(code), null));
                    }
                }
            }
        }

        return tags;
    }

    /**
     * Generate flat string tags for backward compatibility.
     */
    private List<String> generateFlatTags(SearchFilter filter, List<FilterClause> clauses) {
        List<String> tags = new ArrayList<>();
        if (filter == null) return tags;

        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) tags.add("≤¥" + formatPrice(filter.priceRange().max()));
            if (filter.priceRange().min() != null) tags.add("≥¥" + formatPrice(filter.priceRange().min()));
        }
        if (filter.platforms() != null) filter.platforms().stream().limit(2).forEach(tags::add);
        if (Boolean.TRUE.equals(filter.selfOperated())) tags.add("自营");
        if (filter.colors() != null) filter.colors().forEach(tags::add);
        if (filter.brands() != null) filter.brands().forEach(tags::add);
        if (filter.ratingMin() != null) tags.add("≥" + filter.ratingMin() + "分");
        if (filter.keyword() != null && !filter.keyword().isBlank() && !filter.keyword().startsWith("!")) {
            tags.add(filter.keyword());
        }
        if (clauses != null) {
            for (FilterClause clause : clauses) {
                if (clause.type() == FilterClause.ClauseType.CAPABILITY && clause.rawText() != null) {
                    tags.add(clause.rawText());
                }
            }
        }
        return tags;
    }

    private String capabilityCodeToDisplayText(String code) {
        return capabilityRegistry.displayText(code);
    }

    private String formatPrice(double price) {
        if (price == (long) price) return String.valueOf((long) price);
        return String.format("%.0f", price);
    }
}
