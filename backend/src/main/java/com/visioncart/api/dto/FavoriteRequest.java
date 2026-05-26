package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record FavoriteRequest(
        @NotBlank @JsonProperty("product_id") String productId,
        String platform,
        String title,
        @JsonProperty("image_url") String imageUrl,
        BigDecimal price,
        @JsonProperty("detail_url") String detailUrl,
        @JsonProperty("updated_at") Long updatedAt
) {
}
