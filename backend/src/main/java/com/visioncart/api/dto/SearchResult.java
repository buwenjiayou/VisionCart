package com.visioncart.api.dto;

import java.util.List;

public record SearchResult(
        long total,
        List<ProductCard> products,
        List<PlatformPriceStat> platformStats,
        List<SuggestionCard> suggestionCards,
        boolean relaxed,
        List<String> filterTags,
        int totalInPool,
        boolean needExpand,
        boolean needRelaxHint,
        boolean cacheExpired,
        String searchRunId,
        boolean inProgress
) {
    public SearchResult(long total, List<ProductCard> products, List<PlatformPriceStat> platformStats, List<SuggestionCard> suggestionCards) {
        this(total, products, platformStats, suggestionCards, false, List.of(), 0, false, false, false, null, false);
    }

    public SearchResult(long total, List<ProductCard> products, List<PlatformPriceStat> platformStats, List<SuggestionCard> suggestionCards, boolean relaxed) {
        this(total, products, platformStats, suggestionCards, relaxed, List.of(), 0, false, false, false, null, false);
    }

    public SearchResult(long total, List<ProductCard> products, List<PlatformPriceStat> platformStats,
                        List<SuggestionCard> suggestionCards, boolean relaxed, String searchRunId) {
        this(total, products, platformStats, suggestionCards, relaxed, List.of(), 0, false, false, false, searchRunId, false);
    }

    public SearchResult(long total, List<ProductCard> products, List<PlatformPriceStat> platformStats,
                        List<SuggestionCard> suggestionCards, boolean relaxed, String searchRunId,
                        boolean inProgress) {
        this(total, products, platformStats, suggestionCards, relaxed, List.of(), 0, false, false, false,
                searchRunId, inProgress);
    }
}
