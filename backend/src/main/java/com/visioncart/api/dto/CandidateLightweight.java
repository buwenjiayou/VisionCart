package com.visioncart.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight candidate for Redis session cache.
 * Contains all fields needed for filtering, sorting, and attribute matching.
 */
public record CandidateLightweight(
        String id,
        String platform,
        String title,
        String imageUrl,
        String detailUrl,
        BigDecimal price,
        BigDecimal originalPrice,
        String brand,
        double rating,
        long sales,
        boolean selfOperated,
        String shopName,
        String ratingSource,
        String salesLabel,
        String shopType,
        List<String> tags,
        String category,
        List<String> categoryPath,
        Map<String, String> attributes,
        boolean inStock,
        String normalizedText,
        double relevanceScore,
        String mainCategoryCode,
        String productRole,
        double roleConfidence,
        Double itemRating,
        Double shopReputationScore,
        String shopReputationLevel,
        Double sellerReputationScore,
        String reputationEvidence
) {
    public static CandidateLightweight from(ProductCard card, double relevanceScore,
                                             String category, List<String> categoryPath,
                                             Map<String, String> attributes, String shopType,
                                             String mainCategoryCode, String productRole,
                                             double roleConfidence) {
        String normText = buildNormalizedText(card, category, attributes);
        return new CandidateLightweight(
                card.id(),
                card.platform(),
                card.title(),
                card.imageUrl(),
                card.detailUrl(),
                card.price(),
                card.originalPrice(),
                card.brand(),
                card.rating(),
                card.sales(),
                card.selfOperated(),
                card.shopName(),
                card.ratingSource(),
                card.salesLabel(),
                shopType != null ? shopType : "unknown",
                card.tags() != null ? card.tags() : List.of(),
                category,
                categoryPath != null ? categoryPath : List.of(),
                attributes != null ? attributes : Map.of(),
                true,
                normText,
                relevanceScore,
                mainCategoryCode != null ? mainCategoryCode : "",
                productRole != null ? productRole : "unknown",
                roleConfidence,
                card.itemRating(),
                card.shopReputationScore(),
                card.shopReputationLevel(),
                card.sellerReputationScore(),
                card.reputationEvidence()
        );
    }

    public static CandidateLightweight from(ProductCard card, double relevanceScore) {
        return from(card, relevanceScore, null, null, Map.of(), null,
                card.mainCategoryCode(), card.productRole(), 0.8);
    }

    public ProductCard toProductCard() {
        List<String> mergedTags = new ArrayList<>(tags != null ? tags : List.of());
        if (category != null && !category.isBlank()) {
            mergedTags.add(category);
        }
        if (attributes != null) {
            attributes.values().stream()
                    .filter(v -> v != null && !v.isBlank())
                    .forEach(mergedTags::add);
        }
        return new ProductCard(
                id, title, imageUrl != null ? imageUrl : "",
                price, originalPrice,
                platform, selfOperated, shopName,
                rating, sales, relevanceScore,
                mergedTags, detailUrl != null ? detailUrl : "",
                brand, ratingSource != null ? ratingSource : "none", salesLabel,
                mainCategoryCode != null ? mainCategoryCode : "",
                productRole != null ? productRole : "unknown",
                null, null, null, null,
                itemRating, shopReputationScore, shopReputationLevel, sellerReputationScore, reputationEvidence
        );
    }

    private static String buildNormalizedText(ProductCard card, String category,
                                               Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.title() != null ? card.title() : "");
        if (card.brand() != null) sb.append(" ").append(card.brand());
        if (category != null) sb.append(" ").append(category);
        if (attributes != null) {
            attributes.values().forEach(v -> sb.append(" ").append(v));
        }
        if (card.tags() != null) {
            card.tags().forEach(t -> sb.append(" ").append(t));
        }
        if (card.mainCategoryCode() != null && !card.mainCategoryCode().isBlank()) {
            sb.append(" ").append(card.mainCategoryCode());
        }
        return sb.toString().toLowerCase().trim();
    }
}
