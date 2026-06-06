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
        String ratingDisplayLabel
) {
    public ProductCard {
        tags = tags == null ? List.of() : tags;
        brand = brand != null && brand.isBlank() ? null : brand;
        ratingSource = ratingSource == null || ratingSource.isBlank() ? "none" : ratingSource;
        salesLabel = salesLabel != null && salesLabel.isBlank() ? null : salesLabel;
        mainCategoryCode = mainCategoryCode == null ? "" : mainCategoryCode;
        productRole = productRole == null || productRole.isBlank() ? "unknown" : productRole;
    }

    /** 全量构造（不含 ratingDisplayLabel） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl, String brand,
                       String ratingSource, String salesLabel, String mainCategoryCode, String productRole) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, brand, ratingSource, salesLabel, mainCategoryCode, productRole, null);
    }

    /** 全量构造（不含结构化字段，默认 unknown） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl, String brand,
                       String ratingSource, String salesLabel) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, brand, ratingSource, salesLabel, "", "unknown", null);
    }

    /** 精简构造（平台搜索服务常用） */
    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, null, "none", null, "", "unknown", null);
    }

    /** 返回带结构化角色的新实例 */
    public ProductCard withRole(String mainCategoryCode, String productRole) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, ratingDisplayLabel);
    }

    /** 返回相似度按平台权重加权后的新实例 */
    public ProductCard withWeightedScore(double weight) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity * weight, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, ratingDisplayLabel);
    }

    /** 返回带评分展示标签的新实例 */
    public ProductCard withRatingDisplayLabel(String label) {
        return new ProductCard(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName,
                rating, sales, similarity, tags, detailUrl, brand, ratingSource, salesLabel,
                mainCategoryCode, productRole, label);
    }
}
