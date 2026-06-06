package com.visioncart.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lightweight product snapshot stored in recognition history.
 * Contains display fields + ID for future refresh.
 */
public record ProductSnapshot(
        String productId,
        String title,
        String coverImage,
        BigDecimal price,
        BigDecimal originalPrice,
        String brand,
        double rating,
        long sales,
        double similarityScore,
        String platform,
        String detailUrl,
        boolean selfOperated,
        String shopName,
        List<String> tags,
        String salesLabel
) {
    public static ProductSnapshot from(ProductCard card) {
        return new ProductSnapshot(
                card.id(),
                card.title(),
                card.imageUrl(),
                card.price(),
                card.originalPrice(),
                card.brand(),
                card.rating(),
                card.sales(),
                card.similarity(),
                card.platform(),
                card.detailUrl(),
                card.selfOperated(),
                card.shopName(),
                card.tags(),
                card.salesLabel()
        );
    }
}
