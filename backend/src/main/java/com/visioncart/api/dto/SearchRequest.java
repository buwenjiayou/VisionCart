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
        @Min(1) @Max(2000) Integer recallSize,
        String clientType,
        @Size(max = 20) String regionMode
) {
    public SearchRequest(String sessionId, Map<String, String> attributes, SearchFilter filter,
                         Integer page, Integer pageSize, Integer recallSize, String clientType) {
        this(sessionId, attributes, filter, page, pageSize, recallSize, clientType, null);
    }

    public SearchRequest(String sessionId, Map<String, String> attributes, SearchFilter filter,
                         Integer page, Integer pageSize, String clientType) {
        this(sessionId, attributes, filter, page, pageSize, null, clientType, null);
    }

    public SearchFilter effectiveFilter() {
        return filter == null ? SearchFilter.empty() : filter;
    }

    public int effectivePage() {
        return page == null ? 1 : Math.max(1, page);
    }

    public int effectivePageSize() {
        return pageSize == null ? 50 : Math.min(100, Math.max(1, pageSize));
    }

    public int effectiveRecallSize() {
        int base = recallSize == null ? 300 : Math.min(2000, Math.max(1, recallSize));
        return Math.max(base, effectivePageSize());
    }
}
