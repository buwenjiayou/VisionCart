package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;

public final class SearchTextUtils {

    private SearchTextUtils() {}

    public static String useful(String value) {
        String trimmed = StringUtils.defaultString(value).trim();
        return trimmed.isBlank()
                || "未知".equals(trimmed)
                || "未识别".equals(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)
                ? ""
                : trimmed;
    }
}
