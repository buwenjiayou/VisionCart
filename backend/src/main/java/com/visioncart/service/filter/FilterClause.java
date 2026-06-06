package com.visioncart.service.filter;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single filter clause in the semantic filter DSL.
 * Each clause represents one semantic condition extracted from natural language.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterClause(
    String id,
    String rawText,
    ClauseType type,
    String field,
    String operator,
    Object value,
    ApplyMode applyMode,
    double confidence,
    String categoryScope,
    FallbackPolicy fallbackPolicy
) {
    public enum ClauseType {
        /** Exact structured condition: price <= 100, color = black, platform = taobao */
        STRUCTURED,
        /** Product attribute match: stainless steel, 500ml, Type-C */
        ATTRIBUTE,
        /** Inferred capability: airplane_allowed, waterproof, fast_charging */
        CAPABILITY,
        /** Soft preference for reranking: cost_effective, lightweight, gift_friendly */
        PREFERENCE,
        /** Exclusion condition: no accessories, no counterfeits */
        EXCLUSION
    }

    public enum ApplyMode {
        /** Deterministic filter — price, platform, rating. Safe to hard-filter. */
        HARD_FILTER,
        /** Capability-based filter — trial first, rollback if too few results. */
        SAFE_FILTER,
        /** Preference-based reranking — never removes products, only reorders. */
        RERANK,
        /** Exclusion — removes products but with fallback to demote if too aggressive. */
        EXCLUSION,
        /** Explain only — shows info to user but does not filter. */
        EXPLAIN_ONLY
    }

    public enum FallbackPolicy {
        /** If this clause produces 0 results, keep the previous product list. */
        KEEP_PREVIOUS_IF_EMPTY,
        /** If too few results, downgrade this clause from filter to rerank. */
        RERANK_IF_LOW_RESULT,
        /** No fallback — this clause is strict. */
        NONE
    }

    public static FilterClause structured(String id, String rawText, String field, String op, Object value) {
        return new FilterClause(id, rawText, ClauseType.STRUCTURED, field, op, value,
                ApplyMode.HARD_FILTER, 1.0, null, FallbackPolicy.NONE);
    }

    public static FilterClause capability(String id, String rawText, String field, double confidence) {
        return new FilterClause(id, rawText, ClauseType.CAPABILITY, field, "infer", true,
                ApplyMode.SAFE_FILTER, confidence, null, FallbackPolicy.KEEP_PREVIOUS_IF_EMPTY);
    }

    public static FilterClause preference(String id, String rawText, String field) {
        return new FilterClause(id, rawText, ClauseType.PREFERENCE, field, "rerank", true,
                ApplyMode.RERANK, 0.7, null, FallbackPolicy.RERANK_IF_LOW_RESULT);
    }

    public static FilterClause exclusion(String id, String rawText, String field, Object value) {
        return new FilterClause(id, rawText, ClauseType.EXCLUSION, field, "exclude", value,
                ApplyMode.EXCLUSION, 0.8, null, FallbackPolicy.RERANK_IF_LOW_RESULT);
    }

    /** Sort/rerank clause — never removes products, only reorders. */
    public static FilterClause rerank(String id, String rawText, String field, String op, Object value) {
        return new FilterClause(id, rawText, ClauseType.STRUCTURED, field, op, value,
                ApplyMode.RERANK, 1.0, null, FallbackPolicy.NONE);
    }
}
