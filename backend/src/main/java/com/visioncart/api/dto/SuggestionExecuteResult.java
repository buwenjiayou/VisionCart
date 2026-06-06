package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record SuggestionExecuteResult(
        List<ProductCard> products,
        SearchFilter updatedFilter,
        List<SuggestionCard> cards,
        String toast,
        boolean canUndo,
        /** Whether the action was actually committed (false = ZeroResultGuard rolled back) */
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean filterApplied
) {
    /** Legacy constructor (backward compatible — defaults filterApplied to true). */
    public SuggestionExecuteResult(List<ProductCard> products, SearchFilter updatedFilter,
                                   List<SuggestionCard> cards, String toast, boolean canUndo) {
        this(products, updatedFilter, cards, toast, canUndo, true);
    }
}
