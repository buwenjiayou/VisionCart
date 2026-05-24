package com.visioncart.api.dto;

import java.util.List;

public record SearchResult(
        long total,
        List<ProductCard> products,
        List<PlatformPriceStat> platformStats,
        List<SuggestionCard> suggestionCards
) {
}
