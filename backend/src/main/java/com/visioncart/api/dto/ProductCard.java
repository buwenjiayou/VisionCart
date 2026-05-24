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
        String detailUrl
) {
}
