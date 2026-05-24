package com.visioncart.api.dto;

import java.util.List;

public record SuggestionExecuteResult(List<ProductCard> products, List<SuggestionCard> cards, String toast) {
}
