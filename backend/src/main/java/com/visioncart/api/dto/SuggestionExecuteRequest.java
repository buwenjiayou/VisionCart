package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SuggestionExecuteRequest(
        String sessionId,
        @NotBlank String action,
        List<ProductCard> currentProducts
) {
}
