package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.ProductCard;

import java.util.List;
import java.util.Map;

/**
 * Result of evidence-based semantic screening.
 * Products are sorted into buckets: explicit, inferred, uncertain, rejected.
 */
public record BucketedResult(
        /** Products that explicitly match (high confidence) */
        List<ProductCard> explicitMatches,
        /** Products that likely match via inference (medium confidence) */
        List<ProductCard> inferredMatches,
        /** Products that are related but info is insufficient (low confidence) */
        List<ProductCard> uncertainRelated,
        /** Products that were rejected (negative evidence) */
        List<ProductCard> rejected,
        /** All scored products with full details */
        List<ScoredProduct> scoredProducts,
        /** Match bucket counts for UI display */
        Map<MatchBucket, Integer> bucketCounts,
        /** Aggregate warnings */
        List<String> warnings,
        /** Aggregate explanations */
        List<String> explanations
) {
    /**
     * Get all acceptable products (explicit + inferred) for primary display.
     */
    public List<ProductCard> primaryProducts() {
        return new java.util.ArrayList<>() {{
            addAll(explicitMatches);
            addAll(inferredMatches);
        }};
    }

    /**
     * Get fallback products (uncertain) for secondary display.
     */
    public List<ProductCard> fallbackProducts() {
        return uncertainRelated;
    }

    /**
     * Whether any products matched at all.
     */
    public boolean hasAnyMatch() {
        return !explicitMatches.isEmpty() || !inferredMatches.isEmpty();
    }

    /**
     * Whether only uncertain products remain (no explicit/inferred matches).
     */
    public boolean onlyUncertain() {
        return !uncertainRelated.isEmpty() && explicitMatches.isEmpty() && inferredMatches.isEmpty();
    }
}
