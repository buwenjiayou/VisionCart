package com.visioncart.api.dto;

import java.util.List;

/**
 * WebSocket message for staged/progressive search results.
 * Pushed to /topic/search/{sessionId} as each platform completes.
 *
 * @param staging true 表示中间进度（结果持续优化中），false 表示最终结果
 */
public record SearchProgressMessage(
        String sessionId,
        List<ProductCard> products,
        int totalCount,
        boolean staging,
        String searchRunId
) {
    public SearchProgressMessage(String sessionId, List<ProductCard> products, int totalCount) {
        this(sessionId, products, totalCount, true, null);
    }

    public SearchProgressMessage(String sessionId, List<ProductCard> products, int totalCount, boolean staging) {
        this(sessionId, products, totalCount, staging, null);
    }
}
