package com.visioncart.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight candidate for Redis session cache.
 * Contains all fields needed for filtering, sorting, and attribute matching.
 * Full ProductCard (with imageUrl, detailUrl) is built on-demand for frontend.
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
        String shopType,        // official / authorized / third_party / personal
        List<String> tags,
        String category,        // e.g. "剃须刀"
        List<String> categoryPath, // e.g. ["个人护理", "剃须刀", "电动剃须刀"]
        Map<String, String> attributes, // e.g. {color:black, head_count:single}
        boolean inStock,        // stock availability
        String normalizedText,  // pre-computed searchable text for fast filtering
        double relevanceScore,
        String mainCategoryCode, // normalized category code: "cup", "phone", "shoe", etc.
        String productRole,      // "main", "accessory", "consumable", "unknown"
        double roleConfidence    // confidence of role classification (0.0–1.0)
) {
    /**
     * Convert from full ProductCard to lightweight with structured role info.
     */
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
                true, // default inStock
                normText,
                relevanceScore,
                mainCategoryCode != null ? mainCategoryCode : "",
                productRole != null ? productRole : "unknown",
                roleConfidence
        );
    }

    /**
     * Convert from ProductCard (carries mainCategoryCode + productRole).
     */
    public static CandidateLightweight from(ProductCard card, double relevanceScore) {
        return from(card, relevanceScore, null, null, Map.of(), null,
                card.mainCategoryCode(), card.productRole(), 0.8);
    }

    /**
     * Convert back to ProductCard (without imageUrl/detailUrl — those come from DB/cache).
     * Merges attribute values into tags so productText() can match them for filtering.
     */
    public ProductCard toProductCard() {
        // Merge category + attribute values into tags for text-based filtering
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
                rating, sales, relevanceScore, // similarity
                mergedTags, detailUrl != null ? detailUrl : "",
                brand, ratingSource != null ? ratingSource : "none", salesLabel,
                mainCategoryCode != null ? mainCategoryCode : "",
                productRole != null ? productRole : "unknown"
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
        // Include mainCategoryCode in normalized text for NLP filtering
        if (card.mainCategoryCode() != null && !card.mainCategoryCode().isBlank()) {
            sb.append(" ").append(card.mainCategoryCode());
        }
        return sb.toString().toLowerCase().trim();
    }
}
