package com.visioncart.api.dto;

import java.util.List;
import java.util.Map;

public record SearchFilter(
        PriceRange priceRange,
        List<String> platforms,
        Boolean selfOperated,
        List<String> colors,
        List<String> brands,
        Double ratingMin,
        String sortBy,
        String sortOrder,
        String keyword,
        Map<String, String> attributes,
        List<String> excludeRoles,
        Map<String, Boolean> capabilities
) {
    public static SearchFilter empty() {
        return new SearchFilter(new PriceRange(null, null), List.of(), null, List.of(), List.of(), null, null, "desc", null, Map.of(), List.of(), Map.of());
    }

    // Backward-compatible constructor (without capabilities)
    public SearchFilter(PriceRange priceRange, List<String> platforms, Boolean selfOperated,
                        List<String> colors, List<String> brands, Double ratingMin,
                        String sortBy, String sortOrder, String keyword, Map<String, String> attributes,
                        List<String> excludeRoles) {
        this(priceRange, platforms, selfOperated, colors, brands, ratingMin, sortBy, sortOrder, keyword, attributes, excludeRoles, Map.of());
    }

    // Backward-compatible constructor (without excludeRoles)
    public SearchFilter(PriceRange priceRange, List<String> platforms, Boolean selfOperated,
                        List<String> colors, List<String> brands, Double ratingMin,
                        String sortBy, String sortOrder, String keyword, Map<String, String> attributes) {
        this(priceRange, platforms, selfOperated, colors, brands, ratingMin, sortBy, sortOrder, keyword, attributes, List.of(), Map.of());
    }

    // Backward-compatible constructor (without attributes and excludeRoles)
    public SearchFilter(PriceRange priceRange, List<String> platforms, Boolean selfOperated,
                        List<String> colors, List<String> brands, Double ratingMin,
                        String sortBy, String sortOrder, String keyword) {
        this(priceRange, platforms, selfOperated, colors, brands, ratingMin, sortBy, sortOrder, keyword, Map.of(), List.of(), Map.of());
    }

    /**
     * Create a copy with excludeRoles preserved from the source filter.
     */
    public static SearchFilter fromSource(SearchFilter source,
                                          PriceRange priceRange, List<String> platforms, Boolean selfOperated,
                                          List<String> colors, List<String> brands, Double ratingMin,
                                          String sortBy, String sortOrder, String keyword,
                                          Map<String, String> attributes) {
        List<String> roles = (source != null && source.excludeRoles() != null) ? source.excludeRoles() : List.of();
        Map<String, Boolean> caps = (source != null && source.capabilities() != null) ? source.capabilities() : Map.of();
        return new SearchFilter(priceRange, platforms, selfOperated, colors, brands, ratingMin,
                sortBy, sortOrder, keyword, attributes, roles, caps);
    }

    /**
     * Create a copy with excludeRoles preserved from the source filter (without attributes).
     */
    public static SearchFilter fromSource(SearchFilter source,
                                          PriceRange priceRange, List<String> platforms, Boolean selfOperated,
                                          List<String> colors, List<String> brands, Double ratingMin,
                                          String sortBy, String sortOrder, String keyword) {
        return fromSource(source, priceRange, platforms, selfOperated, colors, brands, ratingMin,
                sortBy, sortOrder, keyword, source != null ? source.attributes() : Map.of());
    }
}
