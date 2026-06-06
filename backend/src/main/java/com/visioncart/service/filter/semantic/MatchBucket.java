package com.visioncart.service.filter.semantic;

/**
 * Classification of how well a product matches a semantic filter.
 * Products are sorted into buckets during evidence-based scoring.
 *
 * Bucket hierarchy (best → worst):
 *   EXPLICIT_MATCH → INFERRED_MATCH → UNCERTAIN_RELATED → REJECTED
 */
public enum MatchBucket {

    /** Product explicitly claims the capability (e.g., "可登机", "74Wh"). High confidence. */
    EXPLICIT_MATCH,

    /** Product likely matches based on inference (e.g., 20000mAh × 3.7V = 74Wh). Medium confidence. */
    INFERRED_MATCH,

    /** Product is related but info is insufficient (e.g., "充电宝" with no capacity). Low confidence. */
    UNCERTAIN_RELATED,

    /** Product explicitly contradicts the capability (e.g., "户外电源", "220V AC"). Excluded. */
    REJECTED;

    /**
     * Score threshold for bucket classification.
     */
    public static MatchBucket fromScore(double score, boolean hasNegative) {
        if (hasNegative) return REJECTED;
        if (score >= 0.85) return EXPLICIT_MATCH;
        if (score >= 0.60) return INFERRED_MATCH;
        if (score >= 0.30) return UNCERTAIN_RELATED;
        return REJECTED;
    }
}
