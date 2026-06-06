package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record NlpFilterResult(
        List<ProductCard> products,
        SearchFilter filter,
        List<String> filterTags,
        /** Structured tags with filterPath for precise deletion */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FilterTag> structuredFilterTags,
        int totalInPool,
        int resultCount,
        boolean needExpand,
        boolean needRelaxHint,
        boolean cacheExpired,
        boolean newSearchIntent,
        String newCategory,
        boolean keepStyleReference,
        String message,
        // Semantic filter system fields
        boolean canUndo,
        boolean filterApplied,
        boolean keptPreviousResults,
        List<String> warnings,
        List<String> explanations
) {
    // Backward-compatible constructor (no structured tags, with semantic fields)
    public NlpFilterResult(List<ProductCard> products, SearchFilter filter, List<String> filterTags,
                           int totalInPool, int resultCount, boolean needExpand, boolean needRelaxHint,
                           boolean cacheExpired, boolean newSearchIntent, String newCategory,
                           boolean keepStyleReference, String message,
                           boolean canUndo, boolean filterApplied, boolean keptPreviousResults,
                           List<String> warnings, List<String> explanations) {
        this(products, filter, filterTags, List.of(), totalInPool, resultCount, needExpand, needRelaxHint,
                cacheExpired, newSearchIntent, newCategory, keepStyleReference, message,
                canUndo, filterApplied, keptPreviousResults, warnings, explanations);
    }

    // Old backward-compatible constructor (no structured tags, no semantic fields)
    public NlpFilterResult(List<ProductCard> products, SearchFilter filter, List<String> filterTags,
                           int totalInPool, int resultCount, boolean needExpand, boolean needRelaxHint,
                           boolean cacheExpired, boolean newSearchIntent, String newCategory,
                           boolean keepStyleReference, String message) {
        this(products, filter, filterTags, List.of(), totalInPool, resultCount, needExpand, needRelaxHint,
                cacheExpired, newSearchIntent, newCategory, keepStyleReference, message,
                false, true, false, List.of(), List.of());
    }
}
