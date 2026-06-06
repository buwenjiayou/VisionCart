package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Unified user action model. ALL user actions (NLP, suggestion, correction,
 * tag delete, sort, manual filter) are abstracted into this record
 * before entering SafeActionExecutor.
 *
 * This replaces separate request DTOs for each action type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserAction(
        /** Unique action ID (generated client-side or server-side) */
        String actionId,
        /** Action source: nlp, suggestion, correction, tag_delete, sort, manual_filter */
        String source,
        /** Session ID for filter state and undo */
        String sessionId,
        /** Raw user input (NLP query, suggestion title, correction value, etc.) */
        String rawText,
        /** Action payload — source-specific data */
        ActionPayload payload,
        /** Client request ID for deduplication */
        String clientRequestId
) {
    /**
     * Factory for NLP filter action.
     */
    public static UserAction nlpFilter(String sessionId, String userInput) {
        return new UserAction(
                "nlp-" + System.currentTimeMillis(),
                "nlp", sessionId, userInput,
                new ActionPayload(null, null, null, null, null, null, null, null),
                null
        );
    }

    /**
     * Factory for suggestion action.
     */
    public static UserAction suggestion(String sessionId, String action, String rawText) {
        return new UserAction(
                "sug-" + System.currentTimeMillis(),
                "suggestion", sessionId, rawText != null ? rawText : action,
                new ActionPayload(action, null, null, null, null, null, null, null),
                null
        );
    }

    /**
     * Factory for attribute correction action.
     */
    public static UserAction correction(String sessionId, String field, String newValue) {
        return new UserAction(
                "corr-" + System.currentTimeMillis(),
                "correction", sessionId, field + "=" + newValue,
                new ActionPayload(null, field, newValue, null, null, null, null, null),
                null
        );
    }

    /**
     * Factory for tag delete action.
     */
    public static UserAction tagDelete(String sessionId, String tagId, String filterPath) {
        return new UserAction(
                "tag-" + System.currentTimeMillis(),
                "tag_delete", sessionId, "删除标签: " + tagId,
                new ActionPayload(null, null, null, tagId, filterPath, null, null, null),
                null
        );
    }

    /**
     * Factory for sort action.
     */
    public static UserAction sort(String sessionId, String sortBy) {
        return new UserAction(
                "sort-" + System.currentTimeMillis(),
                "sort", sessionId, "排序: " + sortBy,
                new ActionPayload("sort_" + sortBy, null, null, null, null, sortBy, null, null),
                null
        );
    }

    /**
     * Factory for manual filter action (price, platform, etc.).
     */
    public static UserAction manualFilter(String sessionId, String field, Object value) {
        return new UserAction(
                "filter-" + System.currentTimeMillis(),
                "manual_filter", sessionId, "筛选: " + field,
                new ActionPayload(null, field, String.valueOf(value), null, null, null, null, null),
                null
        );
    }

    /**
     * Action payload — carries source-specific data.
     * Only the relevant fields are populated based on source.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionPayload(
            /** For suggestion: the action string (e.g. "sort_by_price_asc", "filter_brand:小米") */
            String action,
            /** For correction: the attribute field name */
            String field,
            /** For correction: the new value */
            String value,
            /** For tag_delete: the tag ID */
            String tagId,
            /** For tag_delete: the filter path for precise deletion */
            String filterPath,
            /** For sort: the sort key (price_asc, sales_desc, etc.) */
            String sortBy,
            /** For NLP: additional context (category, roles, etc.) */
            Map<String, String> context,
            /** For manual_filter: the filter specification */
            Map<String, Object> filterSpec
    ) {}
}
