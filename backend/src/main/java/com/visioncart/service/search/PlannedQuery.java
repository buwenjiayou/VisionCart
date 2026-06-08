package com.visioncart.service.search;

public record PlannedQuery(String query, RetrievalLevel level) {
    public PlannedQuery {
        query = SearchTextUtils.useful(query);
        level = level == null ? RetrievalLevel.PRIMARY : level;
    }
}
