package com.visioncart.service.recognition;

import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.service.search.SearchTextUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecognitionSearchMapper {

    private RecognitionSearchMapper() {}

    public static Map<String, String> toSearchAttributes(RecognitionResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (result == null) {
            return attributes;
        }
        if (result.attributes() != null) {
            result.attributes().forEach((key, value) -> putIfUseful(attributes, key, value));
        }
        if (result.category() != null) {
            putIfUseful(attributes, SearchTextUtils.ATTR_CATEGORY,
                    firstUseful(result.category().level3(), result.category().level2(), result.category().level1()));
        }
        if (result.keywords() != null && !result.keywords().isEmpty()) {
            List<String> keywords = result.keywords().stream()
                    .map(SearchTextUtils::useful)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            if (!keywords.isEmpty()) {
                putIfUseful(attributes, SearchTextUtils.ATTR_KEYWORD, keywords.get(0));
                attributes.put(SearchTextUtils.ATTR_KEYWORDS, String.join(",", keywords));
            }
        }
        return attributes;
    }

    private static void putIfUseful(Map<String, String> attributes, String key, AttributeValue value) {
        if (value != null) {
            putIfUseful(attributes, key, value.value());
        }
    }

    private static void putIfUseful(Map<String, String> attributes, String key, String value) {
        String useful = SearchTextUtils.useful(value);
        if (!useful.isBlank()) {
            attributes.put(key, useful);
        }
    }

    private static String firstUseful(String... values) {
        for (String value : values) {
            String useful = SearchTextUtils.useful(value);
            if (!useful.isBlank()) {
                return useful;
            }
        }
        return "";
    }
}
