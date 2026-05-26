package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceAlertCard(
        @JsonProperty("product_id") String productId,
        String platform,
        String title,
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("target_price") BigDecimal targetPrice,
        @JsonProperty("current_price") BigDecimal currentPrice,
        @JsonProperty("favorite_price") BigDecimal favoritePrice,
        boolean active,
        @JsonProperty("triggered_at") Instant triggeredAt,
        @JsonProperty("created_at") Instant createdAt
) {
}
