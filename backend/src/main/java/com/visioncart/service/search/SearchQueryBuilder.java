package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class SearchQueryBuilder {

    private SearchQueryBuilder() {}

    public static int platformFetchSize(int pageSize) {
        return Math.min(100, Math.max(pageSize * 3, 60));
    }

    public static List<String> taobaoQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return explicitQueries(explicit, attributes, fallback);
        }

        return precisionQueries(SearchIntent.from(attributes, filter), fallback);
    }

    public static List<String> pddQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return explicitQueries(explicit, attributes, fallback);
        }

        return precisionQueries(SearchIntent.from(attributes, filter), fallback);
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

        List<String> unique = unique(candidates);
        if (unique.isEmpty()) {
            add(unique, fallback);
        }
        return unique.stream().limit(10).toList();
    }

    public static String defaultQuery(Map<String, String> attributes, SearchFilter filter, String fallback) {
        return SearchTextUtils.searchKeyword(attributes, filter, fallback);
    }

    private static String explicitKeyword(SearchFilter filter) {
        return filter == null ? "" : SearchTextUtils.useful(filter.keyword());
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
