package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FavoriteCard(
        @JsonProperty("product_id") String productId,
        String platform,
        String title,
        @JsonProperty("image_url") String imageUrl,
        BigDecimal price,
        @JsonProperty("detail_url") String detailUrl,
        @JsonProperty("updated_at") Long updatedAt,
        @JsonProperty("current_price") BigDecimal currentPrice,
        @JsonProperty("price_change") BigDecimal priceChange,
        @JsonProperty("price_lowest") Boolean priceLowest
) {
    public FavoriteCard(String productId, String platform, String title, String imageUrl,
                        BigDecimal price, String detailUrl, Long updatedAt) {
        this(productId, platform, title, imageUrl, price, detailUrl, updatedAt, null, null, null);
    }
}
