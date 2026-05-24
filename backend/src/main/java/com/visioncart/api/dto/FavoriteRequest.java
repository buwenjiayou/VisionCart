package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record FavoriteRequest(
        @NotBlank String productId,
        String sessionId,
        String platform,
        String title,
        String imageUrl,
        BigDecimal price,
        String detailUrl
) {
}
