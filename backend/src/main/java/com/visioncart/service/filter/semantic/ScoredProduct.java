package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.ProductCard;

import java.util.List;

/**
 * A product with its evidence-based score and bucket classification.
 * Produced by EvidenceScorer during semantic screening.
 */
public record ScoredProduct(
        /** The original product card */
        ProductCard product,
        /** Aggregate match score: 0.0 ~ 1.0 */
        double score,
        /** Bucket classification based on score and evidence */
        MatchBucket bucket,
        /** Evidence descriptions explaining why this product matches/doesn't match */
        List<String> evidence,
        /** Warnings (e.g., "按默认电压估算，需查看详情确认") */
        List<String> warnings,
        /** Which evidence rule IDs matched */
        List<String> matchedRuleIds
) {
    public static ScoredProduct explicit(ProductCard product, double score, List<String> evidence) {
        return new ScoredProduct(product, score, MatchBucket.EXPLICIT_MATCH, evidence, List.of(), List.of());
    }

    public static ScoredProduct inferred(ProductCard product, double score, List<String> evidence, String warning) {
        return new ScoredProduct(product, score, MatchBucket.INFERRED_MATCH, evidence,
                warning != null ? List.of(warning) : List.of(), List.of());
    }

    public static ScoredProduct uncertain(ProductCard product, List<String> evidence) {
        return new ScoredProduct(product, 0.3, MatchBucket.UNCERTAIN_RELATED, evidence, List.of(), List.of());
    }

    public static ScoredProduct rejected(ProductCard product, List<String> evidence) {
        return new ScoredProduct(product, 0.0, MatchBucket.REJECTED, evidence, List.of(), List.of());
    }
}
