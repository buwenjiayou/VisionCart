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
    @Json(name = "platform_stats") val platformStats: List<PlatformPriceStat> = emptyList()
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
    @Json(name = "page_size") val pageSize: Int = 20,
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
    val keyword: String? = null
)

data class PriceRange(val min: Double? = null, val max: Double? = null)

data class SearchResult(
    val total: Long,
    val products: List<ProductCard>,
    @Json(name = "platform_stats") val platformStats: List<PlatformPriceStat> = emptyList(),
    @Json(name = "suggestion_cards") val suggestionCards: List<SuggestionCard> = emptyList()
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
    @Json(name = "sales_label") val salesLabel: String? = null
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
    val priority: Int
)

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
    val candidates: List<RecognitionCandidate> = emptyList()
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
