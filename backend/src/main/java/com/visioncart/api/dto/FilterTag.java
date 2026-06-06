package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured filter tag for precise deletion and UI display.
 * Each tag carries its filterPath so the frontend can delete by ID
 * instead of guessing from Chinese display text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterTag(
        /** Unique tag ID, e.g. "clause-airplane_allowed", "field-colors-black" */
        String id,
        /** Human-readable label, e.g. "可带上飞机", "黑色" */
        String label,
        /** Filter path for precise deletion, e.g. "capabilities.airplane_allowed", "colors.black" */
        String filterPath,
        /** Source of this tag: "nlp", "structured", "capability", "preference", "exclusion" */
        String source,
        /** Raw user input that produced this tag, e.g. "可以带上飞机的" */
        String rawText
) {
    /**
     * Create a structured field tag (price, color, brand, platform, etc.)
     */
    public static FilterTag ofField(String label, String filterPath) {
        return new FilterTag("field-" + filterPath.replace(".", "-"), label, filterPath, "structured", null);
    }

    /**
     * Create a capability tag from a FilterClause
     */
    public static FilterTag ofCapability(String capabilityCode, String label, String rawText) {
        return new FilterTag(
                "clause-" + capabilityCode,
                label,
                "capabilities." + capabilityCode,
                "capability",
                rawText
        );
    }

    /**
     * Create a preference tag from a FilterClause
     */
    public static FilterTag ofPreference(String preferenceCode, String label, String rawText) {
        return new FilterTag(
                "clause-" + preferenceCode,
                label,
                "preferences." + preferenceCode,
                "preference",
                rawText
        );
    }

    /**
     * Create an exclusion tag from a FilterClause
     */
    public static FilterTag ofExclusion(String exclusionCode, String label, String rawText) {
        return new FilterTag(
                "clause-" + exclusionCode,
                label,
                "exclusions." + exclusionCode,
                "exclusion",
                rawText
        );
    }
}
