package com.visioncart.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductCard(
        String id,
        String title,
        String imageUrl,
        BigDecimal price,
        BigDecimal originalPrice,
        String platform,
        boolean selfOperated,
        String shopName,
        double rating,
        long sales,
        double similarity,
        List<String> tags,
        String detailUrl,
        String brand,
        String ratingSource,
        String salesLabel,
        String mainCategoryCode,
        String productRole,
        /** Human-readable rating label from ProductReputationService, e.g. "淘宝 · 商品评分 4.8" */
        String ratingDisplayLabel,
        /** Unified reputation index 0~100 for frontend display ("口碑指数") */
        Integer reputationIndex,
        /** Raw reputation score 0~1 (internal, for sorting/debugging) */
        Double reputationScore,
        /** Reputation confidence 0~1 */
        Double reputationConfidence,
        /** Product-level item rating, display-only for reputation sorting. */
        Double itemRating,
        /** Shop reputation score normalized to 0~1. */
        Double shopReputationScore,
        /** Shop reputation level, e.g. high/mid/low or 高/中/低. */
        String shopReputationLevel,
        /** Seller reputation score normalized to 0~1. */
        Double sellerReputationScore,
        /** Reputation evidence: item_rating/shop_dsr/pdd_shop_level/seller/none. */
        String reputationEvidence
) {
    public ProductCard {
        tags = tags == null ? List.of() : tags;
        brand = brand != null && brand.isBlank() ? null : brand;
        ratingSource = ratingSource == null || ratingSource.isBlank() ? "none" : ratingSource;
        salesLabel = salesLabel != null && salesLabel.isBlank() ? null : salesLabel;
        mainCategoryCode = mainCategoryCode == null ? "" : mainCategoryCode;
        productRole = productRole == null || productRole.isBlank() ? "unknown" : productRole;
        shopReputationLevel = shopReputationLevel != null && shopReputationLevel.isBlank() ? null : shopReputationLevel;
        reputationEvidence = reputationEvidence == null || reputationEvidence.isBlank() ? "none" : reputationEvidence;
    }

    /** Backward-compatible full constructor without structured reputation fields. */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl, String brand,
                       String ratingSource, String salesLabel, String mainCategoryCode, String productRole,
                       String ratingDisplayLabel, Integer reputationIndex, Double reputationScore,
                       Double reputationConfidence) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, brand, ratingSource, salesLabel, mainCategoryCode, productRole,
                ratingDisplayLabel, reputationIndex, reputationScore, reputationConfidence,
                null, null, null, null, null);
    }

    /** 全量构造（不含口碑字段） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl, String brand,
                       String ratingSource, String salesLabel, String mainCategoryCode, String productRole) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, brand, ratingSource, salesLabel, mainCategoryCode, productRole, null, null, null, null);
    }

    /** 全量构造（不含结构化字段，默认 unknown） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl, String brand,
                       String ratingSource, String salesLabel) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, brand, ratingSource, salesLabel, "", "unknown", null, null, null, null);
    }

    /** 精简构造（平台搜索服务常用） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, null, "none", null, "", "unknown", null, null, null, null);
    }

    /** 返回带结构化角色的新实例 */
    public ProductCard withRole(String mainCategoryCode, String productRole) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, ratingDisplayLabel, reputationIndex, reputationScore, reputationConfidence,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence);
    }

    /** 返回相似度按平台权重加权后的新实例 */
    public ProductCard withWeightedScore(double weight) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity * weight, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, ratingDisplayLabel, reputationIndex, reputationScore, reputationConfidence,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence);
    }

    /** 返回带评分展示标签的新实例 */
    public ProductCard withRatingDisplayLabel(String label) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, label, reputationIndex, reputationScore, reputationConfidence,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence);
    }

    /** 返回带口碑指数的新实例 */
    public ProductCard withReputation(int index, double score, double confidence, String displayLabel) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, displayLabel, index, score, confidence,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence);
    }

    /** Return a new instance with structured reputation signals preserved for sorting/display. */
    public ProductCard withReputationSignals(Double itemRating, Double shopReputationScore,
                                             String shopReputationLevel, Double sellerReputationScore,
                                             String reputationEvidence) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, ratingDisplayLabel, reputationIndex, reputationScore, reputationConfidence,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence);
    }
}
