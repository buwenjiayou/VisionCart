package com.visioncart.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Complete filter state: separates filters (what to show) from sort (how to order).
 * Includes version for optimistic concurrency control.
 */
public record FilterState(
        SearchFilter filters,
        SortState sort,
        int version
) {
    public record SortState(String sortBy, String sortOrder) {
        public static SortState empty() {
            return new SortState(null, "desc");
        }

        public static SortState relevance() {
            return new SortState(null, "desc");
        }
    }

    public static FilterState empty() {
        return new FilterState(SearchFilter.empty(), SortState.empty(), 0);
    }

    /**
     * Create a new version with incremented version number.
     */
    public FilterState nextVersion() {
        return new FilterState(this.filters, this.sort, this.version + 1);
    }

    /**
     * Create a new version with updated filters.
     */
    public FilterState withFilters(SearchFilter newFilters) {
        return new FilterState(newFilters, this.sort, this.version + 1);
    }

    /**
     * Create a new version with updated sort.
     */
    public FilterState withSort(SortState newSort) {
        return new FilterState(this.filters, newSort, this.version + 1);
    }

    /**
     * Convert to a single SearchFilter for backward compatibility with existing filter pipeline.
     */
    public SearchFilter toSearchFilter() {
        return new SearchFilter(
                filters.priceRange(),
                filters.platforms(),
                filters.selfOperated(),
                filters.colors(),
                filters.brands(),
                filters.ratingMin(),
                sort != null ? sort.sortBy() : null,
                sort != null ? sort.sortOrder() : "desc",
                filters.keyword(),
                filters.attributes(),
                filters.excludeRoles() != null ? filters.excludeRoles() : List.of(),
                filters.capabilities() != null ? filters.capabilities() : Map.of()
        );
    }
}
