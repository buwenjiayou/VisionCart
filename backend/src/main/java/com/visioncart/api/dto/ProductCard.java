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
        String salesLabel
) {
    public ProductCard {
        tags = tags == null ? List.of() : tags;
        brand = brand != null && brand.isBlank() ? null : brand;
        ratingSource = ratingSource == null || ratingSource.isBlank() ? "none" : ratingSource;
        salesLabel = salesLabel != null && salesLabel.isBlank() ? null : salesLabel;
    }

    public ProductCard(String id, String title, String imageUrl, BigDecimal price, BigDecimal originalPrice,
                       String platform, boolean selfOperated, String shopName, double rating, long sales,
                       double similarity, List<String> tags, String detailUrl) {
        this(id, title, imageUrl, price, originalPrice, platform, selfOperated, shopName, rating, sales,
                similarity, tags, detailUrl, null, "none", null);
    }
}
