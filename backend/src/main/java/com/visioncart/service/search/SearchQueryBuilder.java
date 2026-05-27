package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class SearchQueryBuilder {

    private SearchQueryBuilder() {}

    public static List<String> taobaoQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return List.of(explicit);
        }

        String brand = attr(attributes, SearchTextUtils.ATTR_BRAND);
        String color = attr(attributes, SearchTextUtils.ATTR_COLOR);
        String category = attr(attributes, SearchTextUtils.ATTR_CATEGORY);
        String keyword = attr(attributes, SearchTextUtils.ATTR_KEYWORD);

        List<String> candidates = new ArrayList<>();
        add(candidates, brand, category);
        add(candidates, color, category);
        add(candidates, keyword);
        add(candidates, category);
        add(candidates, brand, keyword);
        add(candidates, color, SearchTextUtils.coreProductToken(attributes));
        add(candidates, fallback);
        return unique(candidates);
    }

    public static List<String> pddQueries(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = explicitKeyword(filter);
        if (StringUtils.isNotBlank(explicit)) {
            return unique(List.of(explicit, SearchTextUtils.coreProductToken(explicit), fallback));
        }

        String brand = attr(attributes, SearchTextUtils.ATTR_BRAND);
        String category = attr(attributes, SearchTextUtils.ATTR_CATEGORY);
        String keyword = attr(attributes, SearchTextUtils.ATTR_KEYWORD);
        String core = SearchTextUtils.coreProductToken(attributes);

        List<String> candidates = new ArrayList<>();
        add(candidates, brand, core);
        add(candidates, keyword);
        add(candidates, category);
        add(candidates, core);
        add(candidates, fallback);
        return unique(candidates);
    }

    public static String defaultQuery(Map<String, String> attributes, SearchFilter filter, String fallback) {
        return SearchTextUtils.searchKeyword(attributes, filter, fallback);
    }

    private static String explicitKeyword(SearchFilter filter) {
        return filter == null ? "" : SearchTextUtils.useful(filter.keyword());
    }

    private static String attr(Map<String, String> attributes, String key) {
        return SearchTextUtils.useful(attributes == null ? null : attributes.get(key));
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
}
