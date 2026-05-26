package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PriceHistoryDto(
        @JsonProperty("product_id") String productId,
        String platform,
        @JsonProperty("lowest_30d") BigDecimal lowest30d,
        List<Entry> entries
) {
    public record Entry(BigDecimal price, @JsonProperty("recorded_at") Instant recordedAt) {
    }
}
