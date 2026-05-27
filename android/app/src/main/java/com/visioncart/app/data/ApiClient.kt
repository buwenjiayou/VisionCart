package com.visioncart.app.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.visioncart.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.TimeUnit

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
    val cards: List<SuggestionCard>
)

data class SuggestionExecuteRequest(
    val session_id: String,
    val action: String,
    val current_products: List<ProductCard>? = null
)

data class SuggestionExecuteResult(
    val products: List<ProductCard>,
    val cards: List<SuggestionCard>,
    val toast: String? = null
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
    val created_at: String?
)

data class PagedResult<T>(
    val total: Long,
    val page: Int,
    val size: Int,
    val items: List<T>
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

    // Recognition
    @Multipart
    @POST("/api/v1/recognition/analyze")
    suspend fun analyzeImage(@Part image: MultipartBody.Part): ApiResponse<AsyncRecognitionResponse>

    @GET("/api/v1/recognition/status/{sessionId}")
    suspend fun getRecognitionStatus(@retrofit2.http.Path("sessionId") sessionId: String): ApiResponse<RecognitionTaskResult>

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

    // Suggestions
    @GET("/api/v1/suggestions/cards")
    suspend fun getSuggestionCards(
        @Query("session_id") sessionId: String,
        @Query("client_type") clientType: String = "app"
    ): ApiResponse<SuggestionCardsResult>

    @POST("/api/v1/suggestions/execute")
    suspend fun executeSuggestion(@Body request: SuggestionExecuteRequest): ApiResponse<SuggestionExecuteResult>

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
}

// ==================== API Client ====================

object ApiClient {
    private val API_BASE_URL = BuildConfig.API_BASE_URL

    // Mutable token holder — set after login
    var authToken: String? = null

    private val authInterceptor = Interceptor { chain ->
        val original: Request = chain.request()
        val builder: Request.Builder = original.newBuilder()
        // Skip auth header for auth endpoints
        if (!original.url.encodedPath.startsWith("/api/v1/auth/")) {
            authToken?.let { token ->
                builder.addHeader("Authorization", "Bearer $token")
            }
        }
        chain.proceed(builder.build())
    }

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val api: VisionCartApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(2))
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(VisionCartApi::class.java)
    }
}
