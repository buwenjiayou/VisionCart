package com.visioncart.api.dto;

import java.util.List;

public record SuggestionExecuteResult(
        List<ProductCard> products,
        SearchFilter updatedFilter,
        List<SuggestionCard> cards,
        String toast,
        boolean canUndo
) {
}
