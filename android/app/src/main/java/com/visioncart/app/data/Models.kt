package com.visioncart.app.data

import com.squareup.moshi.Json

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?
)

data class RecognitionResult(
    @Json(name = "session_id") val sessionId: String,
    val category: CategoryDto,
    val attributes: Map<String, AttributeValue>,
    val keywords: List<String>,
    @Json(name = "overall_confidence") val overallConfidence: Double,
    @Json(name = "platform_stats") val platformStats: List<PlatformPriceStat> = emptyList(),
    // Client-only field: set from RecognitionTaskResult.confidenceHint, not from backend JSON
    @Transient val confidenceHint: String? = null
)

data class CategoryDto(
    val level1: String,
    val level2: String,
    val level3: String,
    val confidence: Double
)

data class AttributeValue(
    val value: String,
    val confidence: Double,
    val verified: Boolean
)

data class SearchRequest(
    @Json(name = "session_id") val sessionId: String?,
    val attributes: Map<String, String>,
    val filter: SearchFilter = SearchFilter(),
    val page: Int = 1,
    @Json(name = "page_size") val pageSize: Int = 50,
    @Json(name = "client_type") val clientType: String = "app"
)

data class SearchFilter(
    @Json(name = "price_range") val priceRange: PriceRange = PriceRange(),
    val platforms: List<String> = emptyList(),
    @Json(name = "self_operated") val selfOperated: Boolean? = null,
    val colors: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    @Json(name = "rating_min") val ratingMin: Double? = null,
    @Json(name = "sort_by") val sortBy: String? = null,
    @Json(name = "sort_order") val sortOrder: String = "desc",
    val keyword: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    @Json(name = "exclude_roles") val excludeRoles: List<String> = emptyList(),
    val capabilities: Map<String, Boolean> = emptyMap()
)

data class PriceRange(val min: Double? = null, val max: Double? = null)

data class SearchResult(
    val total: Long,
    val products: List<ProductCard>,
    @Json(name = "platform_stats") val platformStats: List<PlatformPriceStat> = emptyList(),
    @Json(name = "suggestion_cards") val suggestionCards: List<SuggestionCard> = emptyList(),
    val relaxed: Boolean = false,
    @Json(name = "search_run_id") val searchRunId: String? = null,
    @Json(name = "in_progress") val inProgress: Boolean = false
)

data class SearchProgressMessage(
    @Json(name = "session_id") val sessionId: String? = null,
    val products: List<ProductCard> = emptyList(),
    @Json(name = "total_count") val totalCount: Int = 0,
    val staging: Boolean = true,
    @Json(name = "search_run_id") val searchRunId: String? = null
)

data class ProductCard(
    val id: String,
    val title: String,
    @Json(name = "image_url") val imageUrl: String,
    val price: Double,
    @Json(name = "original_price") val originalPrice: Double?,
    val platform: String,
    @Json(name = "self_operated") val selfOperated: Boolean,
    @Json(name = "shop_name") val shopName: String,
    val rating: Double,
    val sales: Long,
    val similarity: Double,
    val tags: List<String>,
    @Json(name = "detail_url") val detailUrl: String,
    val brand: String? = null,
    @Json(name = "rating_source") val ratingSource: String? = null,
    @Json(name = "sales_label") val salesLabel: String? = null,
    @Json(name = "main_category_code") val mainCategoryCode: String? = null,
    @Json(name = "product_role") val productRole: String? = null,
    @Json(name = "rating_display_label") val ratingDisplayLabel: String? = null,
    /** Unified reputation index 0~100 ("口碑指数") */
    @Json(name = "reputation_index") val reputationIndex: Int? = null,
    /** Raw reputation score 0~1 (internal) */
    @Json(name = "reputation_score") val reputationScore: Double? = null,
    /** Reputation confidence 0~1 */
    @Json(name = "reputation_confidence") val reputationConfidence: Double? = null,
    @Json(name = "item_rating") val itemRating: Double? = null,
    @Json(name = "shop_reputation_score") val shopReputationScore: Double? = null,
    @Json(name = "shop_reputation_level") val shopReputationLevel: String? = null,
    @Json(name = "seller_reputation_score") val sellerReputationScore: Double? = null,
    @Json(name = "reputation_evidence") val reputationEvidence: String? = null
)

data class PlatformPriceStat(
    val platform: String,
    @Json(name = "min_price") val minPrice: Double,
    @Json(name = "avg_price") val avgPrice: Double,
    val count: Long
)

data class SuggestionCard(
    val id: String,
    val title: String,
    val subtitle: String?,
    val icon: String,
    val action: String,
    val priority: Int,
    val badge: String? = null,
    val reason: String? = null,
    val metric: String? = null,
    @Json(name = "action_label") val actionLabel: String? = null,
    val tone: String? = null,
    /** FILTER_ACTION = execute filter; FLOW_ACTION = open UI flow (e.g. price alert dialog) */
    @Json(name = "action_type") val actionType: String? = null
) {
    val isFlowAction: Boolean get() = actionType == "FLOW_ACTION"
}

data class AsyncRecognitionResponse(
    @Json(name = "session_id") val sessionId: String,
    val status: String,
    @Json(name = "websocket_topic") val websocketTopic: String?,
    @Json(name = "estimated_ms") val estimatedMs: Long
)

data class RecognitionTaskResult(
    @Json(name = "session_id") val sessionId: String,
    val status: String,
    val result: RecognitionResult?,
    val error: String?,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "completed_at") val completedAt: String?,
    val candidates: List<RecognitionCandidate> = emptyList(),
    @Json(name = "progress_step") val progressStep: String? = null,
    @Json(name = "confidence_hint") val confidenceHint: String? = null
)

data class RecognitionCandidate(
    @Json(name = "candidate_id") val candidateId: String,
    val bbox: List<Int>,
    val category: String,
    val brand: String?,
    val confidence: Double,
    @Json(name = "preview_image_url") val previewImageUrl: String?
)

data class NlpContext(
    @Json(name = "product_name") val productName: String? = null,
    val category: String? = null,
    val history: List<NlpTurn>? = null
)

data class NlpTurn(
    @Json(name = "user_input") val userInput: String,
    val filter: SearchFilter? = null
)

data class NlpParseRequest(
    @Json(name = "session_id") val sessionId: String?,
    @Json(name = "user_input") val userInput: String,
    val context: NlpContext? = null
)

data class NlpParseResult(
    val filter: SearchFilter,
    val confidence: Double,
    @Json(name = "from_cache") val fromCache: Boolean,
    val decision: String,
    val message: String? = null
)

data class NlpFilterRequest(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "user_input") val userInput: String,
    val context: NlpContext? = null
)

data class NlpFilterResult(
    val products: List<ProductCard>,
    val filter: SearchFilter,
    @Json(name = "filter_tags") val filterTags: List<String>,
    @Json(name = "structured_filter_tags") val structuredFilterTags: List<FilterTag> = emptyList(),
    @Json(name = "total_in_pool") val totalInPool: Int,
    @Json(name = "result_count") val resultCount: Int,
    @Json(name = "need_expand") val needExpand: Boolean,
    @Json(name = "need_relax_hint") val needRelaxHint: Boolean,
    @Json(name = "cache_expired") val cacheExpired: Boolean,
    @Json(name = "new_search_intent") val newSearchIntent: Boolean,
    @Json(name = "new_category") val newCategory: String? = null,
    @Json(name = "keep_style_reference") val keepStyleReference: Boolean = false,
    val message: String? = null,
    // Semantic filter fields
    @Json(name = "can_undo") val canUndo: Boolean = false,
    @Json(name = "filter_applied") val filterApplied: Boolean = true,
    @Json(name = "kept_previous_results") val keptPreviousResults: Boolean = false,
    val warnings: List<String> = emptyList(),
    val explanations: List<String> = emptyList()
)

/**
 * Structured filter tag for precise deletion.
 * Each tag carries its filterPath so the frontend can delete by ID
 * instead of guessing from Chinese display text.
 */
data class FilterTag(
    val id: String,
    val label: String,
    @Json(name = "filter_path") val filterPath: String? = null,
    val source: String? = null,
    @Json(name = "raw_text") val rawText: String? = null
)

data class UserActionRequest(
    @Json(name = "action_id") val actionId: String? = null,
    val source: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "raw_text") val rawText: String? = null,
    val payload: UserActionPayload? = null,
    @Json(name = "client_request_id") val clientRequestId: String? = null
)

data class UserActionPayload(
    val action: String? = null,
    val field: String? = null,
    val value: String? = null,
    @Json(name = "tag_id") val tagId: String? = null,
    @Json(name = "filter_path") val filterPath: String? = null,
    @Json(name = "sort_by") val sortBy: String? = null,
    val context: Map<String, String>? = null,
    @Json(name = "filter_spec") val filterSpec: Map<String, Any>? = null
)

/**
 * Unified action result from backend. All user actions (NLP, suggestion,
 * correction, tag delete, undo) return this format.
 */
data class ActionResult(
    val products: List<ProductCard> = emptyList(),
    @Json(name = "applied_filter") val appliedFilter: SearchFilter? = null,
    @Json(name = "filter_tags") val filterTags: List<FilterTag> = emptyList(),
    @Json(name = "filter_applied") val filterApplied: Boolean = true,
    @Json(name = "kept_previous_results") val keptPreviousResults: Boolean = false,
    @Json(name = "can_undo") val canUndo: Boolean = false,
    val message: String? = null,
    @Json(name = "message_code") val messageCode: String? = null,
    val warnings: List<String> = emptyList(),
    val explanations: List<String> = emptyList(),
    @Json(name = "ui_action") val uiAction: UiAction? = null,
    @Json(name = "total_in_pool") val totalInPool: Int = 0,
    @Json(name = "suggestion_cards") val suggestionCards: List<SuggestionCard>? = null,
    @Json(name = "updated_attributes") val updatedAttributes: Map<String, Any>? = null,
    @Json(name = "action_source") val actionSource: String? = null,
    @Json(name = "undo_token") val undoToken: String? = null,
    @Json(name = "fallback_products") val fallbackProducts: List<ProductCard>? = null,
    @Json(name = "display_mode") val displayMode: String? = null,
    @Json(name = "attributes_updated") val attributesUpdated: Boolean? = null,
    @Json(name = "products_updated") val productsUpdated: Boolean? = null
) {
    data class UiAction(
        val type: String,
        val payload: Map<String, Any>? = null
    )

    /** Whether this is a flow action (open dialog, navigate, etc.) */
    val isFlowAction: Boolean get() = uiAction != null

    /** Whether results are sparse and fallback products should be shown */
    val isMixedResults: Boolean get() = displayMode == "MIXED_RESULTS" && !fallbackProducts.isNullOrEmpty()

    /** All display products: primary + fallback (for mixed results) */
    val allDisplayProducts: List<ProductCard>
        get() = if (isMixedResults) products + (fallbackProducts ?: emptyList()) else products
}
