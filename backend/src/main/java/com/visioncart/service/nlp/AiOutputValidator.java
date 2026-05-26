package com.visioncart.service.nlp;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;

import java.util.List;
import java.util.Set;

public final class AiOutputValidator {

    private static final Set<String> ALLOWED_SORT_BY = Set.of("price", "sales", "rating", "reviews");
    private static final Set<String> ALLOWED_PLATFORMS = Set.of("京东", "淘宝", "天猫", "拼多多");
    private static final Set<String> ALLOWED_SORT_ORDER = Set.of("asc", "desc");
    private static final int MAX_STRING_LENGTH = 100;

    private AiOutputValidator() {}

    public static SearchFilter validate(SearchFilter filter) {
        if (filter == null) return SearchFilter.empty();

        String sortBy = filter.sortBy() != null && ALLOWED_SORT_BY.contains(filter.sortBy()) ? filter.sortBy() : null;

        String sortOrder = filter.sortOrder() != null && ALLOWED_SORT_ORDER.contains(filter.sortOrder()) ? filter.sortOrder() : "desc";

        List<String> platforms = filter.platforms() == null ? List.of()
                : filter.platforms().stream()
                        .filter(ALLOWED_PLATFORMS::contains)
                        .toList();

        Double ratingMin = filter.ratingMin();
        if (ratingMin != null && (ratingMin < 0 || ratingMin > 5)) {
            ratingMin = null;
        }

        PriceRange priceRange = filter.priceRange();
        if (priceRange != null) {
            Double min = priceRange.min();
            Double max = priceRange.max();
            if (min != null && min < 0) min = null;
            if (max != null && max < 0) max = null;
            priceRange = new PriceRange(min, max);
        }

        List<String> colors = truncateList(filter.colors());
        List<String> brands = truncateList(filter.brands());

        String keyword = truncate(filter.keyword());

        return new SearchFilter(
                priceRange,
                platforms,
                filter.selfOperated(),
                colors,
                brands,
                ratingMin,
                sortBy,
                sortOrder,
                keyword
        );
    }

    private static List<String> truncateList(List<String> items) {
        if (items == null) return List.of();
        return items.stream()
                .map(AiOutputValidator::truncate)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > MAX_STRING_LENGTH ? trimmed.substring(0, MAX_STRING_LENGTH) : trimmed;
    }
}
