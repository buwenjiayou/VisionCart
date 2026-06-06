package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SuggestionExecuteRequest(
        String sessionId,
        @NotBlank String action,
        @Size(max = 100) List<ProductCard> currentProducts,
        SearchFilter currentFilter,
        /** Optional context for capability evaluation (category, source, etc.) */
        SuggestionContext context
) {
    /** Minimal context for suggestion execution. */
    public record SuggestionContext(String category, String source) {}
}
