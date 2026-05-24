package com.visioncart.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    val imageUri: Uri? = null
)

// ==================== Main ViewModel ====================

class MainViewModel(private val repository: VisionCartRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Favorites & History flows
    val favorites: StateFlow<List<FavoriteProductEntity>> = repository.getFavoritesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val history: StateFlow<List<RecognitionRecordEntity>> = repository.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ==================== Recognition ====================

    fun analyzeImage(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                imageUri = imageUri,
                recognitionState = UiState.Loading,
                products = emptyList(),
                suggestionCards = emptyList(),
                sessionId = null,
                categoryText = ""
            )
            val result = repository.analyzeImage(imageUri)
            result.onSuccess { recognition ->
                val attrs = recognition.attributes.mapValues { it.value.value }
                val categoryText = listOfNotNull(
                    recognition.category.level1,
                    recognition.category.level2,
                    recognition.category.level3
                ).joinToString(" / ")
                _uiState.value = _uiState.value.copy(
                    recognitionState = UiState.Success(recognition),
                    sessionId = recognition.sessionId,
                    currentAttributes = attrs,
                    categoryText = categoryText
                )
                // Auto search products
                searchProducts()
                // Load suggestion cards
                loadSuggestionCards(recognition.sessionId)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    recognitionState = UiState.Error(e.message ?: "识别失败")
                )
            }
        }
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
                val updatedAttrs = correctionResult.updatedAttributes.mapValues { it.value.value }
                _uiState.value = _uiState.value.copy(
                    currentAttributes = updatedAttrs,
                    recognitionState = _uiState.value.recognitionState.let { state ->
                        if (state is UiState.Success) {
                            UiState.Success(state.data.copy(attributes = correctionResult.updatedAttributes))
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
                    context = mapOf(
                        "product_name" to _uiState.value.categoryText,
                        "category" to _uiState.value.categoryText
                    )
                )
            )
            result.onSuccess { nlpResult ->
                val mergedFilter = mergeFilter(_uiState.value.currentFilter, nlpResult.filter)
                _uiState.value = _uiState.value.copy(currentFilter = mergedFilter)
                searchProducts()
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
            } else {
                repository.addFavoriteLocal(product, _uiState.value.sessionId ?: "")
                repository.addFavorite(product.id, _uiState.value.sessionId)
                showToast("已收藏")
            }
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
