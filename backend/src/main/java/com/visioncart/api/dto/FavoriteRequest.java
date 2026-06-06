package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FavoriteRequest(
        @NotBlank @JsonProperty("product_id") String productId,
        @NotBlank @Size(max = 32) String platform,
        @Size(max = 500) String title,
        @JsonProperty("image_url") String imageUrl,
        BigDecimal price,
        @JsonProperty("detail_url") String detailUrl,
        @JsonProperty("updated_at") Long updatedAt
) {
}
