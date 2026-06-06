package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.*;
import com.visioncart.service.filter.FilterExecutionService;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.filter.capability.ProductFeatureExtractor;
import com.visioncart.service.nlp.LlmSemanticPlanner;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes a SemanticActionPlan against a product pool.
 *
 * This is the bridge between LLM planning and product filtering.
 * It coordinates:
 * - Hard filtering (price, platform, color, brand)
 * - Evidence-based semantic screening (capability scoring)
 * - Preference reranking
 * - Exclusion filtering
 * - Zero-result safety (rollback on empty results)
 *
 * Output: ActionResult with primary/inferred/uncertain products + explanations.
 */
@Component
public class SemanticActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SemanticActionExecutor.class);
    private static final int MAX_PRODUCTS = 50;
    private static final int LOW_RESULT_THRESHOLD = 3;

    private final CandidateFilterService filterService;
    private final EvidenceScorer evidenceScorer;
    private final NlpConversationManager conversationManager;
    private final PreferenceScorer preferenceScorer;
    private final LlmProductJudge productJudge;

    public SemanticActionExecutor(CandidateFilterService filterService,
                                  EvidenceScorer evidenceScorer,
                                  NlpConversationManager conversationManager,
                                  PreferenceScorer preferenceScorer,
                                  LlmProductJudge productJudge) {
        this.filterService = filterService;
        this.evidenceScorer = evidenceScorer;
        this.conversationManager = conversationManager;
        this.preferenceScorer = preferenceScorer;
        this.productJudge = productJudge;
    }

    /**
     * Execute a semantic action plan.
     *
     * @param sessionId      The session ID
     * @param candidates     All candidate products
     * @param previousFilter The current filter state
     * @param plan           The semantic action plan from LlmSemanticPlanner
     * @param previousProducts What the user is currently seeing
     * @return ActionResult with products, explanations, and UI hints
     */
    public ActionResult execute(String sessionId,
                                List<ProductCard> candidates,
                                SearchFilter previousFilter,
                                SemanticActionPlan plan,
                                List<ProductCard> previousProducts) {
        log.info("SemanticActionExecutor: mode={}, semanticFilters={}, hardFilters={}",
                plan.executionMode(),
                plan.semanticFilters() != null ? plan.semanticFilters().size() : 0,
                plan.hardFilters() != null ? plan.hardFilters().size() : 0);

        return switch (plan.executionMode()) {
            case "STRICT_FILTER" -> executeStrictFilter(sessionId, candidates, previousFilter, plan, previousProducts);
            case "SEMANTIC_SCREENING" -> executeSemanticScreening(sessionId, candidates, previousFilter, plan, previousProducts);
            case "PREFERENCE_RERANK" -> hasJudge(plan)
                    ? executeJudgeRerank(sessionId, candidates, previousFilter, plan, previousProducts)
                    : executePreferenceRerank(sessionId, candidates, previousFilter, plan, previousProducts);
            case "LLM_RERANK" -> executeJudgeRerank(sessionId, candidates, previousFilter, plan, previousProducts);
            case "COMBINED" -> executeCombined(sessionId, candidates, previousFilter, plan, previousProducts);
            case "EXCLUSION" -> executeExclusion(sessionId, candidates, previousFilter, plan, previousProducts);
            default -> ActionResult.passThrough(candidates.stream().limit(MAX_PRODUCTS).toList());
        };
    }

    private ActionResult executeCombined(String sessionId,
                                         List<ProductCard> candidates,
                                         SearchFilter previousFilter,
                                         SemanticActionPlan plan,
                                         List<ProductCard> previousProducts) {
        if (hasEvidence(plan)) {
            return executeSemanticScreening(sessionId, candidates, previousFilter, plan, previousProducts);
        }
        if (hasJudge(plan)) {
            return executeJudgeRerank(sessionId, candidates, previousFilter, plan, previousProducts);
        }
        if (hasExclusions(plan)) {
            return executePreparedFilter(sessionId, candidates, previousFilter, plan, previousProducts);
        }
        if (hasHardFilters(plan) || plan.sort() != null) {
            return executeStrictFilter(sessionId, candidates, previousFilter, plan, previousProducts);
        }
        if (plan.preferences() != null && !plan.preferences().isEmpty()) {
            return executePreferenceRerank(sessionId, candidates, previousFilter, plan, previousProducts);
        }
        return ActionResult.passThrough(candidates.stream().limit(MAX_PRODUCTS).toList());
    }

    private ActionResult executePreparedFilter(String sessionId,
                                               List<ProductCard> candidates,
                                               SearchFilter previousFilter,
                                               SemanticActionPlan plan,
                                               List<ProductCard> previousProducts) {
        PoolPreparation prepared = preparePool(candidates, previousFilter, plan, 1000);
        if (prepared.products().isEmpty()) {
            return ActionResult.rolledBack(previousProducts, previousFilter, List.of(),
                    "没有找到符合条件的商品，已保留原结果", List.of());
        }
        conversationManager.setFilterState(sessionId, prepared.filter());
        String message = hasExclusions(plan) ? "已排除相关商品" : "已筛选";
        return ActionResult.filtered(
                prepared.products().stream().limit(MAX_PRODUCTS).toList(),
                prepared.filter(),
                prepared.filterTags(),
                true,
                message,
                List.of(),
                List.of());
    }

    private ActionResult executeJudgeRerank(String sessionId,
                                            List<ProductCard> candidates,
                                            SearchFilter previousFilter,
                                            SemanticActionPlan plan,
                                            List<ProductCard> previousProducts) {
        PoolPreparation prepared = preparePool(candidates, previousFilter, plan, 1000);
        if (prepared.products().isEmpty()) {
            return ActionResult.rolledBack(previousProducts, previousFilter, List.of(),
                    "没有找到符合条件的商品，已保留原结果", List.of());
        }
        return executeJudgeRerankOnPool(sessionId, prepared.products(), prepared.filter(), plan,
                previousProducts, candidates.size(), prepared.filterTags());
    }

    private ActionResult executeJudgeRerankOnPool(String sessionId,
                                                  List<ProductCard> products,
                                                  SearchFilter appliedFilter,
                                                  SemanticActionPlan plan,
                                                  List<ProductCard> previousProducts,
                                                  int totalInPool,
                                                  List<FilterTag> existingTags) {
        JudgeRerankOutcome judged = rerankWithJudgeOrPreference(products, plan);
        List<ProductCard> display = judged.products().isEmpty()
                ? products.stream().limit(MAX_PRODUCTS).toList()
                : judged.products().stream().limit(MAX_PRODUCTS).toList();

        if (display.isEmpty()) {
            return ActionResult.rolledBack(previousProducts, appliedFilter, existingTags,
                    "没有找到符合条件的商品，已保留原结果", judged.warnings());
        }

        conversationManager.setFilterState(sessionId, appliedFilter);
        List<FilterTag> tags = new ArrayList<>(existingTags);
        String label = judgeLabel(plan);
        tags.add(FilterTag.ofPreference("semantic_judge", label, label));

        String message = "已按「" + label + "」调整排序";
        if (plan.rankingGoal() != null && !plan.rankingGoal().isBlank()) {
            message = "已按「" + label + "」排序：" + plan.rankingGoal();
        }

        return new ActionResult(
                display, appliedFilter, tags, true, false, true,
                message, judged.warnings(), judged.explanations(), null, totalInPool,
                null, null, "nlp", null, null, "NORMAL", null, null, null);
    }

    // ==================== STRICT_FILTER ====================

    private ActionResult executeStrictFilter(String sessionId,
                                             List<ProductCard> candidates,
                                             SearchFilter previousFilter,
                                             SemanticActionPlan plan,
                                             List<ProductCard> previousProducts) {
        // Build SearchFilter from hard filters
        SearchFilter hardFilter = buildSearchFilterFromHardFilters(plan.hardFilters(), previousFilter);

        // Merge sort from plan — "价格低到高" / "销量高的" / "评分高的" etc.
        hardFilter = mergeSort(hardFilter, plan.sort());

        // Apply hard filter
        CandidateFilterService.FilterResult result = filterService.filter(
                candidates, hardFilter, Map.of(), MAX_PRODUCTS, 1);

        if (result.products().isEmpty()) {
            // Zero result → rollback, keep previous
            log.info("STRICT_FILTER: 0 results, rolling back");
            return ActionResult.rolledBack(
                    previousProducts, previousFilter, List.of(),
                    "没有找到符合条件的商品，已保留原结果",
                    List.of());
        }

        // Update filter state
        conversationManager.setFilterState(sessionId, hardFilter);

        return ActionResult.filtered(
                result.products(), hardFilter, generateFilterTags(hardFilter, plan),
                true, "已筛选", "filter.applied", List.of(), List.of());
    }

    // ==================== SEMANTIC_SCREENING ====================

    private ActionResult executeSemanticScreening(String sessionId,
                                                  List<ProductCard> candidates,
                                                  SearchFilter previousFilter,
                                                  SemanticActionPlan plan,
                                                  List<ProductCard> previousProducts) {
        // 1. Apply hard filters and exclusions first to narrow the pool
        PoolPreparation prepared = preparePool(candidates, previousFilter, plan, 1000);
        List<ProductCard> basePool = prepared.products();
        SearchFilter baseFilter = prepared.filter();

        if (basePool.isEmpty()) {
            return ActionResult.rolledBack(previousProducts, previousFilter, List.of(),
                    "没有找到符合条件的商品", List.of());
        }

        List<SemanticActionPlan.SemanticFilter> evidenceFilters = evidenceFilters(plan);
        if (evidenceFilters.isEmpty()) {
            if (hasJudge(plan)) {
                return executeJudgeRerankOnPool(sessionId, basePool, baseFilter, plan, previousProducts,
                        candidates.size(), prepared.filterTags());
            }
            return ActionResult.filtered(
                    basePool.stream().limit(MAX_PRODUCTS).toList(), baseFilter, prepared.filterTags(),
                    true, "已筛选", "filter.applied", List.of(), List.of());
        }

        // 2. Score products against semantic filters
        List<ScoredProduct> allScored = new ArrayList<>();
        for (SemanticActionPlan.SemanticFilter sf : evidenceFilters) {
            List<ScoredProduct> scored = evidenceScorer.score(basePool, sf);
            allScored.addAll(scored);
        }

        // 3. Deduplicate: keep best score per product
        Map<String, ScoredProduct> bestByProduct = new LinkedHashMap<>();
        for (ScoredProduct sp : allScored) {
            String id = sp.product().id();
            bestByProduct.merge(id, sp, (existing, candidate) ->
                    candidate.score() > existing.score() ? candidate : existing);
        }

        // 4. Bucket products
        List<ScoredProduct> allBest = new ArrayList<>(bestByProduct.values());
        List<ProductCard> explicitMatches = new ArrayList<>();
        List<ProductCard> inferredMatches = new ArrayList<>();
        List<ProductCard> uncertainRelated = new ArrayList<>();
        List<ProductCard> rejected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        for (ScoredProduct sp : allBest) {
            switch (sp.bucket()) {
                case EXPLICIT_MATCH -> explicitMatches.add(sp.product());
                case INFERRED_MATCH -> inferredMatches.add(sp.product());
                case UNCERTAIN_RELATED -> uncertainRelated.add(sp.product());
                case REJECTED -> rejected.add(sp.product());
            }
            warnings.addAll(sp.warnings());
            explanations.addAll(sp.evidence());
        }

        // 5. Build display lists
        List<ProductCard> primaryProducts = new ArrayList<>();
        primaryProducts.addAll(explicitMatches);
        primaryProducts.addAll(inferredMatches);

        List<ProductCard> fallbackProducts = uncertainRelated;

        // 6. Zero-result guard
        if (primaryProducts.isEmpty() && fallbackProducts.isEmpty()) {
            log.info("SEMANTIC_SCREENING: 0 matches, rolling back");
            return ActionResult.rolledBack(previousProducts, previousFilter, List.of(),
                    buildZeroResultMessage(plan), List.of());
        }

        // 7. Generate structured filter tags
        List<FilterTag> filterTags = new ArrayList<>(prepared.filterTags());
        for (SemanticActionPlan.SemanticFilter sf : evidenceFilters) {
            filterTags.add(FilterTag.ofCapability(sf.code(), sf.userMeaning(), sf.userMeaning()));
        }

        // Build match bucket counts
        Map<MatchBucket, Integer> bucketCounts = new EnumMap<>(MatchBucket.class);
        bucketCounts.put(MatchBucket.EXPLICIT_MATCH, explicitMatches.size());
        bucketCounts.put(MatchBucket.INFERRED_MATCH, inferredMatches.size());
        bucketCounts.put(MatchBucket.UNCERTAIN_RELATED, uncertainRelated.size());
        bucketCounts.put(MatchBucket.REJECTED, rejected.size());

        // 8. Fallback-only: don't commit strong filter state
        if (primaryProducts.isEmpty() && !fallbackProducts.isEmpty()) {
            log.info("SEMANTIC_SCREENING: only fallback results, not committing filter");
            String message = "没有找到明确符合的商品，下面保留可能相关结果供参考";
            return new ActionResult(
                    previousProducts, previousFilter, filterTags, false, true, false,
                    message, List.of(), List.of(), null, candidates.size(),
                    null, null, "nlp", null,
                    fallbackProducts.stream().limit(20).toList(),
                    "MIXED_RESULTS", "filter.soft_reference", null, null);
        }

        if (hasJudge(plan)) {
            JudgeRerankOutcome judged = rerankWithJudgeOrPreference(primaryProducts, plan);
            if (!judged.products().isEmpty()) {
                primaryProducts = judged.products();
                warnings.addAll(judged.warnings());
                explanations.addAll(judged.explanations());
            }
        }

        // 9. Build result with committed filter
        SearchFilter appliedFilter = mergeCapabilities(baseFilter, plan.semanticFilters());
        appliedFilter = mergeSort(appliedFilter, plan.sort());
        conversationManager.setFilterState(sessionId, appliedFilter);

        String message = buildScreeningMessage(plan, bucketCounts);
        String displayMode = fallbackProducts.isEmpty() ? "NORMAL" : "MIXED_RESULTS";

        if (!primaryProducts.isEmpty() && !fallbackProducts.isEmpty()) {
            return ActionResult.sparseWithFallback(
                    primaryProducts.stream().limit(MAX_PRODUCTS).toList(),
                    fallbackProducts.stream().limit(20).toList(),
                    appliedFilter, filterTags, true,
                    message, warnings, explanations);
        }

        return new ActionResult(
                primaryProducts.stream().limit(MAX_PRODUCTS).toList(),
                appliedFilter, filterTags, true, false, true,
                message, warnings, explanations, null, candidates.size(),
                null, null, "nlp", null,
                fallbackProducts.isEmpty() ? null : fallbackProducts.stream().limit(20).toList(),
                displayMode, null, null, null);
    }

    // ==================== PREFERENCE_RERANK ====================

    private ActionResult executePreferenceRerank(String sessionId,
                                                 List<ProductCard> candidates,
                                                 SearchFilter previousFilter,
                                                 SemanticActionPlan plan,
                                                 List<ProductCard> previousProducts) {
        // Preferences don't filter — they rerank using PreferenceScorer
        List<ProductCard> reranked = preferenceScorer.rerank(candidates, plan.preferences());
        List<ProductCard> display = reranked.stream().limit(MAX_PRODUCTS).toList();
        conversationManager.setFilterState(sessionId, previousFilter);

        // Build a descriptive message from preference rules
        String prefDesc = plan.preferences() != null && !plan.preferences().isEmpty()
                ? plan.preferences().stream()
                    .map(SemanticActionPlan.PreferenceRule::userMeaning)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("、"))
                : "偏好";
        String message = "已按「" + prefDesc + "」调整排序";

        // Generate preference filter tags
        List<FilterTag> tags = new ArrayList<>();
        if (plan.preferences() != null) {
            for (SemanticActionPlan.PreferenceRule pref : plan.preferences()) {
                String label = pref.userMeaning() != null ? pref.userMeaning() : pref.code();
                tags.add(FilterTag.ofPreference(
                        pref.code() != null ? pref.code() : "preference",
                        label, pref.userMeaning()));
            }
        }

        return new ActionResult(
                display, previousFilter, tags, true, false, true,
                message, List.of(), List.of(), null, candidates.size(),
                null, null, "nlp", null, null, "NORMAL", "preference.applied", null, null);
    }

    // ==================== EXCLUSION ====================

    private static final Set<String> ACCESSORY_ROLES = Set.of(
            "accessory", "case", "part", "consumable", "storage");

    private ActionResult executeExclusion(String sessionId,
                                          List<ProductCard> candidates,
                                          SearchFilter previousFilter,
                                          SemanticActionPlan plan,
                                          List<ProductCard> previousProducts) {
        ExclusionSpec exclusions = collectExclusions(plan);

        // Filter out excluded products: structural check first, then text fallback
        List<ProductCard> filtered = candidates.stream()
                .filter(p -> !matchesExclusion(p, exclusions))
                .limit(MAX_PRODUCTS)
                .toList();

        if (filtered.isEmpty()) {
            return ActionResult.rolledBack(previousProducts, previousFilter, List.of(),
                    "排除后没有剩余商品，已保留原结果", List.of());
        }

        SearchFilter updatedFilter = mergeExcludeRoles(previousFilter, exclusions.roles());
        conversationManager.setFilterState(sessionId, updatedFilter);

        // Generate exclusion filter tags
        List<FilterTag> tags = new ArrayList<>();
        if (plan.exclusions() != null) {
            for (SemanticActionPlan.ExclusionRule ex : plan.exclusions()) {
                String label = ex.userMeaning() != null ? ex.userMeaning()
                        : ("exclude_accessory".equals(ex.code()) ? "排除配件" : ex.code());
                tags.add(FilterTag.ofExclusion(ex.code(), label, ex.userMeaning()));
            }
        }
        if (plan.negativeCriteria() != null) {
            for (SemanticActionPlan.NegativeCriterion criterion : plan.negativeCriteria()) {
                String label = criterion.userMeaning() != null ? criterion.userMeaning() : criterion.name();
                tags.add(FilterTag.ofExclusion("negative_" + sanitizeCode(criterion.name(), tags.size()), label, label));
            }
        }

        return new ActionResult(
                filtered, updatedFilter, tags, true, false, true,
                "已排除相关商品", List.of(), List.of(), null, candidates.size(),
                null, null, "nlp", null, null, "NORMAL", "exclusion.applied", null, null);
    }

    private boolean isAccessoryExclusion(SemanticActionPlan plan) {
        if (plan.exclusions() == null) return false;
        return plan.exclusions().stream()
                .anyMatch(ex -> "exclude_accessory".equals(ex.code())
                        || (ex.userMeaning() != null && ex.userMeaning().contains("配件")));
    }

    // ==================== Helpers ====================

    private PoolPreparation preparePool(List<ProductCard> candidates,
                                        SearchFilter previousFilter,
                                        SemanticActionPlan plan,
                                        int limit) {
        SearchFilter baseFilter = previousFilter != null ? previousFilter : SearchFilter.empty();
        if (hasHardFilters(plan)) {
            baseFilter = buildSearchFilterFromHardFilters(plan.hardFilters(), baseFilter);
        }
        baseFilter = mergeSort(baseFilter, plan.sort());

        CandidateFilterService.FilterResult hardResult = filterService.filter(
                candidates, baseFilter, Map.of(), limit, 1);
        List<ProductCard> products = new ArrayList<>(hardResult.products());

        ExclusionSpec exclusions = collectExclusions(plan);
        if (!exclusions.isEmpty()) {
            products = products.stream()
                    .filter(p -> !matchesExclusion(p, exclusions))
                    .toList();
            baseFilter = mergeExcludeRoles(baseFilter, exclusions.roles());
        }

        List<FilterTag> tags = new ArrayList<>(generateHardFilterTags(baseFilter));
        if (plan.exclusions() != null) {
            for (SemanticActionPlan.ExclusionRule ex : plan.exclusions()) {
                String label = ex.userMeaning() != null ? ex.userMeaning() : ex.code();
                tags.add(FilterTag.ofExclusion(ex.code(), label, ex.userMeaning()));
            }
        }
        return new PoolPreparation(products, baseFilter, tags);
    }

    private List<SemanticActionPlan.SemanticFilter> evidenceFilters(SemanticActionPlan plan) {
        List<SemanticActionPlan.SemanticFilter> filters = new ArrayList<>();
        if (plan.semanticFilters() != null) {
            filters.addAll(plan.semanticFilters());
        }

        List<SemanticActionPlan.EvidenceRule> negativeRules = dynamicNegativeEvidenceRules(plan.negativeCriteria());
        if (plan.criteria() != null) {
            int index = 0;
            for (SemanticActionPlan.DynamicCriterion criterion : plan.criteria()) {
                List<SemanticActionPlan.EvidenceRule> positiveRules = dynamicPositiveEvidenceRules(criterion, index);
                if (positiveRules.isEmpty() && negativeRules.isEmpty()) {
                    index++;
                    continue;
                }
                String code = "criteria_" + sanitizeCode(criterion.name(), index);
                String label = firstNonBlank(criterion.userMeaning(), criterion.name(), code);
                filters.add(new SemanticActionPlan.SemanticFilter(
                        code,
                        label,
                        firstNonBlank(criterion.type(), "EVIDENCE_OR_SCORE"),
                        positiveRules,
                        negativeRules,
                        firstNonBlank(criterion.unknownPolicy(), "KEEP_AS_SECONDARY")));
                index++;
            }
        }
        return filters;
    }

    private List<SemanticActionPlan.EvidenceRule> dynamicPositiveEvidenceRules(
            SemanticActionPlan.DynamicCriterion criterion,
            int criterionIndex) {
        if (criterion == null || criterion.signals() == null) return List.of();
        List<SemanticActionPlan.EvidenceRule> rules = new ArrayList<>();
        int index = 0;
        for (SemanticActionPlan.CriterionSignal signal : criterion.signals()) {
            toEvidenceRule(signal, "criteria_" + criterionIndex + "_" + index,
                    firstNonBlank(signal.description(), criterion.name()), false)
                    .ifPresent(rules::add);
            index++;
        }
        return rules;
    }

    private List<SemanticActionPlan.EvidenceRule> dynamicNegativeEvidenceRules(
            List<SemanticActionPlan.NegativeCriterion> criteria) {
        if (criteria == null) return List.of();
        List<SemanticActionPlan.EvidenceRule> rules = new ArrayList<>();
        int criterionIndex = 0;
        for (SemanticActionPlan.NegativeCriterion criterion : criteria) {
            if (criterion.signals() == null) {
                criterionIndex++;
                continue;
            }
            int signalIndex = 0;
            for (SemanticActionPlan.CriterionSignal signal : criterion.signals()) {
                toEvidenceRule(signal, "negative_" + criterionIndex + "_" + signalIndex,
                        firstNonBlank(signal.description(), criterion.name()), true)
                        .ifPresent(rules::add);
                signalIndex++;
            }
            criterionIndex++;
        }
        return rules;
    }

    private Optional<SemanticActionPlan.EvidenceRule> toEvidenceRule(SemanticActionPlan.CriterionSignal signal,
                                                                      String id,
                                                                      String description,
                                                                      boolean negative) {
        if (signal == null) return Optional.empty();

        List<String> fields = signal.field() != null && !signal.field().isBlank()
                ? List.of(signal.field())
                : List.of("all_text");
        List<String> values = signal.values() != null ? signal.values() : List.of();
        Object value = signal.value();
        String operator = signal.operator();
        if (operator == null || operator.isBlank()) {
            operator = !values.isEmpty() ? "contains_any" : "contains";
        }
        if (value == null && values.isEmpty() && signal.formula() == null) {
            return Optional.empty();
        }

        double score = negative ? 0.0 : signal.score() != null ? signal.score() : 0.70;
        String bucket = negative ? "REJECTED" : firstNonBlank(signal.bucket(), "INFERRED_MATCH");
        String action = negative ? "reject" : "accept";

        return Optional.of(new SemanticActionPlan.EvidenceRule(
                id,
                firstNonBlank(description, id),
                fields,
                operator,
                value,
                values.isEmpty() ? null : values,
                signal.formula(),
                score,
                bucket,
                action,
                null));
    }

    private JudgeRerankOutcome rerankWithJudgeOrPreference(List<ProductCard> products,
                                                           SemanticActionPlan plan) {
        if (products == null || products.isEmpty()) {
            return new JudgeRerankOutcome(List.of(), List.of(), List.of());
        }

        if (hasJudge(plan)) {
            List<LlmProductJudge.JudgeScore> scores;
            try {
                scores = productJudge.judge(
                        judgeLabel(plan), plan.rankingGoal(), plan.judge(), products);
            } catch (Exception e) {
                log.warn("LLM product judge failed in executor, falling back to local ranking: {}", e.getMessage());
                scores = List.of();
            }
            JudgeRerankOutcome outcome = applyJudgeScores(products, plan, scores);
            if (!outcome.products().isEmpty()) {
                return outcome;
            }
        }

        List<ProductCard> fallback = preferenceScorer.rerank(products, plan.preferences());
        List<String> warnings = hasJudge(plan)
                ? List.of("AI语义评分不可用，已使用本地排序规则")
                : List.of();
        return new JudgeRerankOutcome(fallback, warnings, List.of());
    }

    private JudgeRerankOutcome applyJudgeScores(List<ProductCard> products,
                                                SemanticActionPlan plan,
                                                List<LlmProductJudge.JudgeScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return new JudgeRerankOutcome(List.of(), List.of(), List.of());
        }

        Map<String, ProductCard> productById = products.stream()
                .filter(p -> p.id() != null)
                .collect(Collectors.toMap(ProductCard::id, p -> p, (a, b) -> a, LinkedHashMap::new));
        double minScore = productJudge.minScore(plan.judge());
        Set<String> rejectedIds = scores.stream()
                .filter(s -> "REJECT".equals(s.bucket()))
                .map(LlmProductJudge.JudgeScore::productId)
                .collect(Collectors.toSet());

        List<ProductCard> ranked = scores.stream()
                .filter(s -> !"REJECT".equals(s.bucket()))
                .filter(s -> s.score() >= minScore)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .map(s -> productById.get(s.productId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        Set<String> included = ranked.stream().map(ProductCard::id).collect(Collectors.toSet());
        for (ProductCard product : products) {
            if (ranked.size() >= productJudge.returnLimit(plan.judge())) break;
            if (product.id() == null || included.contains(product.id()) || rejectedIds.contains(product.id())) {
                continue;
            }
            ranked.add(product);
            included.add(product.id());
        }

        if (ranked.isEmpty()) {
            return new JudgeRerankOutcome(List.of(), List.of(), List.of());
        }

        List<String> warnings = scores.stream()
                .flatMap(score -> score.riskFlags().stream())
                .distinct()
                .limit(8)
                .toList();
        List<String> explanations = scores.stream()
                .filter(score -> productById.containsKey(score.productId()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(10)
                .map(score -> productById.get(score.productId()).title() + "：" + String.join("；", score.reasons()))
                .filter(text -> !text.endsWith("："))
                .toList();

        return new JudgeRerankOutcome(ranked, warnings, explanations);
    }

    private ExclusionSpec collectExclusions(SemanticActionPlan plan) {
        List<String> keywords = new ArrayList<>();
        List<String> roles = new ArrayList<>();

        if (plan.exclusions() != null) {
            for (SemanticActionPlan.ExclusionRule ex : plan.exclusions()) {
                if (ex.keywords() != null) keywords.addAll(ex.keywords());
                if (ex.roles() != null) roles.addAll(ex.roles());
            }
        }
        if (isAccessoryExclusion(plan)) {
            roles.addAll(ACCESSORY_ROLES);
        }
        if (plan.negativeCriteria() != null) {
            for (SemanticActionPlan.NegativeCriterion criterion : plan.negativeCriteria()) {
                if (criterion.signals() == null || !"reject".equalsIgnoreCase(firstNonBlank(criterion.action(), "reject"))) {
                    continue;
                }
                for (SemanticActionPlan.CriterionSignal signal : criterion.signals()) {
                    if (signal.values() != null) keywords.addAll(signal.values());
                    if (signal.value() instanceof String value && !value.isBlank()) keywords.add(value);
                }
            }
        }
        return new ExclusionSpec(distinct(keywords), distinct(roles));
    }

    private boolean matchesExclusion(ProductCard product, ExclusionSpec exclusions) {
        if (!exclusions.roles().isEmpty()) {
            String role = product.productRole();
            if (role != null && exclusions.roles().contains(role.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (!exclusions.keywords().isEmpty()) {
            String text = productText(product);
            for (String keyword : exclusions.keywords()) {
                if (keyword != null && !keyword.isBlank()
                        && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private SearchFilter mergeExcludeRoles(SearchFilter filter, List<String> excludeRoles) {
        if (filter == null) filter = SearchFilter.empty();
        if (excludeRoles == null || excludeRoles.isEmpty()) return filter;
        List<String> merged = new ArrayList<>();
        if (filter.excludeRoles() != null) merged.addAll(filter.excludeRoles());
        for (String role : excludeRoles) {
            if (role != null && !role.isBlank() && !merged.contains(role)) {
                merged.add(role);
            }
        }
        return new SearchFilter(
                filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), filter.ratingMin(),
                filter.sortBy(), filter.sortOrder(), filter.keyword(),
                filter.attributes(), merged.isEmpty() ? null : merged,
                filter.capabilities());
    }

    private List<FilterTag> generateHardFilterTags(SearchFilter filter) {
        List<FilterTag> tags = new ArrayList<>();
        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) tags.add(FilterTag.ofField("≤¥" + filter.priceRange().max(), "price_range.max"));
            if (filter.priceRange().min() != null) tags.add(FilterTag.ofField("≥¥" + filter.priceRange().min(), "price_range.min"));
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
            tags.add(FilterTag.ofField("≥" + filter.ratingMin(), "rating_min"));
        }
        return tags;
    }

    private boolean hasHardFilters(SemanticActionPlan plan) {
        return plan.hardFilters() != null && !plan.hardFilters().isEmpty();
    }

    private boolean hasEvidence(SemanticActionPlan plan) {
        return (plan.semanticFilters() != null && !plan.semanticFilters().isEmpty())
                || (plan.criteria() != null && !plan.criteria().isEmpty());
    }

    private boolean hasJudge(SemanticActionPlan plan) {
        return plan.judge() != null && Boolean.TRUE.equals(plan.judge().required());
    }

    private boolean hasExclusions(SemanticActionPlan plan) {
        return (plan.exclusions() != null && !plan.exclusions().isEmpty())
                || (plan.negativeCriteria() != null && !plan.negativeCriteria().isEmpty());
    }

    private String judgeLabel(SemanticActionPlan plan) {
        if (plan.judge() != null && plan.judge().userMeaning() != null && !plan.judge().userMeaning().isBlank()) {
            return plan.judge().userMeaning();
        }
        if (plan.preferences() != null && !plan.preferences().isEmpty()) {
            return plan.preferences().stream()
                    .map(SemanticActionPlan.PreferenceRule::userMeaning)
                    .filter(Objects::nonNull)
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining("、"));
        }
        if (plan.rankingGoal() != null && !plan.rankingGoal().isBlank()) {
            return plan.rankingGoal();
        }
        return "语义偏好";
    }

    private String sanitizeCode(String value, int fallbackIndex) {
        if (value == null || value.isBlank()) {
            return String.valueOf(fallbackIndex);
        }
        String sanitized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? String.valueOf(fallbackIndex) : sanitized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private record PoolPreparation(List<ProductCard> products, SearchFilter filter, List<FilterTag> filterTags) {}

    private record ExclusionSpec(List<String> keywords, List<String> roles) {
        boolean isEmpty() {
            return keywords.isEmpty() && roles.isEmpty();
        }
    }

    private record JudgeRerankOutcome(List<ProductCard> products, List<String> warnings, List<String> explanations) {}

    private SearchFilter buildSearchFilterFromHardFilters(List<SemanticActionPlan.HardFilter> hardFilters,
                                                           SearchFilter base) {
        if (hardFilters == null || hardFilters.isEmpty()) return base;

        Double minPrice = base.priceRange() != null ? base.priceRange().min() : null;
        Double maxPrice = base.priceRange() != null ? base.priceRange().max() : null;
        List<String> platforms = base.platforms() != null ? new ArrayList<>(base.platforms()) : new ArrayList<>();
        List<String> colors = base.colors() != null ? new ArrayList<>(base.colors()) : new ArrayList<>();
        List<String> brands = base.brands() != null ? new ArrayList<>(base.brands()) : new ArrayList<>();
        Boolean selfOperated = base.selfOperated();
        Double ratingMin = base.ratingMin();
        String sortBy = base.sortBy();
        String sortOrder = base.sortOrder();

        for (SemanticActionPlan.HardFilter hf : hardFilters) {
            String field = hf.field();
            String op = hf.operator() == null ? "equals" : hf.operator();
            if (field == null) continue;

            switch (field) {
                case "price" -> {
                    Double v = toNullableDouble(hf.value());
                    if (v == null) break;
                    switch (op) {
                        case "<=", "le", "<", "lt" -> maxPrice = v;
                        case ">=", "ge", ">", "gt" -> minPrice = v;
                    }
                }
                case "platform" -> {
                    List<String> vals = toStringList(hf.value());
                    if (!vals.isEmpty()) platforms.addAll(vals);
                }
                case "color" -> {
                    List<String> vals = toStringList(hf.value());
                    if (!vals.isEmpty()) colors.addAll(vals);
                }
                case "brand" -> {
                    List<String> vals = toStringList(hf.value());
                    if (!vals.isEmpty()) brands.addAll(vals);
                }
                case "self_operated" -> selfOperated = true;
                case "rating" -> {
                    Double v = toNullableDouble(hf.value());
                    if (v != null) ratingMin = v;
                }
            }
        }

        PriceRange priceRange = (minPrice != null || maxPrice != null)
                ? new PriceRange(minPrice, maxPrice) : null;

        return new SearchFilter(priceRange, platforms.isEmpty() ? null : platforms,
                selfOperated, colors.isEmpty() ? null : colors, brands.isEmpty() ? null : brands,
                ratingMin, sortBy, sortOrder, base.keyword(), base.attributes(),
                base.excludeRoles(), base.capabilities());
    }

    /**
     * Merge sort rule from plan into SearchFilter.
     * plan.sort() takes priority over existing filter sort.
     */
    private SearchFilter mergeSort(SearchFilter filter, SemanticActionPlan.SortRule sort) {
        if (sort == null || sort.field() == null) return filter;

        return new SearchFilter(
                filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), filter.ratingMin(),
                sort.field(), sort.order() != null ? sort.order() : "desc",
                filter.keyword(), filter.attributes(),
                filter.excludeRoles(), filter.capabilities());
    }

    private SearchFilter mergeCapabilities(SearchFilter filter,
                                           List<SemanticActionPlan.SemanticFilter> semanticFilters) {
        if (semanticFilters == null || semanticFilters.isEmpty()) return filter;

        Map<String, Boolean> caps = new LinkedHashMap<>();
        if (filter.capabilities() != null) caps.putAll(filter.capabilities());
        for (SemanticActionPlan.SemanticFilter sf : semanticFilters) {
            caps.put(sf.code(), true);
        }

        return new SearchFilter(filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), filter.ratingMin(), filter.sortBy(),
                filter.sortOrder(), filter.keyword(), filter.attributes(),
                filter.excludeRoles(), caps);
    }

    private List<FilterTag> generateFilterTags(SearchFilter filter, SemanticActionPlan plan) {
        List<FilterTag> tags = new ArrayList<>();
        if (filter.priceRange() != null) {
            if (filter.priceRange().max() != null) tags.add(FilterTag.ofField("≤¥" + filter.priceRange().max(), "price_range.max"));
            if (filter.priceRange().min() != null) tags.add(FilterTag.ofField("≥¥" + filter.priceRange().min(), "price_range.min"));
        }
        if (plan.semanticFilters() != null) {
            for (SemanticActionPlan.SemanticFilter sf : plan.semanticFilters()) {
                tags.add(FilterTag.ofCapability(sf.code(), sf.userMeaning(), sf.userMeaning()));
            }
        }
        return tags;
    }

    private String buildScreeningMessage(SemanticActionPlan plan, Map<MatchBucket, Integer> counts) {
        int explicit = counts.getOrDefault(MatchBucket.EXPLICIT_MATCH, 0);
        int inferred = counts.getOrDefault(MatchBucket.INFERRED_MATCH, 0);
        int uncertain = counts.getOrDefault(MatchBucket.UNCERTAIN_RELATED, 0);

        String capability = planLabel(plan);

        StringBuilder sb = new StringBuilder();
        sb.append("已按「").append(capability).append("」筛选：");
        if (explicit > 0) sb.append(explicit).append(" 个明确符合");
        if (inferred > 0) {
            if (explicit > 0) sb.append("，");
            sb.append(inferred).append(" 个根据参数估算");
        }
        if (uncertain > 0) {
            if (explicit > 0 || inferred > 0) sb.append("，");
            sb.append(uncertain).append(" 个信息不足供参考");
        }
        sb.append("。具体以商品详情为准。");
        return sb.toString();
    }

    private String buildZeroResultMessage(SemanticActionPlan plan) {
        String capability = planLabel(plan);
        return "没有找到符合「" + capability + "」的商品，已保留原结果";
    }

    private String planLabel(SemanticActionPlan plan) {
        if (plan.semanticFilters() != null && !plan.semanticFilters().isEmpty()) {
            String label = plan.semanticFilters().get(0).userMeaning();
            if (label != null && !label.isBlank()) return label;
        }
        if (plan.criteria() != null && !plan.criteria().isEmpty()) {
            SemanticActionPlan.DynamicCriterion criterion = plan.criteria().get(0);
            return firstNonBlank(criterion.userMeaning(), criterion.name(), "条件");
        }
        return "条件";
    }

    private String productText(ProductCard product) {
        StringBuilder sb = new StringBuilder();
        if (product.title() != null) sb.append(product.title()).append(" ");
        if (product.brand() != null) sb.append(product.brand()).append(" ");
        if (product.shopName() != null) sb.append(product.shopName()).append(" ");
        if (product.tags() != null) sb.append(String.join(" ", product.tags()));
        return sb.toString().toLowerCase();
    }

    /**
     * Parse to double, returning NaN on failure instead of 0.
     * NaN prevents accidental price <= 0 filtering from dirty data.
     */
    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /**
     * Parse to nullable Double, returning null on failure.
     * Use this when 0 is a valid value and should not be confused with parse failure.
     */
    private Double toNullableDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * Convert a value to a list of strings. Handles both single strings and lists.
     * Returns empty list for null/unparseable values.
     */
    private List<String> toStringList(Object value) {
        if (value instanceof String s && !s.isBlank()) return List.of(s);
        if (value instanceof List<?> list) return list.stream()
                .map(Object::toString)
                .filter(v -> !v.isBlank())
                .toList();
        return List.of();
    }
}
