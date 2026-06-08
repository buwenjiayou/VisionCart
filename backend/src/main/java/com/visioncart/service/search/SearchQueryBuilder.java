package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class SearchQueryBuilder {

    /** attributes 中注入的 QueryPlan 查询列表 key（JSON 数组） */
    public static final String ATTR_PLAN_QUERIES = "__plan_queries";

    private SearchQueryBuilder() {}

    public static int platformFetchSize(int pageSize) {
        return Math.min(100, Math.max(pageSize * 4, 90));
    }

    public static List<String> taobaoQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return explicitQueries(explicit, attributes, fallback);
        }

        // 优先使用 QueryPlan 注入的查询
        List<String> planQueries = extractPlanQueries(attributes);
        if (planQueries != null && !planQueries.isEmpty()) {
            return planQueries;
        }

        return precisionQueries(SearchIntent.from(attributes, filter), fallback);
    }

    public static List<String> pddQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return explicitQueries(explicit, attributes, fallback);
        }

        // 优先使用 QueryPlan 注入的查询
        List<String> planQueries = extractPlanQueries(attributes);
        if (planQueries != null && !planQueries.isEmpty()) {
            return planQueries;
        }

        return precisionQueries(SearchIntent.from(attributes, filter), fallback);
    }

    /**
     * 从 attributes 中提取 QueryPlan 注入的查询列表。
     * 查询列表以 JSON 数组形式存储在 {@link #ATTR_PLAN_QUERIES} key 中。
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractPlanQueries(Map<String, String> attributes) {
        String json = attributes == null ? null : attributes.get(ATTR_PLAN_QUERIES);
        if (json == null || json.isBlank()) return null;
        try {
            List<String> queries = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
            return queries.stream()
                    .map(SearchTextUtils::useful)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .limit(6)
                    .toList();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> precisionQueries(SearchIntent intent, String fallback) {
        List<String> candidates = new ArrayList<>();
        String brand = intent.brand();
        String category = intent.category();
        String core = intent.coreProduct();

        for (String exact : intent.exactTerms()) {
            add(candidates, brand, exact, core);
            add(candidates, brand, exact, category);
            add(candidates, exact, core);
        }
        for (String keyword : intent.keywords()) {
            add(candidates, keyword);
            if (!SearchTextUtils.containsNormalized(keyword, brand)) {
                add(candidates, brand, keyword);
            }
        }
        add(candidates, brand, core);
        add(candidates, brand, category);
        add(candidates, core, first(intent.descriptiveTerms()));
        add(candidates, category, first(intent.descriptiveTerms()));

        // 确保修饰词出现在查询中（如"电动"、"无线"、"智能"）
        for (String modifier : intent.modifierTerms()) {
            add(candidates, modifier, core);
            if (!modifier.equals(core)) {
                add(candidates, modifier, category);
            }
        }

        List<String> unique = unique(candidates);
        if (unique.isEmpty()) {
            add(unique, fallback);
        }
        return unique.stream().limit(6).toList();
    }

    public static String defaultQuery(Map<String, String> attributes, SearchFilter filter, String fallback) {
        return SearchTextUtils.searchKeyword(attributes, filter, fallback);
    }

    private static String explicitKeyword(SearchFilter filter) {
        return filter == null ? "" : SearchTextUtils.positiveKeyword(filter.keyword());
    }

    private static List<String> explicitQueries(String explicit, Map<String, String> attributes, String fallback) {
        SearchIntent intent = SearchIntent.from(attributes, null);
        List<String> candidates = new ArrayList<>();
        if (!SearchTextUtils.containsNormalized(explicit, intent.brand())) {
            add(candidates, intent.brand(), explicit);
        }
        add(candidates, explicit);
        add(candidates, explicit, intent.coreProduct());
        add(candidates, fallback);
        return unique(candidates).stream().limit(4).toList();
    }

    private static void add(List<String> candidates, String... parts) {
        String joined = String.join(" ", java.util.Arrays.stream(parts)
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .toList());
        if (StringUtils.isNotBlank(joined)) {
            candidates.add(joined);
        }
    }

    private static List<String> unique(List<String> candidates) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        candidates.stream()
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .forEach(unique::add);
        return new ArrayList<>(unique);
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }
}
