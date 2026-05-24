package com.visioncart.app.data.repository

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.visioncart.app.data.*
import com.visioncart.app.data.db.AppDatabase
import com.visioncart.app.data.db.FavoriteProductEntity
import com.visioncart.app.data.db.RecognitionRecordEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class VisionCartRepository(private val context: Context) {

    private val api = ApiClient.api
    private val db = AppDatabase.getDatabase(context)
    private val recognitionDao = db.recognitionRecordDao()
    private val favoriteDao = db.favoriteProductDao()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // ==================== Recognition ====================

    suspend fun analyzeImage(imageUri: Uri): Result<RecognitionResult> {
        return try {
            val file = uriToFile(imageUri)
            val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", file.name, requestFile)
            val response = api.analyzeImage(body)
            if (response.code == 200 && response.data != null) {
                // Save to local DB
                saveRecognitionToLocal(response.data, imageUri.toString())
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun correctAttribute(
        sessionId: String,
        attribute: String,
        oldValue: String,
        newValue: String
    ): Result<AttributeCorrectionResult> {
        return try {
            val response = api.correctAttributes(
                AttributeCorrectionRequest(sessionId, attribute, oldValue, newValue)
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAttributeOptions(category: String, attribute: String): Result<List<String>> {
        return try {
            val response = api.getAttributeOptions(category, attribute)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.options)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== Search ====================

    suspend fun searchProducts(request: SearchRequest): Result<SearchResult> {
        return try {
            val response = api.searchProducts(request)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== NLP ====================

    suspend fun parseNlp(request: NlpParseRequest): Result<NlpParseResult> {
        return try {
            val response = api.parseNlp(request)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== Suggestions ====================

    suspend fun getSuggestionCards(sessionId: String): Result<List<SuggestionCard>> {
        return try {
            val response = api.getSuggestionCards(sessionId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.cards)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeSuggestion(
        sessionId: String,
        action: String,
        currentProducts: List<ProductCard>? = null
    ): Result<SuggestionExecuteResult> {
        return try {
            val response = api.executeSuggestion(
                SuggestionExecuteRequest(sessionId, action, currentProducts)
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== Favorites ====================

    suspend fun addFavorite(productId: String, sessionId: String? = null): Result<Unit> {
        return try {
            val response = api.addFavorite(FavoriteRequest(productId, sessionId))
            if (response.code == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFavoritesFlow(): Flow<List<FavoriteProductEntity>> = favoriteDao.getAllFlow()

    suspend fun isFavoriteLocal(productId: String): Boolean = favoriteDao.isFavorite(productId)

    suspend fun addFavoriteLocal(product: ProductCard, sessionId: String) {
        favoriteDao.insert(
            FavoriteProductEntity(
                productId = product.id,
                platform = product.platform,
                title = product.title,
                imageUrl = product.imageUrl,
                price = product.price,
                originalPrice = product.originalPrice,
                shopName = product.shopName,
                rating = product.rating,
                sales = product.sales,
                detailUrl = product.detailUrl,
                sessionId = sessionId
            )
        )
    }

    suspend fun removeFavoriteLocal(productId: String) = favoriteDao.delete(productId)

    // ==================== History ====================

    fun getHistoryFlow(): Flow<List<RecognitionRecordEntity>> = recognitionDao.getAllFlow()

    suspend fun getHistoryFromApi(page: Int = 1, pageSize: Int = 20): Result<HistoryResult> {
        return try {
            val response = api.getHistory(page, pageSize)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== Local DB Helpers ====================

    private suspend fun saveRecognitionToLocal(result: RecognitionResult, imageUrl: String) {
        val categoryAdapter = moshi.adapter(CategoryDto::class.java)
        val attributesAdapter = moshi.adapter<Map<String, AttributeValue>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                AttributeValue::class.java
            )
        )
        recognitionDao.insert(
            RecognitionRecordEntity(
                sessionId = result.sessionId,
                imageUrl = imageUrl,
                categoryJson = categoryAdapter.toJson(result.category),
                attributesJson = attributesAdapter.toJson(result.attributes),
                keywords = result.keywords.joinToString(","),
                confidence = result.overallConfidence
            )
        )
    }

    private fun uriToFile(uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)!!
        val tempFile = File.createTempFile("visioncart_", ".jpg", context.cacheDir)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()
        return tempFile
    }
}
