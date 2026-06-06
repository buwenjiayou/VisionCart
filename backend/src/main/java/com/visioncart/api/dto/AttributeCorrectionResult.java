package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttributeCorrectionResult(
        Map<String, AttributeValue> updatedAttributes,
        SearchResult products,
        boolean correctionApplied,
        /** Whether the re-search returned new (different) products */
        boolean productsUpdated,
        /** Whether the previous product list was kept because re-search returned empty */
        boolean keptPreviousResults,
        String message,
        String failedField,
        String failedValue,
        /** Message code for i18n */
        String messageCode,
        /** Snapshot of attributes BEFORE correction (for undo) */
        Map<String, AttributeValue> previousAttributes
) {
    // Backward-compatible constructor
    public AttributeCorrectionResult(Map<String, AttributeValue> updatedAttributes, SearchResult products) {
        this(updatedAttributes, products, true, false, false, null, null, null, null, null);
    }

    // Convenience constructor (old 6-arg signature)
    public AttributeCorrectionResult(Map<String, AttributeValue> updatedAttributes, SearchResult products,
                                     boolean correctionApplied, String message,
                                     String failedField, String failedValue) {
        this(updatedAttributes, products, correctionApplied, false, false, message, failedField, failedValue, null, null);
    }
}
