package com.visioncart.api.dto;

import java.util.List;

public record SearchFilter(
        PriceRange priceRange,
        List<String> platforms,
        Boolean selfOperated,
        List<String> colors,
        List<String> brands,
        Double ratingMin,
        String sortBy,
        String sortOrder,
        String keyword
) {
    public static SearchFilter empty() {
        return new SearchFilter(new PriceRange(null, null), List.of(), null, List.of(), List.of(), null, null, "desc", null);
    }
}
