package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Unified action result DTO. ALL user action endpoints return this structure.
 * Android uses a single StateReducer to process this response.
 *
 * Design principles:
 * - filterApplied=false means ZeroResultGuard rolled back — Android must NOT overwrite products/filter/tags
 * - uiAction=null means it's a filter action; non-null means it's a flow action (e.g. open price alert dialog)
 * - products is always the CURRENT display list (post-filter or pre-filter on rollback)
 * - canUndo indicates whether the unified undo endpoint can be called
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResult(
        /** Current product list to display (post-filter, or preserved on rollback) */
        List<ProductCard> products,

        /** The filter state after this action (null on rollback if no previous filter) */
        SearchFilter appliedFilter,

        /** Structured filter tags for UI display (with filterPath for precise deletion) */
        List<FilterTag> filterTags,

        /** Whether the filter was actually committed (false = ZeroResultGuard rolled back) */
        boolean filterApplied,

        /** Whether previous results were preserved when no new matches found */
        boolean keptPreviousResults,

        /** Whether the user can undo this action */
        boolean canUndo,

        /** User-facing message (warnings, status, etc.) */
        String message,

        /** Detailed warnings from filter execution */
        List<String> warnings,

        /** Explanations of why products were included/excluded */
        List<String> explanations,

        /** UI action for flow actions (e.g. OPEN_PRICE_ALERT_DIALOG). Null for filter actions. */
        UiAction uiAction,

        /** Total products in the candidate pool (before filtering) */
        Integer totalInPool,

        /** Suggestion cards to display after this action */
        List<SuggestionCard> suggestionCards,

        /** Updated attributes (for correction actions) */
        Map<String, Object> updatedAttributes,

        /** Source of the action that produced this result */
        String actionSource,

        /** Undo token for the unified undo endpoint */
        String undoToken,

        /** Fallback products: previous results shown below primary for sparse results (P2-8) */
        List<ProductCard> fallbackProducts,

        /** Display mode: NORMAL, MIXED_RESULTS (primary + fallback), ROLLED_BACK */
        String displayMode,

        /** Message code for client-side i18n (e.g. "filter.applied", "filter.rollback"). Android resolves to strings.xml. */
        String messageCode,

        /** Whether recognition attributes were updated (for correction actions) */
        Boolean attributesUpdated,

        /** Whether the product list was refreshed with new results (for correction actions) */
        Boolean productsUpdated
) {
    /**
     * Backward-compatible constructor before correction attribute/product flags were added.
     */
    public ActionResult(List<ProductCard> products,
                        SearchFilter appliedFilter,
                        List<FilterTag> filterTags,
                        boolean filterApplied,
                        boolean keptPreviousResults,
                        boolean canUndo,
                        String message,
                        List<String> warnings,
                        List<String> explanations,
                        UiAction uiAction,
                        Integer totalInPool,
                        List<SuggestionCard> suggestionCards,
                        Map<String, Object> updatedAttributes,
                        String actionSource,
                        String undoToken,
                        List<ProductCard> fallbackProducts,
                        String displayMode,
                        String messageCode) {
        this(products, appliedFilter, filterTags, filterApplied, keptPreviousResults, canUndo,
                message, warnings, explanations, uiAction, totalInPool, suggestionCards, updatedAttributes,
                actionSource, undoToken, fallbackProducts, displayMode, messageCode, null, null);
    }

    /**
     * Create a filter-applied result.
     */
    public static ActionResult filtered(
            List<ProductCard> products, SearchFilter filter,
            List<FilterTag> filterTags, boolean canUndo,
            String message, List<String> warnings, List<String> explanations) {
        return new ActionResult(
                products, filter, filterTags, true, false, canUndo,
                message, warnings, explanations, null, null, null, null, null, null,
                null, "NORMAL", null, null, null
        );
    }

    /**
     * Create a filter-applied result with message code.
     */
    public static ActionResult filtered(
            List<ProductCard> products, SearchFilter filter,
            List<FilterTag> filterTags, boolean canUndo,
            String message, String messageCode,
            List<String> warnings, List<String> explanations) {
        return new ActionResult(
                products, filter, filterTags, true, false, canUndo,
                message, warnings, explanations, null, null, null, null, null, null,
                null, "NORMAL", messageCode, null, null
        );
    }

    /**
     * Create a sparse result: primary results + fallback previous products.
     * Display mode is MIXED_RESULTS — frontend shows "找到 N 个符合，下方保留原结果供参考".
     */
    public static ActionResult sparseWithFallback(
            List<ProductCard> primaryProducts, List<ProductCard> fallbackProducts,
            SearchFilter filter, List<FilterTag> filterTags, boolean canUndo,
            String message, List<String> warnings, List<String> explanations) {
        return new ActionResult(
                primaryProducts, filter, filterTags, true, false, canUndo,
                message, warnings, explanations, null,
                primaryProducts.size() + fallbackProducts.size(),
                null, null, null, null,
                fallbackProducts, "MIXED_RESULTS", null, null, null
        );
    }

    /**
     * Create a sparse result with message code.
     */
    public static ActionResult sparseWithFallback(
            List<ProductCard> primaryProducts, List<ProductCard> fallbackProducts,
            SearchFilter filter, List<FilterTag> filterTags, boolean canUndo,
            String message, String messageCode,
            List<String> warnings, List<String> explanations) {
        return new ActionResult(
                primaryProducts, filter, filterTags, true, false, canUndo,
                message, warnings, explanations, null,
                primaryProducts.size() + fallbackProducts.size(),
                null, null, null, null,
                fallbackProducts, "MIXED_RESULTS", messageCode, null, null
        );
    }

    /**
     * Create a rollback result (ZeroResultGuard rolled back).
     */
    public static ActionResult rolledBack(
            List<ProductCard> previousProducts, SearchFilter previousFilter,
            List<FilterTag> previousTags, String message, List<String> warnings) {
        return new ActionResult(
                previousProducts, previousFilter, previousTags, false, true, true,
                message, warnings, List.of(), null, null, null, null, null, null,
                null, "ROLLED_BACK", "filter.rollback", null, null
        );
    }

    /**
     * Create a flow action result (no filter change, UI action instead).
     */
    public static ActionResult flowAction(UiAction uiAction, String message) {
        return new ActionResult(
                null, null, null, false, false, false,
                message, List.of(), List.of(), uiAction, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    /**
     * Create a no-op result (empty action, pass-through).
     */
    public static ActionResult passThrough(List<ProductCard> products) {
        return new ActionResult(
                products, null, null, true, false, false,
                null, List.of(), List.of(), null, null, null, null, null, null,
                null, "NORMAL", null, null, null
        );
    }

    /**
     * UI action for flow actions that don't modify filter state.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UiAction(
            /** Action type: OPEN_PRICE_ALERT_DIALOG, OPEN_URL, etc. */
            String type,
            /** Action payload */
            Map<String, Object> payload
    ) {
        public static UiAction openPriceAlert(String productId, String productTitle, double currentPrice) {
            return new UiAction("OPEN_PRICE_ALERT_DIALOG", Map.of(
                    "productId", productId,
                    "productTitle", productTitle,
                    "currentPrice", currentPrice
            ));
        }
    }
}
