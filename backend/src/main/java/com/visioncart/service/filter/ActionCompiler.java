package com.visioncart.service.filter;

import com.visioncart.api.dto.SearchFilter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compiles user action strings (from Suggestion cards, attribute corrections, etc.)
 * into FilterClause lists that can be executed by SafeActionExecutor.
 *
 * This unifies all user action sources into the same Filter DSL pipeline.
 */
@Component
public class ActionCompiler {

    private static final Set<String> ACCESSORY_ROLES = Set.of(
            "accessory", "case", "part", "consumable", "storage"
    );

    /**
     * Compile a Suggestion action string into a list of FilterClauses.
     *
     * @param action The action string (e.g. "filter_brand:小米", "sort_by_price_asc")
     * @param currentFilter The current filter state (for context)
     * @return List of FilterClauses, or empty list for no-op actions
     */
    public List<FilterClause> compile(String action, SearchFilter currentFilter) {
        if (action == null) return List.of();

        return switch (action) {
            case "sort_relevance" -> List.of(
                    FilterClause.rerank("sort-relevance", "恢复推荐排序", "sort_by", "eq", "relevance"));
            case "sort_by_price_asc" -> List.of(
                    FilterClause.rerank("sort-price-asc", "按价格排序", "sort_by", "eq", "price_asc"));
            case "sort_by_sales_desc" -> List.of(
                    FilterClause.rerank("sort-sales-desc", "按销量排序", "sort_by", "eq", "sales_desc"));
            case "sort_by_rating_desc", "sort_by_review_quality",
                    "sort_rating_desc", "sort_review_quality", "sort_rating",
                    "sort_reviews", "sort_shop_trust", "sort_seller_trust" -> List.of(
                    FilterClause.rerank("sort-review-quality", "口碑优先", "sort_by", "eq", "review_quality"));
            case "filter_self_operated" -> List.of(
                    FilterClause.structured("filter-self-operated", "自营", "self_operated", "eq", true));
            case "filter_coupon" -> List.of(
                    FilterClause.structured("filter-coupon", "优惠券", "keyword", "eq", "[coupon]"));
            case "filter_discount" -> List.of(
                    FilterClause.structured("filter-discount", "折扣", "keyword", "eq", "[discount]"));
            case "filter_by_attributes" -> List.of(
                    FilterClause.structured("filter-attributes", "有属性", "keyword", "eq", "[has_attributes]"));
            case "filter_free_shipping" -> List.of(
                    FilterClause.structured("filter-free-shipping", "包邮", "keyword", "eq", "[free_shipping]"));
            case "set_price_alert" -> List.of(); // Client-side only
            case "highlight_best_value" -> List.of(
                    FilterClause.rerank("sort-best-value", "性价比排序", "sort_by", "eq", "value_score"));
            case "filter_main_product" -> List.of(
                    FilterClause.exclusion("exclude-accessory", "排除配件", "exclude_roles", ACCESSORY_ROLES));
            default -> compilePrefixAction(action);
        };
    }

    /**
     * Check if an action is a semantic action (needs capability/preference evaluation)
     * vs a structural action (hard filter on price/platform/etc).
     */
    public boolean isSemanticAction(String action) {
        if (action == null) return false;
        // These actions involve text matching or subjective judgment
        return action.startsWith("filter_keyword:")
                || action.startsWith("filter_brand:")
                || action.startsWith("filter_color:")
                || action.equals("filter_coupon")
                || action.equals("filter_discount")
                || action.equals("filter_by_attributes")
                || action.equals("filter_free_shipping")
                || action.equals("filter_main_product");
    }

    /**
     * Check if an action is a no-op (client-side only, no server filtering needed).
     */
    public boolean isNoOpAction(String action) {
        return "set_price_alert".equals(action);
    }

    /**
     * Build a SearchFilter from an action string, preserving all existing filter fields.
     * This is used to construct the tentative filter for SafeActionExecutor.
     */
    public SearchFilter applyActionToFilter(String action, SearchFilter currentFilter) {
        if (action == null || currentFilter == null) return currentFilter;

        return switch (action) {
            case "sort_relevance" -> applySort(currentFilter, null, null);
            case "sort_by_price_asc" -> applySort(currentFilter, "price", "asc");
            case "sort_by_sales_desc" -> applySort(currentFilter, "sales", "desc");
            case "sort_by_rating_desc", "sort_by_review_quality",
                    "sort_rating_desc", "sort_review_quality", "sort_rating",
                    "sort_reviews", "sort_shop_trust", "sort_seller_trust" -> applySort(currentFilter, "review_quality", "desc");
            case "filter_self_operated" -> applySelfOperated(currentFilter, true);
            case "filter_coupon" -> applyKeyword(currentFilter, "[coupon]");
            case "filter_discount" -> applyKeyword(currentFilter, "[discount]");
            case "filter_by_attributes" -> applyKeyword(currentFilter, "[has_attributes]");
            case "filter_free_shipping" -> applyKeyword(currentFilter, "[free_shipping]");
            case "highlight_best_value" -> applySort(currentFilter, "value_score", "desc");
            case "filter_main_product" -> applyExcludeRoles(currentFilter, ACCESSORY_ROLES);
            case "set_price_alert" -> currentFilter;
            default -> applyPrefixActionToFilter(action, currentFilter);
        };
    }

    private List<FilterClause> compilePrefixAction(String action) {
        List<FilterClause> clauses = new ArrayList<>();

        if (action.startsWith("filter_brand:")) {
            String brand = action.substring("filter_brand:".length()).trim();
            if (!brand.isEmpty()) {
                clauses.add(FilterClause.structured("filter-brand-" + brand,
                        brand, "brand", "contains", brand));
            }
        } else if (action.startsWith("filter_color:")) {
            String color = action.substring("filter_color:".length()).trim();
            if (!color.isEmpty()) {
                clauses.add(FilterClause.structured("filter-color-" + color,
                        color, "color", "contains", color));
            }
        } else if (action.startsWith("filter_platform:")) {
            String platform = action.substring("filter_platform:".length()).trim();
            if (!platform.isEmpty()) {
                clauses.add(FilterClause.structured("filter-platform-" + platform,
                        platform, "platform", "eq", platform));
            }
        } else if (action.startsWith("filter_budget_under:")) {
            String limitStr = action.substring("filter_budget_under:".length()).trim();
            try {
                double limit = Double.parseDouble(limitStr);
                clauses.add(FilterClause.structured("filter-budget-under",
                        "≤¥" + limitStr, "price_max", "lte", limit));
            } catch (NumberFormatException ignored) {}
        } else if (action.startsWith("filter_price_band:")) {
            String band = action.substring("filter_price_band:".length()).trim();
            String[] parts = band.split(":");
            if (parts.length == 2) {
                try {
                    double min = Double.parseDouble(parts[0]);
                    double max = Double.parseDouble(parts[1]);
                    clauses.add(FilterClause.structured("filter-price-band-min",
                            "¥" + parts[0] + "～¥" + parts[1], "price_min", "gte", min));
                    clauses.add(FilterClause.structured("filter-price-band-max",
                            "¥" + parts[0] + "～¥" + parts[1], "price_max", "lte", max));
                } catch (NumberFormatException ignored) {}
            }
        } else if (action.startsWith("filter_keyword:")) {
            String keyword = action.substring("filter_keyword:".length()).trim();
            if (!keyword.isEmpty()) {
                if ("[exclude_accessory]".equals(keyword)) {
                    // Special: exclude low-similarity items
                    clauses.add(FilterClause.exclusion("exclude-low-similarity",
                            "排除疑似配件", "similarity", "lt:0.3"));
                } else {
                    clauses.add(FilterClause.structured("filter-keyword-" + keyword,
                            keyword, "keyword", "contains", keyword));
                }
            }
        }

        return clauses;
    }

    private SearchFilter applyPrefixActionToFilter(String action, SearchFilter currentFilter) {
        if (action.startsWith("filter_brand:")) {
            String brand = action.substring("filter_brand:".length()).trim();
            return applyBrand(currentFilter, brand);
        }
        if (action.startsWith("filter_color:")) {
            String color = action.substring("filter_color:".length()).trim();
            return applyColor(currentFilter, color);
        }
        if (action.startsWith("filter_platform:")) {
            String platform = action.substring("filter_platform:".length()).trim();
            return applyPlatform(currentFilter, platform);
        }
        if (action.startsWith("filter_budget_under:")) {
            String limitStr = action.substring("filter_budget_under:".length()).trim();
            try {
                double limit = Double.parseDouble(limitStr);
                return applyPriceMax(currentFilter, limit);
            } catch (NumberFormatException ignored) {}
        }
        if (action.startsWith("filter_price_band:")) {
            String band = action.substring("filter_price_band:".length()).trim();
            String[] parts = band.split(":");
            if (parts.length == 2) {
                try {
                    double min = Double.parseDouble(parts[0]);
                    double max = Double.parseDouble(parts[1]);
                    return applyPriceRange(currentFilter, min, max);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (action.startsWith("filter_keyword:")) {
            String keyword = action.substring("filter_keyword:".length()).trim();
            return applyKeyword(currentFilter, keyword);
        }
        return currentFilter;
    }

    // --- Filter helpers (same logic as SuggestionService but standalone) ---

    private SearchFilter applySort(SearchFilter filter, String sortBy, String sortOrder) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                sortBy, sortBy == null ? "desc" : (sortOrder == null || sortOrder.isBlank() ? "desc" : sortOrder),
                base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applySelfOperated(SearchFilter filter, boolean selfOperated) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), selfOperated,
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyBrand(SearchFilter filter, String brand) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), appendUnique(base.brands(), brand), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyColor(SearchFilter filter, String color) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                appendUnique(base.colors(), color), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyPlatform(SearchFilter filter, String platform) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), appendUnique(base.platforms(), platform), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyPriceMax(SearchFilter filter, double max) {
        SearchFilter base = safe(filter);
        var current = base.priceRange() == null ? new com.visioncart.api.dto.PriceRange(null, null) : base.priceRange();
        return new SearchFilter(new com.visioncart.api.dto.PriceRange(current.min(), max),
                safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyPriceRange(SearchFilter filter, double min, double max) {
        SearchFilter base = safe(filter);
        return new SearchFilter(new com.visioncart.api.dto.PriceRange(min, max),
                safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyKeyword(SearchFilter filter, String keyword) {
        SearchFilter base = safe(filter);
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), keyword,
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                safeList(base.excludeRoles()),
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private SearchFilter applyExcludeRoles(SearchFilter filter, Set<String> roles) {
        SearchFilter base = safe(filter);
        List<String> merged = new ArrayList<>(safeList(base.excludeRoles()));
        for (String role : roles) {
            if (merged.stream().noneMatch(r -> r.equalsIgnoreCase(role))) {
                merged.add(role);
            }
        }
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? java.util.Map.of() : base.attributes(),
                merged,
                base.capabilities() == null ? java.util.Map.of() : base.capabilities());
    }

    private List<String> appendUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) return safeList(values);
        List<String> result = new ArrayList<>(safeList(values));
        boolean exists = result.stream().anyMatch(v -> v.equalsIgnoreCase(value));
        if (!exists) result.add(value);
        return result;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private SearchFilter safe(SearchFilter filter) {
        return filter == null ? SearchFilter.empty() : filter;
    }
}
