package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record HistoryItem(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("image_url") String imageUrl,
        CategoryDto category,
        @JsonProperty("attributes") Map<String, AttributeValue> attributes,
        List<String> keywords,
        double confidence,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("products") List<HistoryProduct> products
) {
}
