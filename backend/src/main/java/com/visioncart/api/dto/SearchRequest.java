package com.visioncart.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

public record SearchRequest(
        String sessionId,
        Map<String, String> attributes,
        SearchFilter filter,
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer pageSize,
        String clientType
) {
    public SearchFilter effectiveFilter() {
        return filter == null ? SearchFilter.empty() : filter;
    }

    public int effectivePage() {
        return page == null ? 1 : page;
    }

    public int effectivePageSize() {
        return pageSize == null ? 20 : pageSize;
    }
}
