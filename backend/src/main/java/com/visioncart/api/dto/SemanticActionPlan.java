package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * LLM-generated semantic action plan.
 * The LLM's job is to UNDERSTAND user intent and PLAN the execution,
 * NOT to directly filter products.
 *
 * Execution modes:
 * - STRICT_FILTER: simple numeric/categorical conditions → hard filter
 * - SEMANTIC_SCREENING: capability/feature → multi-evidence scoring
 * - PREFERENCE_RERANK: subjective preference → rerank without hard filter
 * - EXCLUSION: explicit negation → exclude by role/keyword
 * - NEW_SEARCH: user wants different products → new search
 * - CORRECTION: fix recognition attributes
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SemanticActionPlan(
        /** Intent: filter_current_results, new_search, correction, chat */
        String intent,

        /** Execution mode: STRICT_FILTER, SEMANTIC_SCREENING, PREFERENCE_RERANK, EXCLUSION, NEW_SEARCH, CORRECTION */
        String executionMode,

        /** Target product category (e.g., "充电宝", "手机") */
        String targetProduct,

        /** Hard numeric/categorical filters for STRICT_FILTER mode */
        List<HardFilter> hardFilters,

        /** Semantic capability filters for SEMANTIC_SCREENING mode */
        List<SemanticFilter> semanticFilters,

        /** Preference rules for PREFERENCE_RERANK mode */
        List<PreferenceRule> preferences,

        /** Exclusion rules */
        List<ExclusionRule> exclusions,

        /** Sort rule */
        SortRule sort,

        /** What to do when semantic screening yields 0 results */
        String zeroResultPolicy,

        /** Dynamic criteria generated for this specific query (evidence or preference signals). */
        List<DynamicCriterion> criteria,

        /** Dynamic negative criteria generated for this specific query. */
        List<NegativeCriterion> negativeCriteria,

        /** LLM judge/rerank plan for open-ended preferences. */
        JudgePlan judge,

        /** Plain-language ranking goal for result explanations. */
        String rankingGoal,

        /** Result handling policy for uncertainty/fallback behavior. */
        ResultStrategy resultStrategy
) {
    public SemanticActionPlan(
            String intent,
            String executionMode,
            String targetProduct,
            List<HardFilter> hardFilters,
            List<SemanticFilter> semanticFilters,
            List<PreferenceRule> preferences,
            List<ExclusionRule> exclusions,
            SortRule sort,
            String zeroResultPolicy
    ) {
        this(intent, executionMode, targetProduct, hardFilters, semanticFilters, preferences, exclusions,
                sort, zeroResultPolicy, null, null, null, null, null);
    }

    /** Simple numeric/categorical filter. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HardFilter(
            String field,
            String operator,
            Object value
    ) {}

    /** Semantic capability filter with multi-evidence scoring. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SemanticFilter(
            /** Capability code: airplane_allowed, waterproof, etc. */
            String code,
            /** What the user means in plain Chinese */
            String userMeaning,
            /** Match logic: EVIDENCE_OR_SCORE */
            String matchLogic,
            /** Positive evidence rules (OR logic — any match counts) */
            List<EvidenceRule> positiveEvidence,
            /** Negative evidence rules (any match → reject) */
            List<EvidenceRule> negativeEvidence,
            /** Policy when info is insufficient: KEEP_AS_SECONDARY, EXCLUDE, SHOW_WARNING */
            String unknownPolicy
    ) {}

    /** A single evidence rule for scoring. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceRule(
            /** Rule ID: explicit_airline_claim, wh_under_limit, etc. */
            String id,
            /** Human-readable description */
            String description,
            /** Product fields to check: title, tags, all_text, energy_wh, capacity_mah, product_role */
            List<String> fields,
            /**
             * Comparison operator:
             * - contains: single keyword in value
             * - contains_any: any keyword in values matches
             * - contains_all: all keywords in values must match
             * - regex: regex pattern in value
             * - equals, <=, >=, <, >: numeric/string comparison with value
             * - formula_lte, formula_gte: compute formula then compare with value
             */
            String operator,
            /** Threshold value (for numeric comparison or single-keyword contains) */
            Object value,
            /** Keyword list for contains_any / contains_all operators */
            List<String> values,
            /**
             * Computation formula type. Supported:
             * - ENERGY_WH_FROM_MAH_VOLTAGE: capacity_mah * voltage / 1000
             * - ENERGY_WH_FROM_MAH_DEFAULT: capacity_mah * 3.7 / 1000
             */
            String formula,
            /** Match score: 0.0 ~ 1.0 */
            double score,
            /** Match bucket: EXPLICIT_MATCH, INFERRED_MATCH, UNCERTAIN_RELATED */
            String bucket,
            /** Action on match: accept, reject, keep_secondary */
            String action,
            /** Warning to show when this evidence is the sole match */
            String warning
    ) {}

    /** Preference rule (rerank, not hard filter). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreferenceRule(
            String code,
            String userMeaning,
            double weight
    ) {}

    /** Query-specific dynamic criterion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DynamicCriterion(
            String name,
            String type,
            Boolean required,
            Double weight,
            List<CriterionSignal> signals,
            String unknownPolicy,
            String userMeaning
    ) {}

    /** Query-specific negative criterion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NegativeCriterion(
            String name,
            String action,
            List<CriterionSignal> signals,
            String userMeaning
    ) {}

    /** Signal produced by the LLM planner for dynamic evidence or judge hints. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CriterionSignal(
            String kind,
            String field,
            String operator,
            Object value,
            List<String> values,
            String formula,
            Double score,
            String description,
            String bucket
    ) {}

    /** LLM judge/rerank plan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JudgePlan(
            Boolean required,
            String userMeaning,
            List<String> positiveSignals,
            List<String> negativeSignals,
            Integer candidateLimit,
            Integer returnLimit,
            Double minScore
    ) {}

    /** Result handling strategy. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultStrategy(
            String unknownPolicy,
            String fallbackPolicy,
            Boolean preservePreviousOnEmpty
    ) {}

    /** Exclusion rule. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExclusionRule(
            String code,
            String userMeaning,
            /** Keywords or roles to exclude */
            List<String> keywords,
            /** Product roles to exclude: accessory, case, part */
            List<String> roles
    ) {}

    /** Sort rule. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SortRule(
            String field,
            String order
    ) {}
}
