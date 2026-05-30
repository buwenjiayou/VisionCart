package com.visioncart.app.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.visioncart.app.data.*
import com.visioncart.app.data.db.FavoriteProductEntity
import com.visioncart.app.data.db.RecognitionRecordEntity
import com.visioncart.app.data.repository.VisionCartRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

// ==================== UI State ====================

sealed class UiState<out T> {
    data object Idle : UiState<Nothing>()
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

data class MainUiState(
    val recognitionState: UiState<RecognitionResult> = UiState.Idle,
    val products: List<ProductCard> = emptyList(),
    val productsLoading: Boolean = false,
    val suggestionCards: List<SuggestionCard> = emptyList(),
    val currentFilter: SearchFilter = SearchFilter(),
    val currentAttributes: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val categoryText: String = "",
    val toastMessage: String? = null,
    val imageUri: Uri? = null,
    val imagePreviewUrl: String? = null,
    val multiProductCandidates: List<RecognitionCandidate> = emptyList(),
    val nlpQuery: String = ""
)

// ==================== Main ViewModel ====================

class MainViewModel(private val repository: VisionCartRepository) : ViewModel() {

    private companion object {
        const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Favorites & History flows
    val favorites: StateFlow<List<FavoriteProductEntity>> = repository.getFavoritesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val history: StateFlow<List<RecognitionRecordEntity>> = repository.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun resetState() {
        _uiState.value = MainUiState()
    }

    // ==================== Recognition ====================

    fun analyzeImage(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                imageUri = imageUri,
                imagePreviewUrl = imageUri.toString(),
                recognitionState = UiState.Loading,
                products = emptyList(),
                productsLoading = false,
                suggestionCards = emptyList(),
                currentFilter = SearchFilter(),
                currentAttributes = emptyMap(),
                sessionId = null,
                categoryText = "",
                multiProductCandidates = emptyList(),
                nlpQuery = ""
            )
            val result = repository.analyzeImage(imageUri)
            result.onSuccess { recognition ->
                handleRecognitionSuccess(recognition)
            }.onFailure { e ->
                Log.e(TAG, "Recognition failed for uri=$imageUri", e)
                if (e is VisionCartRepository.MultiProductPendingException) {
                    val pendingImageUri = e.imageUrl?.let(Uri::parse) ?: imageUri
                    _uiState.value = _uiState.value.copy(
                        recognitionState = UiState.Idle,
                        sessionId = e.sessionId,
                        imageUri = pendingImageUri,
                        imagePreviewUrl = e.imageUrl ?: imageUri.toString(),
                        productsLoading = false,
                        multiProductCandidates = e.candidates,
                        toastMessage = "请选择要识别的商品"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        recognitionState = UiState.Error(e.message ?: "识别失败"),
                        productsLoading = false,
                        multiProductCandidates = emptyList(),
                        toastMessage = e.message ?: "识别失败"
                    )
                }
            }
        }
    }

    fun selectRecognitionCandidate(candidate: RecognitionCandidate) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                recognitionState = UiState.Loading,
                products = emptyList(),
                productsLoading = false,
                suggestionCards = emptyList()
            )
            val result = repository.selectProductForRecognition(
                sessionId,
                candidate.candidateId,
                _uiState.value.imageUri?.toString()
            )
            result.onSuccess { recognition ->
                handleRecognitionSuccess(recognition)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    recognitionState = UiState.Error(e.message ?: "识别失败"),
                    productsLoading = false,
                    toastMessage = e.message ?: "识别失败"
                )
            }
        }
    }

    private fun handleRecognitionSuccess(recognition: RecognitionResult) {
        Log.i(TAG, "Recognition succeeded: sessionId=${recognition.sessionId}, category=${recognition.category}")
        val attrs = recognition.toSearchAttributes()
        val categoryText = listOfNotNull(
            recognition.category.level1,
            recognition.category.level2,
            recognition.category.level3
        ).joinToString(" / ")
        _uiState.value = _uiState.value.copy(
            recognitionState = UiState.Success(recognition),
            sessionId = recognition.sessionId,
            currentAttributes = attrs,
            categoryText = categoryText,
            multiProductCandidates = emptyList()
        )
        searchProducts()
        loadSuggestionCards(recognition.sessionId)
    }

    // ==================== Search ====================

    fun searchProducts() {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(productsLoading = true)
            val result = repository.searchProducts(
                SearchRequest(
                    sessionId = sessionId,
                    attributes = state.currentAttributes,
                    filter = state.currentFilter
                )
            )
            result.onSuccess { searchResult ->
                _uiState.value = _uiState.value.copy(
                    products = searchResult.products,
                    productsLoading = false,
                    // Merge suggestion cards from search result
                    suggestionCards = if (searchResult.suggestionCards.isNotEmpty()) {
                        searchResult.suggestionCards
                    } else {
                        state.suggestionCards
                    }
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    productsLoading = false,
                    toastMessage = "搜索失败: ${e.message}"
                )
            }
        }
    }

    // ==================== Attribute Correction ====================

    fun correctAttribute(attribute: String, oldValue: String, newValue: String) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            val result = repository.correctAttribute(sessionId, attribute, oldValue, newValue)
            result.onSuccess { correctionResult ->
                // Update attributes
                val updatedRecognition = (_uiState.value.recognitionState as? UiState.Success)
                    ?.data
                    ?.copy(attributes = correctionResult.updatedAttributes)
                val updatedAttrs = updatedRecognition?.toSearchAttributes()
                    ?: correctionResult.updatedAttributes.mapValues { it.value.value }
                _uiState.value = _uiState.value.copy(
                    currentAttributes = updatedAttrs,
                    recognitionState = _uiState.value.recognitionState.let { state ->
                        if (state is UiState.Success) {
                            UiState.Success(updatedRecognition ?: state.data.copy(attributes = correctionResult.updatedAttributes))
                        } else state
                    }
                )
                // If correction result includes products, update them
                correctionResult.products?.let { searchResult ->
                    _uiState.value = _uiState.value.copy(products = searchResult.products)
                } ?: searchProducts()
                showToast("属性已修正: $attribute → $newValue")
            }.onFailure { e ->
                showToast("修正失败: ${e.message}")
            }
        }
    }

    // ==================== Suggestions ====================

    private fun loadSuggestionCards(sessionId: String) {
        viewModelScope.launch {
            val result = repository.getSuggestionCards(sessionId)
            result.onSuccess { cards ->
                _uiState.value = _uiState.value.copy(suggestionCards = cards)
            }
            // Silently fail - suggestions are optional
        }
    }

    fun executeSuggestion(card: SuggestionCard) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            val result = repository.executeSuggestion(
                sessionId, card.action, _uiState.value.products
            )
            result.onSuccess { execResult ->
                _uiState.value = _uiState.value.copy(
                    products = execResult.products,
                    suggestionCards = execResult.cards,
                    toastMessage = execResult.toast ?: "${card.title}已应用"
                )
            }.onFailure { e ->
                showToast("操作失败: ${e.message}")
            }
        }
    }

    // ==================== Filter ====================

    fun clearFilter() {
        _uiState.value = _uiState.value.copy(currentFilter = SearchFilter())
        searchProducts()
    }

    // ==================== NLP ====================

    fun parseNlp(userInput: String) {
        if (userInput.isBlank()) return
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(productsLoading = true)
            val result = repository.parseNlp(
                NlpParseRequest(
                    sessionId = sessionId,
                    userInput = userInput,
                    context = NlpContext(
                        productName = _uiState.value.categoryText,
                        category = _uiState.value.categoryText
                    )
                )
            )
            result.onSuccess { nlpResult ->
                val mergedFilter = mergeFilter(_uiState.value.currentFilter, nlpResult.filter)
                _uiState.value = _uiState.value.copy(currentFilter = mergedFilter, nlpQuery = userInput)
                searchProducts()
                // Persist NLP state to history record
                try {
                    val filterAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(SearchFilter::class.java)
                    repository.updateRecognitionNlp(sessionId, userInput, filterAdapter.toJson(mergedFilter))
                } catch (_: Exception) {}
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    productsLoading = false,
                    toastMessage = "解析失败: ${e.message}"
                )
            }
        }
    }

    private fun mergeFilter(current: SearchFilter, parsed: SearchFilter): SearchFilter {
        return SearchFilter(
            priceRange = PriceRange(
                min = parsed.priceRange.min ?: current.priceRange.min,
                max = parsed.priceRange.max ?: current.priceRange.max
            ),
            platforms = if (parsed.platforms.isNotEmpty()) parsed.platforms else current.platforms,
            selfOperated = parsed.selfOperated ?: current.selfOperated,
            colors = if (parsed.colors.isNotEmpty()) parsed.colors else current.colors,
            brands = if (parsed.brands.isNotEmpty()) parsed.brands else current.brands,
            ratingMin = parsed.ratingMin ?: current.ratingMin,
            sortBy = parsed.sortBy ?: current.sortBy,
            sortOrder = if (parsed.sortBy != null) parsed.sortOrder else current.sortOrder,
            keyword = parsed.keyword ?: current.keyword
        )
    }

    // ==================== Favorites ====================

    fun toggleFavorite(product: ProductCard) {
        viewModelScope.launch {
            val isFav = repository.isFavoriteLocal(product.id)
            if (isFav) {
                repository.removeFavoriteLocal(product.id)
                showToast("已取消收藏")
                // Sync removal to backend
                launch { repository.removeFavoriteBackend(product.id) }
            } else {
                repository.addFavoriteLocal(product, _uiState.value.sessionId ?: "")
                repository.addFavorite(product)
                showToast("已收藏")
            }
        }
    }

    // ==================== Sync ====================

    fun syncHistory() {
        viewModelScope.launch { repository.syncHistoryFromBackend() }
    }

    fun syncFavorites() {
        viewModelScope.launch { repository.syncFavoritesFromBackend() }
    }

    // ==================== Restore from History ====================

    fun restoreFromHistory(record: RecognitionRecordEntity) {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val categoryAdapter = moshi.adapter(CategoryDto::class.java)
        val attributesAdapter = moshi.adapter<Map<String, AttributeValue>>(
            Types.newParameterizedType(Map::class.java, String::class.java, AttributeValue::class.java)
        )
        val category = try {
            categoryAdapter.fromJson(record.categoryJson) ?: CategoryDto("", "", "", 0.0)
        } catch (_: Exception) { CategoryDto("", "", "", 0.0) }
        val attributes = try {
            attributesAdapter.fromJson(record.attributesJson) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        val categoryText = listOfNotNull(
            category.level1.takeIf { it.isNotBlank() },
            category.level2.takeIf { it.isNotBlank() },
            category.level3.takeIf { it.isNotBlank() }
        ).joinToString(" / ")

        val restoredRecognition = RecognitionResult(
            sessionId = record.sessionId,
            category = category,
            attributes = attributes,
            keywords = record.keywords.split(","),
            overallConfidence = record.confidence
        )

        // Restore NLP filter from history
        val restoredFilter = try {
            val filterAdapter = moshi.adapter(SearchFilter::class.java)
            filterAdapter.fromJson(record.filterJson) ?: SearchFilter()
        } catch (_: Exception) {
            SearchFilter()
        }

        _uiState.value = _uiState.value.copy(
            sessionId = record.sessionId,
            categoryText = categoryText,
            currentAttributes = restoredRecognition.toSearchAttributes(),
            currentFilter = restoredFilter,
            nlpQuery = record.nlpQuery,
            imageUri = localFileUriOrNull(record.imageUrl),
            imagePreviewUrl = record.imageUrl.takeIf { it.isNotBlank() },
            recognitionState = UiState.Success(restoredRecognition),
            multiProductCandidates = emptyList()
        )
        searchProducts()
    }

    private fun localFileUriOrNull(imageUrl: String): Uri? {
        if (!imageUrl.startsWith("file://")) return null
        return try {
            val uri = Uri.parse(imageUrl)
            val path = uri.path ?: return null
            if (File(path).isFile) uri else null
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Toast ====================

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toastMessage = message)
    }

    // ==================== Factory ====================

    class Factory(private val repository: VisionCartRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
