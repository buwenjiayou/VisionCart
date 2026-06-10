package com.visioncart.app.data

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.visioncart.app.BuildConfig
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ==================== Additional DTOs (not in Models.kt) ====================

data class AttributeCorrectionRequest(
    val session_id: String,
    val attribute: String,
    val old_value: String,
    val new_value: String
)

data class AttributeCorrectionResult(
    @com.squareup.moshi.Json(name = "updated_attributes")
    val updatedAttributes: Map<String, AttributeValue>,
    val products: SearchResult?
)

data class AttributeOptionsResult(
    val options: List<String>
)

data class SuggestionCardsResult(
    val cards: List<SuggestionCard>,
    @Json(name = "insight_status") val insightStatus: String? = null  // "READY" or "PENDING"
)

data class SuggestionExecuteRequest(
    val session_id: String,
    val action: String,
    val current_products: List<ProductCard>? = null,
    val current_filter: SearchFilter? = null
)

data class SuggestionExecuteResult(
    val products: List<ProductCard>,
    val cards: List<SuggestionCard>,
    val toast: String? = null,
    val updated_filter: SearchFilter? = null,
    val can_undo: Boolean = false,
    /** Whether the action was committed (false = ZeroResultGuard rolled back, kept previous results) */
    @Json(name = "filter_applied") val filter_applied: Boolean? = true
)

data class ProductSelectionRequest(
    val candidate_id: String
)

data class FavoriteRequest(
    val product_id: String,
    val platform: String? = null,
    val title: String? = null,
    val image_url: String? = null,
    val price: Double? = null,
    val detail_url: String? = null,
    val updated_at: Long? = null
)

data class FavoriteCard(
    val product_id: String,
    val platform: String?,
    val title: String?,
    val image_url: String?,
    val price: Double?,
    val detail_url: String?,
    val updated_at: Long? = null
)

data class HistoryItem(
    val session_id: String,
    val image_url: String?,
    val category: CategoryDto?,
    val attributes: Map<String, AttributeValue>?,
    val keywords: List<String>?,
    val confidence: Double?,
    val created_at: String?,
    val products: List<HistoryProduct>? = null
)

data class HistoryProduct(
    val product_id: String,
    val sort_no: Int? = null,
    val similarity_score: Double? = null,
    val title: String?,
    val cover_image: String?,
    val price: Double?,
    val original_price: Double? = null,
    val platform: String?,
    val brand: String?,
    val rating: Double? = null,
    val sales: Int? = null,
    val detail_url: String?,
    val main_category_code: String? = null,
    val product_role: String? = null
) {
    fun toProductCard(): ProductCard = ProductCard(
        id = product_id,
        title = title ?: "",
        imageUrl = cover_image ?: "",
        price = price ?: 0.0,
        originalPrice = original_price,
        platform = platform ?: "",
        brand = brand ?: "",
        shopName = "",
        rating = rating ?: 0.0,
        sales = sales?.toLong() ?: 0L,
        detailUrl = detail_url ?: "",
        similarity = similarity_score ?: 0.0,
        selfOperated = false,
        tags = emptyList(),
        mainCategoryCode = main_category_code ?: "",
        productRole = product_role ?: "main"
    )
}

data class PagedResult<T>(
    val total: Long,
    val page: Int,
    val size: Int,
    val items: List<T>
)

data class PriceAlertRequest(
    @com.squareup.moshi.Json(name = "product_id") val productId: String,
    @com.squareup.moshi.Json(name = "target_price") val targetPrice: Double
)

data class PriceAlertCard(
    @com.squareup.moshi.Json(name = "product_id") val productId: String,
    val platform: String?,
    val title: String?,
    @com.squareup.moshi.Json(name = "image_url") val imageUrl: String?,
    @com.squareup.moshi.Json(name = "target_price") val targetPrice: Double,
    @com.squareup.moshi.Json(name = "current_price") val currentPrice: Double?,
    @com.squareup.moshi.Json(name = "favorite_price") val favoritePrice: Double?,
    val active: Boolean,
    @com.squareup.moshi.Json(name = "triggered_at") val triggeredAt: String?,
    @com.squareup.moshi.Json(name = "created_at") val createdAt: String?
)

/** Response wrapper for token refresh — matches ApiResponse<LoginResponse> */
data class RefreshTokenResponse(
    val code: Int? = null,
    val data: RefreshTokenData? = null
)

data class RefreshTokenData(
    val token: String? = null,
    val refresh_token: String? = null
)

data class RefreshTokenRequest(
    @Json(name = "refresh_token") val refreshToken: String
)

// ==================== API Interface ====================

interface VisionCartApi {
    // Auth
    @POST("/api/v1/auth/send-code")
    suspend fun sendCode(@Body request: SendCodeRequest): ApiResponse<Any?>

    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: EmailLoginRequest): ApiResponse<LoginResponse>

    @GET("/api/v1/auth/profile")
    suspend fun getProfile(): ApiResponse<UserProfile>

    @POST("/api/v1/auth/logout")
    suspend fun logout(): ApiResponse<Any?>

    @POST("/api/v1/auth/refresh")
    suspend fun refreshAccessToken(@Body body: Map<String, String>): ApiResponse<LoginResponse>

    // Recognition
    @Multipart
    @POST("/api/v1/recognition/analyze")
    suspend fun analyzeImage(
        @Part image: MultipartBody.Part,
        @Part("region") region: okhttp3.RequestBody? = null
    ): ApiResponse<AsyncRecognitionResponse>

    @GET("/api/v1/recognition/status/{sessionId}")
    suspend fun getRecognitionStatus(@retrofit2.http.Path("sessionId") sessionId: String): ApiResponse<RecognitionTaskResult>

    @POST("/api/v1/recognition/{sessionId}/select-product")
    suspend fun selectRecognitionProduct(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Body request: ProductSelectionRequest
    ): ApiResponse<AsyncRecognitionResponse>

    @POST("/api/v1/recognition/{sessionId}/archive")
    suspend fun archiveSession(
        @retrofit2.http.Path("sessionId") sessionId: String
    ): ApiResponse<Any?>

    @PUT("/api/v1/recognition/attributes")
    suspend fun correctAttributes(@Body request: AttributeCorrectionRequest): ApiResponse<AttributeCorrectionResult>

    @GET("/api/v1/recognition/attribute-options")
    suspend fun getAttributeOptions(
        @Query("category") category: String,
        @Query("attribute") attribute: String,
        @Query("session_id") sessionId: String? = null
    ): ApiResponse<AttributeOptionsResult>

    // Search
    @POST("/api/v1/search/products")
    suspend fun searchProducts(@Body request: SearchRequest): ApiResponse<SearchResult>

    // NLP
    @POST("/api/v1/nlp/parse")
    suspend fun parseNlp(@Body request: NlpParseRequest): ApiResponse<NlpParseResult>

    @POST("/api/v1/nlp/filter")
    suspend fun nlpFilter(@Body request: NlpFilterRequest): ApiResponse<NlpFilterResult>

    @retrofit2.http.HTTP(method = "DELETE", path = "/api/v1/nlp/filter/{sessionId}/field/{fieldName}", hasBody = true)
    suspend fun removeFilterField(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @retrofit2.http.Path("fieldName") fieldName: String,
        @retrofit2.http.Body currentFilter: SearchFilter
    ): ApiResponse<NlpFilterResult>

    @retrofit2.http.HTTP(method = "DELETE", path = "/api/v1/nlp/filter/{sessionId}/tag/{tagId}", hasBody = true)
    suspend fun removeFilterTag(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @retrofit2.http.Path("tagId") tagId: String,
        @retrofit2.http.Body currentFilter: SearchFilter
    ): ApiResponse<NlpFilterResult>

    @retrofit2.http.DELETE("/api/v1/nlp/filter/{sessionId}")
    suspend fun clearFilters(
        @retrofit2.http.Path("sessionId") sessionId: String
    ): ApiResponse<Any>

    @POST("/api/v1/nlp/filter/{sessionId}/undo")
    suspend fun undoNlpFilter(
        @retrofit2.http.Path("sessionId") sessionId: String
    ): ApiResponse<NlpFilterResult>

    // Suggestions
    @GET("/api/v1/suggestions/cards")
    suspend fun getSuggestionCards(
        @Query("session_id") sessionId: String,
        @Query("client_type") clientType: String = "app"
    ): ApiResponse<SuggestionCardsResult>

    @POST("/api/v1/suggestions/execute")
    suspend fun executeSuggestion(@Body request: SuggestionExecuteRequest): ApiResponse<SuggestionExecuteResult>

    @POST("/api/v1/suggestions/undo")
    suspend fun undoSuggestion(
        @Query("session_id") sessionId: String,
        @Body currentProducts: List<ProductCard>? = null
    ): ApiResponse<SuggestionExecuteResult>

    @POST("/api/v1/suggestions/action")
    suspend fun executeSuggestionAction(@Body request: SuggestionExecuteRequest): ApiResponse<ActionResult>

    @POST("/api/v1/actions/execute")
    suspend fun executeUserAction(@Body request: UserActionRequest): ApiResponse<ActionResult>

    @POST("/api/v1/actions/undo")
    suspend fun undoLastAction(
        @Query("session_id") sessionId: String
    ): ApiResponse<ActionResult>

    // Favorites
    @POST("/api/v1/favorites")
    suspend fun addFavorite(@Body request: FavoriteRequest): ApiResponse<Any>

    @GET("/api/v1/favorites")
    suspend fun getFavorites(): ApiResponse<List<FavoriteCard>>

    @retrofit2.http.DELETE("/api/v1/favorites/{productId}")
    suspend fun removeFavorite(@retrofit2.http.Path("productId") productId: String): ApiResponse<Any?>

    // History
    @GET("/api/v1/history")
    suspend fun getHistory(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20
    ): ApiResponse<PagedResult<HistoryItem>>

    @retrofit2.http.DELETE("/api/v1/history/{sessionId}")
    suspend fun deleteHistory(@retrofit2.http.Path("sessionId") sessionId: String): ApiResponse<Any?>

    @GET("/api/v1/history/{sessionId}/products")
    suspend fun getHistoryProducts(@retrofit2.http.Path("sessionId") sessionId: String): ApiResponse<List<HistoryProduct>>

    // Price Alerts
    @POST("/api/v1/price-alerts")
    suspend fun createPriceAlert(@Body request: PriceAlertRequest): ApiResponse<PriceAlertCard>

    @GET("/api/v1/price-alerts")
    suspend fun getPriceAlerts(): ApiResponse<List<PriceAlertCard>>

    @retrofit2.http.DELETE("/api/v1/price-alerts/{productId}")
    suspend fun deletePriceAlert(@retrofit2.http.Path("productId") productId: String): ApiResponse<Void>
}

// ==================== API Client ====================

object ApiClient {
    private val API_BASE_URL = BuildConfig.API_BASE_URL

    // Mutable token holder — set after login
    @Volatile
    var authToken: String? = null

    @Volatile
    var refreshTokenValue: String? = null

    @Volatile
    var currentUserId: Long? = null

    /** Context for refresh token persistence — set in Application.onCreate */
    @Volatile
    var appContext: android.content.Context? = null

    private val isRefreshing = AtomicBoolean(false)

    /** Latch for non-refreshing threads to wait on; replaced on each refresh cycle */
    @Volatile
    private var refreshLatch: CountDownLatch? = null

    private val authInterceptor = Interceptor { chain ->
        val original: Request = chain.request()
        val builder: Request.Builder = original.newBuilder()
        // Skip auth header only for login/register/refresh endpoints
        val path = original.url.encodedPath
        val isPublicAuth = path == "/api/v1/auth/send-code" || path == "/api/v1/auth/login"
                || path == "/api/v1/auth/refresh"
        if (!isPublicAuth) {
            authToken?.let { token ->
                builder.addHeader("Authorization", "Bearer $token")
            }
        }
        chain.proceed(builder.build())
    }

    private val tokenAuthenticator = Authenticator { route: Route?, response: Response ->
        // Don't retry if the refresh endpoint itself failed
        if (response.request.url.encodedPath == "/api/v1/auth/refresh") {
            return@Authenticator null
        }
        // Only retry once per request
        if (responseCount(response) >= 2) {
            return@Authenticator null
        }
        val currentRefreshToken = refreshTokenValue ?: return@Authenticator null

        // Check if token was already refreshed by another thread
        val newToken = authToken
        if (newToken != null && newToken != response.request.header("Authorization")?.removePrefix("Bearer ")) {
            return@Authenticator response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }

        // Use CountDownLatch instead of Thread.sleep to avoid thread starvation
        if (!isRefreshing.compareAndSet(false, true)) {
            // Another thread is refreshing — wait on latch (max 5s)
            val latch = refreshLatch
            if (latch != null) {
                try { latch.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            }
            val afterWait = authToken
            return@Authenticator if (afterWait != null) {
                response.request.newBuilder()
                    .header("Authorization", "Bearer $afterWait")
                    .build()
            } else null
        }

        // This thread won the CAS — perform refresh
        val latch = CountDownLatch(1)
        refreshLatch = latch
        try {
            val refreshClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val body = moshi.adapter(RefreshTokenRequest::class.java)
                .toJson(RefreshTokenRequest(currentRefreshToken))
                .toRequestBody("application/json".toMediaType())
            val refreshRequest = Request.Builder()
                .url("${API_BASE_URL}api/v1/auth/refresh")
                .post(body)
                .build()
            val refreshResponse = refreshClient.newCall(refreshRequest).execute()
            if (refreshResponse.isSuccessful) {
                val responseBody = refreshResponse.body?.string()
                if (responseBody != null) {
                    // Parse with Moshi instead of fragile manual string parsing
                    val parsed = moshi.adapter(RefreshTokenResponse::class.java).fromJson(responseBody)
                    val data = parsed?.data
                    if (data != null && data.token != null) {
                        authToken = data.token
                        if (data.refresh_token != null) {
                            refreshTokenValue = data.refresh_token
                        }
                        // Persist tokens
                        appContext?.let { ctx ->
                            val uid = currentUserId
                            val email = kotlinx.coroutines.runBlocking { TokenManager.getEmail(ctx) }
                            if (uid != null && email != null) {
                                kotlinx.coroutines.runBlocking {
                                    TokenManager.saveToken(ctx, data.token, data.refresh_token, uid, email)
                                }
                            }
                        }
                        return@Authenticator response.request.newBuilder()
                            .header("Authorization", "Bearer ${data.token}")
                            .build()
                    }
                }
            }
            // Refresh failed — clear tokens, user needs to re-login
            authToken = null
            refreshTokenValue = null
            null
        } catch (e: Exception) {
            authToken = null
            refreshTokenValue = null
            null
        } finally {
            isRefreshing.set(false)
            latch.countDown()
            refreshLatch = null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(2))
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(logging)
            .build()
    }

    val api: VisionCartApi by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(VisionCartApi::class.java)
    }
}
