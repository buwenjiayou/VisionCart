package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record HistoryProduct(
        @JsonProperty("product_id") String productId,
        @JsonProperty("sort_no") int sortNo,
        @JsonProperty("similarity_score") BigDecimal similarityScore,
        String title,
        @JsonProperty("cover_image") String coverImage,
        BigDecimal price,
        @JsonProperty("original_price") BigDecimal originalPrice,
        String platform,
        String brand,
        BigDecimal rating,
        Integer sales,
        @JsonProperty("detail_url") String detailUrl,
        @JsonProperty("main_category_code") String mainCategoryCode,
        @JsonProperty("product_role") String productRole
) {
}
