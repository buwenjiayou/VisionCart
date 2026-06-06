package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record NlpParseResult(
        SearchFilter filter,
        double confidence,
        boolean fromCache,
        String decision,
        String message,
        /** LLM-detected semantic clauses (capabilities, preferences, exclusions). Empty if none detected. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DetectedClause> clauses
) {
    /** Backward-compatible constructor without clauses. */
    public NlpParseResult(SearchFilter filter, double confidence, boolean fromCache, String decision, String message) {
        this(filter, confidence, fromCache, decision, message, List.of());
    }

    /**
     * A semantic clause detected by the LLM.
     */
    public record DetectedClause(
            String type,       // "capability", "preference", "exclusion"
            String field,      // capability/preference/exclusion code
            double confidence,
            String rawText
    ) {}
}
