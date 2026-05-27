package com.visioncart.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
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
import java.io.ByteArrayOutputStream
import java.io.File

class VisionCartRepository(private val context: Context) {

    private val api = ApiClient.api
    private val db = AppDatabase.getDatabase(context)
    private val recognitionDao = db.recognitionRecordDao()
    private val favoriteDao = db.favoriteProductDao()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private companion object {
        const val TAG = "VisionCartRepository"
        const val MAX_UPLOAD_DIMENSION = 1280
        const val MAX_UPLOAD_BYTES = 1_500_000
        const val INITIAL_JPEG_QUALITY = 88
        const val MIN_JPEG_QUALITY = 68
    }

    // ==================== Recognition ====================

    suspend fun analyzeImage(imageUri: Uri): Result<RecognitionResult> {
        return try {
            val file = uriToFile(imageUri)
            analyzeImageFile(file, imageUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare image for recognition: $imageUri", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeImageFile(file: File, imageUrl: String? = null): Result<RecognitionResult> {
        return try {
            Log.i(TAG, "Uploading image for recognition: name=${file.name}, size=${file.length()}")
            val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", file.name, requestFile)
            val response = api.analyzeImage(body)
            if (response.code != 200 || response.data == null) {
                Log.w(TAG, "Recognition upload rejected: code=${response.code}, message=${response.message}")
                return Result.failure(Exception(response.message))
            }

            val sessionId = response.data.sessionId
            Log.i(TAG, "Recognition task accepted: sessionId=$sessionId")
            // Poll for result
            val result = pollRecognitionResult(sessionId)
            // Save to local DB
            saveRecognitionToLocal(result, imageUrl ?: file.toURI().toString())
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Recognition request failed for file=${file.name}, size=${file.length()}", e)
            Result.failure(e)
        }
    }

    private suspend fun pollRecognitionResult(sessionId: String): RecognitionResult {
        val maxAttempts = 30 // 30 * 2s = 60s max
        val intervalMs = 2000L
        for (i in 0 until maxAttempts) {
            val statusResponse = api.getRecognitionStatus(sessionId)
            if (statusResponse.code == 200 && statusResponse.data != null) {
                val task = statusResponse.data
                Log.d(TAG, "Recognition poll: sessionId=$sessionId, attempt=${i + 1}, status=${task.status}")
                when (task.status) {
                    "COMPLETED" -> {
                        if (task.result != null) return task.result
                        throw Exception("识别结果为空")
                    }
                    "FAILED" -> throw Exception(task.error ?: "识别失败")
                    else -> {
                        // PROCESSING or PENDING, continue polling
                    }
                }
            } else {
                Log.w(
                    TAG,
                    "Recognition poll rejected: sessionId=$sessionId, attempt=${i + 1}, code=${statusResponse.code}, message=${statusResponse.message}"
                )
            }
            kotlinx.coroutines.delay(intervalMs)
        }
        throw Exception("识别超时")
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

    suspend fun getAttributeOptions(category: String, attribute: String, sessionId: String? = null): Result<List<String>> {
        return try {
            val response = api.getAttributeOptions(category, attribute, sessionId)
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

    suspend fun addFavorite(product: ProductCard): Result<Unit> {
        return try {
            val response = api.addFavorite(FavoriteRequest(
                product_id = product.id,
                platform = product.platform,
                title = product.title,
                image_url = product.imageUrl,
                price = product.price,
                detail_url = product.detailUrl,
                updated_at = System.currentTimeMillis()
            ))
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

    suspend fun removeFavoriteBackend(productId: String): Result<Unit> {
        return try {
            val response = api.removeFavorite(productId)
            if (response.code == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncFavoritesFromBackend() {
        try {
            val response = api.getFavorites()
            if (response.code == 200 && response.data != null) {
                val localItems = favoriteDao.getAll()
                val localMap = localItems.associateBy { it.productId }

                // Upsert backend items into local, preserving local data for fields not returned by backend
                for (card in response.data) {
                    val existing = localMap[card.product_id]
                    favoriteDao.insert(
                        FavoriteProductEntity(
                            productId = card.product_id,
                            platform = card.platform ?: existing?.platform ?: "",
                            title = card.title ?: existing?.title ?: "",
                            imageUrl = card.image_url ?: existing?.imageUrl ?: "",
                            price = card.price ?: existing?.price ?: 0.0,
                            originalPrice = existing?.originalPrice,
                            shopName = card.platform ?: existing?.shopName ?: "收藏商品",
                            rating = existing?.rating ?: 0.0,
                            sales = existing?.sales ?: 0L,
                            detailUrl = card.detail_url ?: existing?.detailUrl ?: "",
                            sessionId = existing?.sessionId ?: "",
                            updatedAt = card.updated_at ?: System.currentTimeMillis()
                        )
                    )
                }

                // Sync local-only items to backend (items added while offline)
                val backendIds = response.data.map { it.product_id }.toSet()
                val localOnlyIds = localItems.map { it.productId }.toSet() - backendIds
                for (localItem in localItems.filter { it.productId in localOnlyIds }) {
                    try {
                        api.addFavorite(FavoriteRequest(
                            product_id = localItem.productId,
                            platform = localItem.platform,
                            title = localItem.title,
                            image_url = localItem.imageUrl,
                            price = localItem.price,
                            detail_url = localItem.detailUrl,
                            updated_at = localItem.updatedAt
                        ))
                    } catch (_: Exception) {
                        // Will retry on next sync
                    }
                }
            }
        } catch (_: Exception) {
            // 静默失败，本地缓存仍可用
        }
    }

    // ==================== History ====================

    fun getHistoryFlow(): Flow<List<RecognitionRecordEntity>> = recognitionDao.getAllFlow()

    suspend fun syncHistoryFromBackend() {
        try {
            val response = api.getHistory(page = 1, size = 100)
            if (response.code == 200 && response.data != null) {
                val items = response.data.items
                for (item in items) {
                    recognitionDao.insert(
                        RecognitionRecordEntity(
                            sessionId = item.session_id,
                            imageUrl = item.image_url ?: "",
                            categoryJson = item.category?.let { moshi.adapter(CategoryDto::class.java).toJson(it) } ?: "{}",
                            attributesJson = item.attributes?.let {
                                moshi.adapter<Map<String, AttributeValue>>(
                                    Types.newParameterizedType(Map::class.java, String::class.java, AttributeValue::class.java)
                                ).toJson(it)
                            } ?: "{}",
                            keywords = item.keywords?.joinToString(",") ?: "",
                            confidence = item.confidence ?: 0.0,
                            createdAt = item.created_at?.toLongOrNull() ?: System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // 静默失败，本地缓存仍可用
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
        val decoded = decodeUploadBitmap(uri)
        val normalized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decoded
        } else {
            applyExifRotation(uri, decoded)
        }
        val constrained = resizeIfNeeded(normalized, MAX_UPLOAD_DIMENSION)
        val jpegBytes = compressJpegToLimit(constrained)

        val tempFile = File.createTempFile("visioncart_", ".jpg", context.cacheDir)
        tempFile.outputStream().use { output -> output.write(jpegBytes) }

        if (constrained !== normalized) constrained.recycle()
        if (normalized !== decoded) normalized.recycle()
        decoded.recycle()

        return tempFile
    }

    private fun decodeUploadBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = MAX_UPLOAD_DIMENSION.toFloat() / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
                val normalized = decoded.copy(Bitmap.Config.ARGB_8888, false)
                if (normalized !== decoded) decoded.recycle()
                return normalized
            } catch (e: Exception) {
                throw IllegalArgumentException("图片格式暂不支持，请换用 JPG、PNG 或 WebP", e)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalArgumentException("无法读取图片: $uri")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("图片格式暂不支持，请换用 JPG 或 PNG")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }
        return bitmap ?: throw IllegalArgumentException("无法解码图片，请换用 JPG 或 PNG")
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_UPLOAD_DIMENSION || height / sampleSize > MAX_UPLOAD_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / currentMax
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun compressJpegToLimit(bitmap: Bitmap): ByteArray {
        var working = bitmap
        var quality = INITIAL_JPEG_QUALITY
        while (true) {
            val output = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_UPLOAD_BYTES || (quality <= MIN_JPEG_QUALITY && maxOf(working.width, working.height) <= 768)) {
                if (working !== bitmap) working.recycle()
                return bytes
            }
            if (quality > MIN_JPEG_QUALITY) {
                quality -= 8
            } else {
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * 0.85f).toInt().coerceAtLeast(1),
                    (working.height * 0.85f).toInt().coerceAtLeast(1),
                    true
                )
                if (working !== bitmap) working.recycle()
                working = scaled
                quality = INITIAL_JPEG_QUALITY
            }
        }
    }
}
