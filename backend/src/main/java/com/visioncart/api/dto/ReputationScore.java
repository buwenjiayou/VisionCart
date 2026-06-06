package com.visioncart.api.dto;

/**
 * Comprehensive reputation score for a product.
 * Decomposes the single "rating" into multi-signal reputation with confidence and explainability.
 *
 * @param score              Final weighted reputation score (0~1, higher is better)
 * @param itemRatingScore    Normalized item-level rating (0~1), only meaningful when source is item_rating
 * @param shopOrSellerScore  Normalized shop/seller reputation (0~1)
 * @param confidence         Overall confidence in the reputation data (0~1)
 * @param displayLabel       Human-readable label, e.g. "商品评分 4.8", "店铺口碑 高", "卖家信誉 98%"
 */
public record ReputationScore(
        double score,
        double itemRatingScore,
        double shopOrSellerScore,
        double confidence,
        String displayLabel
) {
    /** No reputation data available */
    public static final ReputationScore EMPTY = new ReputationScore(0, 0, 0, 0, "暂无评分");
}
