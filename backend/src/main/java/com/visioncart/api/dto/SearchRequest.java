package com.visioncart.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SearchRequest(
        @Size(max = 64) String sessionId,
        @Size(max = 20) Map<@Size(max = 50) String, @Size(max = 200) String> attributes,
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
