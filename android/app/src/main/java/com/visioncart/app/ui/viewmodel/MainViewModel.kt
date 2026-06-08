package com.visioncart.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.visioncart.app.R
import com.visioncart.app.data.*
import com.visioncart.app.data.toSearchAttributes
import com.visioncart.app.data.db.FavoriteProductEntity
import com.visioncart.app.data.db.RecognitionRecordEntity
import com.visioncart.app.data.repository.VisionCartRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

/** Recognition-related state: image, candidates, session */
data class RecognitionUiState(
    val recognitionState: UiState<RecognitionResult> = UiState.Idle,
    val sessionId: String? = null,
    val imageUri: Uri? = null,
    val imagePreviewUrl: String? = null,
    val categoryText: String = "",
    val multiProductCandidates: List<RecognitionCandidate> = emptyList(),
    val currentAttributes: Map<String, String> = emptyMap(),
    val progressStep: String? = null,
    val confidenceHint: String? = null
)

/** Search & filter-related state: products, suggestions, filter */
data class SearchUiState(
    val products: List<ProductCard> = emptyList(),
    val productsLoading: Boolean = false,
    val suggestionCards: List<SuggestionCard> = emptyList(),
    val currentFilter: SearchFilter = SearchFilter(),
    val nlpQuery: String = "",
    val searchRelaxed: Boolean = false,
    val filterTags: List<String> = emptyList(),
    val poolSize: Int = 0,
    val canUndo: Boolean = false,
    val filterApplied: Boolean = true,
    val keptPreviousResults: Boolean = false,
    val filterStatusMessage: String? = null,
    // NLP background filtering state (separate from productsLoading)
    val nlpFiltering: Boolean = false,
    val nlpMessage: String? = null
)

/** Transient UI state: toast, dialogs, undo */
data class TransientUiState(
    val toastMessage: String? = null,
    val showPriceAlertDialog: ProductCard? = null,
    val undoProducts: List<ProductCard>? = null,
    val undoFilter: SearchFilter? = null,
    val undoAction: String? = null,
    val undoMetric: String? = null,
    val undoTone: String? = null
)

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
    val nlpQuery: String = "",
    val searchRelaxed: Boolean = false,
    val filterTags: List<String> = emptyList(),
    val poolSize: Int = 0,
    val undoProducts: List<ProductCard>? = null,
    val undoFilter: SearchFilter? = null,
    val undoAction: String? = null,
    val undoMetric: String? = null,
    val undoTone: String? = null,
    val showPriceAlertDialog: ProductCard? = null,
    val progressStep: String? = null,
    val confidenceHint: String? = null,
    // Semantic filter fields
    val canUndo: Boolean = false,
    val filterApplied: Boolean = true,
    val keptPreviousResults: Boolean = false,
    val filterStatusMessage: String? = null,
    // Structured filter tags for precise deletion
    val structuredFilterTags: List<com.visioncart.app.data.FilterTag> = emptyList(),
    // Whether FilterSummary may derive visible tags from currentFilter when backend tags are absent.
    val deriveFilterTagsFromFilter: Boolean = true,
    // NLP background filtering state
    val nlpFiltering: Boolean = false,
    val nlpMessage: String? = null,
    val nlpRequestId: Long = 0
) {
    fun toRecognitionUiState() = RecognitionUiState(
        recognitionState, sessionId, imageUri, imagePreviewUrl,
        categoryText, multiProductCandidates, currentAttributes, progressStep, confidenceHint
    )
    fun toSearchUiState() = SearchUiState(
        products, productsLoading, suggestionCards, currentFilter, nlpQuery, searchRelaxed,
        filterTags, poolSize, canUndo, filterApplied, keptPreviousResults, filterStatusMessage,
        nlpFiltering, nlpMessage
    )
    fun toTransientUiState() = TransientUiState(
        toastMessage, showPriceAlertDialog, undoProducts, undoFilter, undoAction, undoMetric, undoTone
    )
}

internal fun nextRelaxFilterField(filter: SearchFilter): String? = when {
    filter.priceRange.min != null || filter.priceRange.max != null -> "price_range"
    filter.brands.isNotEmpty() -> "brands"
    filter.colors.isNotEmpty() -> "colors"
    filter.ratingMin != null -> "rating_min"
    else -> null
}

internal fun snapshotVisibleFilterTags(state: MainUiState): Pair<List<String>, List<FilterTag>> {
    if (state.structuredFilterTags.isNotEmpty()) {
        return state.filterTags to state.structuredFilterTags
    }

    val tags = mutableListOf<FilterTag>()
    val filter = state.currentFilter
    filter.priceRange.min?.let { tags.add(FilterTag("field-price-range-min", "≥¥$it", "price_range.min", "structured")) }
    filter.priceRange.max?.let { tags.add(FilterTag("field-price-range-max", "≤¥$it", "price_range.max", "structured")) }
    filter.platforms.take(2).forEach { tags.add(FilterTag("field-platforms-$it", it, "platforms.$it", "structured")) }
    if (filter.selfOperated == true) {
        tags.add(FilterTag("field-self-operated", "自营", "self_operated", "structured"))
    }
    filter.colors.forEach { tags.add(FilterTag("field-colors-$it", it, "colors.$it", "structured")) }
    filter.brands.forEach { tags.add(FilterTag("field-brands-$it", it, "brands.$it", "structured")) }
    filter.ratingMin?.let { tags.add(FilterTag("field-rating-min", "≥${it}分", "rating_min", "structured")) }
    filter.keyword?.let {
        if (it.isNotBlank() && !it.startsWith("!")) {
            tags.add(FilterTag("field-keyword", it, "keyword", "structured"))
        }
    }

    return tags.map { it.label } to tags
}

// ==================== Main ViewModel ====================

class MainViewModel(
    application: Application,
    private val repository: VisionCartRepository
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "MainViewModel"
    }

    /** Shorthand for getString() */
    private fun str(resId: Int): String = getApplication<Application>().getString(resId)
    private fun str(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Focused state flows for targeted recomposition */
    val recognitionStateFlow: StateFlow<RecognitionUiState> = _uiState
        .map { it.toRecognitionUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecognitionUiState())
    val searchStateFlow: StateFlow<SearchUiState> = _uiState
        .map { it.toSearchUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())
    val transientStateFlow: StateFlow<TransientUiState> = _uiState
        .map { it.toTransientUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransientUiState())

    private var recognitionJob: kotlinx.coroutines.Job? = null
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchProgressJob: kotlinx.coroutines.Job? = null
    private var correctionRequestId: Long = 0
    // Problem 11 fix: unified search request ID for stale response protection
    // Prevents old HTTP results, WebSocket progress, and post-correction auto-search
    // from overwriting newer search results
    private var searchRequestId: Long = 0
    private var currentSearchRunId: String? = null

    // Favorites & History flows
    val favorites: StateFlow<List<FavoriteProductEntity>> = repository.getFavoritesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val history: StateFlow<List<RecognitionRecordEntity>> = repository.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _priceAlerts = MutableStateFlow<List<PriceAlertCard>>(emptyList())
    val priceAlerts: StateFlow<List<PriceAlertCard>> = _priceAlerts.asStateFlow()

    fun resetState() {
        _uiState.value = MainUiState()
    }

    // ==================== Recognition ====================

    fun analyzeImage(imageUri: Uri) {
        recognitionJob?.cancel()
        // 归档当前会话到 MySQL（识别历史）
        val previousSessionId = _uiState.value.sessionId
        recognitionJob = viewModelScope.launch {
            if (!previousSessionId.isNullOrBlank()) {
                repository.archiveSession(previousSessionId)
            }
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
                nlpQuery = "",
                progressStep = null,
                // Clear all filter-related state from previous session
                filterTags = emptyList(),
                structuredFilterTags = emptyList(),
                deriveFilterTagsFromFilter = true,
                filterStatusMessage = null,
                filterApplied = true,
                keptPreviousResults = false,
                canUndo = false,
                nlpFiltering = false,
                nlpMessage = null,
                poolSize = 0,
                undoProducts = null,
                undoFilter = null,
                undoAction = null,
                undoMetric = null,
                undoTone = null
            )
            val result = repository.analyzeImage(imageUri) { step ->
                _uiState.value = _uiState.value.copy(progressStep = step)
            }
            result.onSuccess { recognition ->
                handleRecognitionSuccess(recognition)
            }.onFailure { e ->
                Log.e(TAG, "Recognition failed for uri=$imageUri", e)
                if (e is VisionCartRepository.MultiProductPendingException) {
                    Log.i(TAG, "Multi-product pending: ${e.candidates.size} candidates")
                    e.candidates.forEach { c ->
                        Log.i(TAG, "  candidate: id=${c.candidateId}, category=${c.category}, previewImageUrl=${c.previewImageUrl}, bbox=${c.bbox}")
                    }
                    val pendingImageUri = e.imageUrl?.let(Uri::parse) ?: imageUri
                    _uiState.value = _uiState.value.copy(
                        recognitionState = UiState.Idle,
                        sessionId = e.sessionId,
                        imageUri = pendingImageUri,
                        imagePreviewUrl = e.imageUrl ?: imageUri.toString(),
                        productsLoading = false,
                        multiProductCandidates = e.candidates,
                        toastMessage = str(R.string.select_product_to_recognize)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        recognitionState = UiState.Error(e.message ?: str(R.string.recognition_failed)),
                        productsLoading = false,
                        multiProductCandidates = emptyList(),
                        // Preserve imageUri and sessionId for user to retry or give feedback
                        imageUri = _uiState.value.imageUri,
                        imagePreviewUrl = _uiState.value.imagePreviewUrl,
                        sessionId = _uiState.value.sessionId,
                        toastMessage = e.message ?: str(R.string.recognition_failed)
                    )
                }
            }
        }
    }

    fun selectRecognitionCandidate(candidate: RecognitionCandidate) {
        val sessionId = _uiState.value.sessionId ?: return
        val previousImageUri = _uiState.value.imageUri
        val previousImagePreviewUrl = _uiState.value.imagePreviewUrl
        val previousCandidates = _uiState.value.multiProductCandidates
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            val selectedPreviewUrl = candidate.previewImageUrl ?: _uiState.value.imagePreviewUrl
            _uiState.value = _uiState.value.copy(
                recognitionState = UiState.Loading,
                products = emptyList(),
                productsLoading = false,
                suggestionCards = emptyList(),
                currentFilter = SearchFilter(),
                currentAttributes = emptyMap(),
                // 切换预览图为选中商品的裁剪图
                imagePreviewUrl = selectedPreviewUrl
            )
            val result = repository.selectProductForRecognition(
                sessionId,
                candidate.candidateId,
                selectedPreviewUrl
            )
            result.onSuccess { recognition ->
                handleRecognitionSuccess(recognition)
            }.onFailure { e ->
                Log.e(TAG, "selectRecognitionCandidate failed", e)
                _uiState.value = _uiState.value.copy(
                    recognitionState = UiState.Error(e.message ?: str(R.string.recognition_failed)),
                    productsLoading = false,
                    // Preserve original image and session for retry
                    imageUri = previousImageUri,
                    imagePreviewUrl = previousImagePreviewUrl,
                    sessionId = sessionId,
                    multiProductCandidates = previousCandidates,
                    toastMessage = e.message ?: str(R.string.recognition_failed_retry)
                )
            }
        }
    }

    private fun handleRecognitionSuccess(recognition: RecognitionResult) {
        Log.i(TAG, "Recognition succeeded: sessionId=${recognition.sessionId}, category=${recognition.category}, confidence=${recognition.overallConfidence}")
        val attrs = recognition.toSearchAttributes()
        val categoryText = listOfNotNull(
            recognition.category.level1,
            recognition.category.level2,
            recognition.category.level3
        ).joinToString(" / ")
        val hint = recognition.confidenceHint
        _uiState.value = _uiState.value.copy(
            recognitionState = UiState.Success(recognition),
            sessionId = recognition.sessionId,
            currentAttributes = attrs,
            categoryText = categoryText,
            multiProductCandidates = emptyList(),
            filterTags = emptyList(),
            structuredFilterTags = emptyList(),
            deriveFilterTagsFromFilter = true,
            poolSize = 0,
            progressStep = null,
            confidenceHint = hint
        )
        // Show confidence hint as toast for medium/low confidence
        if (hint != null) {
            showToast(hint)
        }
        // searchProducts() already returns suggestion cards from the search response.
        // No need for a concurrent loadSuggestionCards() call — it races with the search
        // and can fail if the session cache isn't populated yet.
        searchProducts()
    }

    // ==================== Search ====================

    fun searchProducts() {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        searchJob?.cancel()
        searchProgressJob?.cancel()

        // P0-2 fix: increment searchRequestId to guard against stale responses
        val requestId = ++searchRequestId
        currentSearchRunId = null

        // P0-2 fix: 独立 progressPool，防止旧商品混入新搜索进度
        val progressPool = LinkedHashMap<String, ProductCard>()

        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                products = emptyList(),
                productsLoading = true
            )

            // Start WebSocket listener for staged partial results (runs in background)
            searchProgressJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                repository.subscribeSearchProgress(sessionId, timeoutMs = 15_000) { progress ->
                    // P0-2 fix: discard stale WebSocket progress
                    if (requestId != searchRequestId) return@subscribeSearchProgress
                    if (!acceptSearchRun(progress.searchRunId)) return@subscribeSearchProgress
                    if (currentSearchRunId == null && progress.searchRunId != null) {
                        currentSearchRunId = progress.searchRunId
                    }
                    progress.products.forEach { product ->
                        progressPool[product.id] = product
                    }
                    _uiState.value = _uiState.value.copy(
                        products = progressPool.values.take(50).toList(),
                        productsLoading = progress.staging
                    )
                }
            }

            val result = repository.searchProducts(
                SearchRequest(
                    sessionId = sessionId,
                    attributes = state.currentAttributes,
                    filter = state.currentFilter
                )
            )
            searchProgressJob?.cancel() // Stop listening once final result arrives

            // Problem 11 fix: discard stale HTTP search results
            if (requestId != searchRequestId) {
                Log.d(TAG, "Ignoring stale search response for requestId=$requestId")
                return@launch
            }

            result.onSuccess { searchResult ->
                // HTTP final belongs to this requestId, so it is authoritative. A late progress
                // message from the previous backend run may have arrived before this response and
                // temporarily populated currentSearchRunId; do not let that poison the final result.
                currentSearchRunId = searchResult.searchRunId ?: currentSearchRunId
                Log.i(TAG, "searchProducts: ${searchResult.products.size} products, ${searchResult.suggestionCards.size} suggestion cards")
                val mergedCards = searchResult.suggestionCards.ifEmpty { _uiState.value.suggestionCards }
                _uiState.value = _uiState.value.copy(
                    products = searchResult.products,
                    productsLoading = false,
                    searchRelaxed = searchResult.relaxed,
                    suggestionCards = mergedCards.distinctBy { it.id },
                    filterTags = emptyList(), // clear NLP tags on fresh search
                    structuredFilterTags = emptyList(),
                    deriveFilterTagsFromFilter = true,
                    poolSize = 0
                )
                // AI 导购卡异步生成：搜索返回后延迟拉一次，确保拿到 insight cards
                val hasInsight = mergedCards.any { it.id.startsWith("insight_") }
                if (!hasInsight) {
                    Log.i(TAG, "searchProducts: no insight cards yet, scheduling delayed pull")
                    loadSuggestionCards(sessionId)
                }
            }.onFailure { e ->
                Log.e(TAG, "searchProducts failed", e)
                _uiState.value = _uiState.value.copy(
                    productsLoading = false,
                    toastMessage = str(R.string.search_failed, e.message ?: "")
                )
            }
        }
    }

    private fun acceptSearchRun(incomingRunId: String?): Boolean {
        val current = currentSearchRunId
        return incomingRunId == null || current == null || incomingRunId == current
    }

    // ==================== Attribute Correction ====================

    fun correctAttribute(attribute: String, oldValue: String, newValue: String) {
        val sessionId = _uiState.value.sessionId ?: return
        val requestId = ++correctionRequestId

        searchJob?.cancel()
        searchProgressJob?.cancel()
        // P0-2 fix: 使旧搜索的 progress 回调失效
        searchRequestId++
        currentSearchRunId = null
        _uiState.value = _uiState.value.copy(productsLoading = true)

        viewModelScope.launch {
            val result = repository.executeUserAction(
                UserActionRequest(
                    actionId = "correction-${System.currentTimeMillis()}",
                    source = "correction",
                    sessionId = sessionId,
                    rawText = "$attribute=$newValue",
                    payload = UserActionPayload(field = attribute, value = newValue),
                    clientRequestId = "corr-$requestId"
                )
            )
            // Stale response protection: discard if a newer correction was issued
            if (requestId != correctionRequestId) {
                Log.d(TAG, "Ignoring stale correction response for requestId=$requestId")
                return@launch
            }
            result.onSuccess { actionResult ->
                var nextState = ActionStateReducer
                    .reduceActionResult(_uiState.value, actionResult)
                    .copy(productsLoading = false)
                // Always update attributes when backend returns them (even on product rollback)
                if (!actionResult.updatedAttributes.isNullOrEmpty()) {
                    val updatedAttributes = actionResult.updatedAttributes.toAttributeValues()
                    nextState = nextState.copy(
                        currentAttributes = updatedAttributes.mapValues { it.value.value },
                        recognitionState = nextState.recognitionState.let { state ->
                            if (state is UiState.Success) {
                                UiState.Success(state.data.copy(attributes = updatedAttributes))
                            } else state
                        }
                    )
                }
                _uiState.value = nextState
                loadSuggestionCards(sessionId)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(productsLoading = false)
                showToast(str(R.string.correction_failed, e.message ?: ""))
            }
        }
    }

    @Deprecated("Use correctAttribute() which goes through unified ActionExecutionService", level = DeprecationLevel.ERROR)
    private fun correctAttributeLegacy(attribute: String, oldValue: String, newValue: String) {
        error("Use correctAttribute() — legacy path removed to prevent AttributeCorrectionResult bypass")
    }

    // ==================== Suggestions ====================

    /** Retry delays for PENDING insight cards: 1.2s, 2.5s, 4s */
    private val insightRetryDelays = longArrayOf(1200, 2500, 4000)

    private fun loadSuggestionCards(sessionId: String, replace: Boolean = false) {
        viewModelScope.launch {
            val result = repository.getSuggestionCards(sessionId)
            result.onSuccess { response ->
                val cards = response.cards
                val insightStatus = response.insightStatus
                Log.i(TAG, "loadSuggestionCards: received ${cards.size} cards, insight_status=$insightStatus")
                if (cards.isNotEmpty()) {
                    if (replace) {
                        // After undo: replace all cards with fresh ones from server
                        _uiState.value = _uiState.value.copy(suggestionCards = cards.distinctBy { it.id })
                    } else {
                        // Normal load: merge with existing, prefer existing (from search) over new
                        val existingIds = _uiState.value.suggestionCards.map { it.id }.toSet()
                        val newCards = cards.filter { it.id !in existingIds }
                        val merged = _uiState.value.suggestionCards + newCards
                        _uiState.value = _uiState.value.copy(suggestionCards = merged.distinctBy { it.id })
                    }
                }

                // If insight cards are still pending (async generation in progress), retry with backoff
                if (insightStatus == "PENDING") {
                    retryInsightCards(sessionId)
                }
            }
            result.onFailure { e ->
                Log.w(TAG, "loadSuggestionCards failed for session $sessionId", e)
            }
        }
    }

    /**
     * Retry loading insight cards up to 3 times with increasing delays.
     * Stops as soon as an insight card appears or all retries exhausted.
     */
    private suspend fun retryInsightCards(sessionId: String) {
        for ((index, delayMs) in insightRetryDelays.withIndex()) {
            val hasInsight = _uiState.value.suggestionCards.any { it.id.startsWith("insight_") }
            if (hasInsight) {
                Log.i(TAG, "Insight card found after ${index} retries, stopping")
                return
            }
            Log.i(TAG, "Insight cards PENDING, retry ${index + 1}/${insightRetryDelays.size} in ${delayMs}ms")
            kotlinx.coroutines.delay(delayMs)
            val result = repository.getSuggestionCards(sessionId)
            result.onSuccess { response ->
                if (response.cards.isNotEmpty()) {
                    val existingIds = _uiState.value.suggestionCards.map { it.id }.toSet()
                    val newCards = response.cards.filter { it.id !in existingIds }
                    if (newCards.isNotEmpty()) {
                        val merged = _uiState.value.suggestionCards + newCards
                        _uiState.value = _uiState.value.copy(suggestionCards = merged.distinctBy { it.id })
                    }
                }
                if (response.insightStatus != "PENDING") {
                    Log.i(TAG, "Insight status resolved to ${response.insightStatus} after retry ${index + 1}")
                    return
                }
            }
        }
        Log.i(TAG, "Insight card retries exhausted")
    }

    /**
     * Single-shot load without retry loop (used for the delayed retry after PENDING).
     */
    private suspend fun loadSuggestionCardsOnce(sessionId: String) {
        val result = repository.getSuggestionCards(sessionId)
        result.onSuccess { response ->
            val cards = response.cards
            val hasInsight = cards.any { it.id.startsWith("insight_") }
            Log.i(TAG, "loadSuggestionCardsOnce: ${cards.size} cards, hasInsight=$hasInsight")
            if (cards.isNotEmpty()) {
                val existingIds = _uiState.value.suggestionCards.map { it.id }.toSet()
                val newCards = cards.filter { it.id !in existingIds }
                if (newCards.isNotEmpty()) {
                    val merged = _uiState.value.suggestionCards + newCards
                    _uiState.value = _uiState.value.copy(suggestionCards = merged.distinctBy { it.id })
                }
            }
        }
        result.onFailure { e ->
            Log.w(TAG, "loadSuggestionCardsOnce failed for session $sessionId", e)
        }
    }

    fun executeSuggestion(card: SuggestionCard) {
        val state = _uiState.value

        // FLOW_ACTION cards: backend returns uiAction via unified ActionResult,
        // but we also handle client-side flow actions for responsiveness
        if (card.isFlowAction) {
            when (card.action) {
                "action_set_price_alert" -> {
                    val cheapest = state.products
                        .filter { it.price > 0.0 }
                        .minByOrNull { it.price }
                    if (cheapest != null) {
                        _uiState.value = state.copy(
                            showPriceAlertDialog = cheapest,
                            toastMessage = str(R.string.select_target_price)
                        )
                    }
                }
                else -> showToast(str(R.string.feature_in_development, card.title))
            }
            return
        }

        val sessionId = state.sessionId ?: return
        viewModelScope.launch {
            // Save snapshot for undo
            val snapshotProducts = state.products
            val snapshotFilter = state.currentFilter
            val (snapshotFilterTags, snapshotStructuredFilterTags) = snapshotVisibleFilterTags(state)

            // Use unified endpoint (returns ActionResult)
            val result = repository.executeSuggestionAction(
                sessionId = sessionId,
                action = card.action,
                currentProducts = state.products,
                currentFilter = state.currentFilter
            )
            result.onSuccess { actionResult ->
                // Handle flow actions returned by backend (P1-7)
                if (actionResult.isFlowAction) {
                    when (actionResult.uiAction?.type) {
                        "OPEN_PRICE_ALERT_DIALOG" -> {
                            val cheapest = state.products
                                .filter { it.price > 0.0 }
                                .minByOrNull { it.price }
                            if (cheapest != null) {
                                _uiState.value = _uiState.value.copy(
                                    showPriceAlertDialog = cheapest,
                                    toastMessage = actionResult.message ?: str(R.string.select_target_price)
                                )
                            }
                        }
                        else -> showToast(actionResult.message ?: str(R.string.feature_in_development_generic))
                    }
                    return@onSuccess
                }

                // Use unified state reducer
                val newState = ActionStateReducer.reduceActionResult(_uiState.value, actionResult)
                _uiState.value = newState.copy(
                    // Preserve undo snapshot for backward compatibility
                    filterTags = snapshotFilterTags,
                    structuredFilterTags = snapshotStructuredFilterTags,
                    deriveFilterTagsFromFilter = false,
                    undoProducts = if (actionResult.canUndo && actionResult.filterApplied) snapshotProducts else null,
                    undoFilter = if (actionResult.canUndo && actionResult.filterApplied) snapshotFilter else null,
                    undoAction = if (actionResult.canUndo && actionResult.filterApplied) card.title else null,
                    undoMetric = if (actionResult.canUndo && actionResult.filterApplied) card.metric else null,
                    undoTone = if (actionResult.canUndo && actionResult.filterApplied) card.tone else null
                )
            }.onFailure { e ->
                showToast(str(R.string.suggestion_failed, e.message ?: ""))
            }
        }
    }

    fun undoSuggestion() {
        undoLastAction()
    }

    fun dismissPriceAlertDialog() {
        _uiState.value = _uiState.value.copy(showPriceAlertDialog = null)
    }

    // ==================== Filter ====================

    fun clearFilter() {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            val result = repository.executeUserAction(
                UserActionRequest(
                    actionId = "clear-${System.currentTimeMillis()}",
                    source = "clear_filter",
                    sessionId = sessionId,
                    rawText = "clear filters",
                    payload = UserActionPayload(action = "clear_all")
                )
            )
            result.onSuccess { actionResult ->
                _uiState.value = ActionStateReducer.reduceActionResult(_uiState.value, actionResult)
                    .copy(showPriceAlertDialog = null, deriveFilterTagsFromFilter = true)
                loadSuggestionCards(sessionId)
            }.onFailure { e ->
                showToast(str(R.string.filter_clear_failed, e.message ?: ""))
            }
        }
    }

    @Deprecated("Use clearFilter() which goes through unified ActionExecutionService", level = DeprecationLevel.ERROR)
    private fun clearFilterLegacy() {
        error("Use clearFilter() — legacy path removed")
    }

    /**
     * Relax the most restrictive filter condition to expand results.
     * Removes price range first, then brand, then color — whichever is present.
     */
    fun relaxFilter() {
        val state = _uiState.value
        val filter = state.currentFilter
        val sessionId = state.sessionId ?: return
        val fieldToRemove = nextRelaxFilterField(filter)
        if (fieldToRemove != null) {
            removeFilterField(fieldToRemove)
        } else {
            clearFilter()
        }
    }

    fun applySortMode(sortBy: String?, sortOrder: String = "desc") {
        val state = _uiState.value
        val sessionId = state.sessionId
        if (sessionId == null) {
            // No session — cannot use unified path; do local sort as fallback
            val current = state.currentFilter
            val next = current.copy(sortBy = sortBy, sortOrder = if (sortBy == null) "desc" else sortOrder)
            if (next != current) {
                _uiState.value = state.copy(
                    currentFilter = next,
                    deriveFilterTagsFromFilter = true,
                    undoProducts = null,
                    undoFilter = null,
                    undoAction = null,
                    undoMetric = null,
                    undoTone = null
                )
            }
            return
        }
        viewModelScope.launch {
            // Clear undo state only; keep suggestionCards visible until backend responds
            _uiState.value = state.copy(
                deriveFilterTagsFromFilter = true,
                undoProducts = null,
                undoFilter = null,
                undoAction = null,
                undoMetric = null,
                undoTone = null
            )
            val sortKey = if (sortBy == null) "relevance" else "${sortBy}_${sortOrder}"
            val result = repository.executeUserAction(
                UserActionRequest(
                    actionId = "sort-${System.currentTimeMillis()}",
                    source = "sort",
                    sessionId = sessionId,
                    rawText = "sort:$sortKey",
                    payload = UserActionPayload(sortBy = sortKey)
                )
            )
            result.onSuccess { actionResult ->
                val reduced = ActionStateReducer.reduceActionResult(_uiState.value, actionResult)
                _uiState.value = reduced.copy(
                    filterTags = state.filterTags,
                    structuredFilterTags = state.structuredFilterTags,
                    deriveFilterTagsFromFilter = true,
                    canUndo = false,
                    suggestionCards = actionResult.suggestionCards?.distinctBy { it.id } ?: emptyList(),
                    undoProducts = null,
                    undoFilter = null,
                    undoAction = null,
                    undoMetric = null,
                    undoTone = null
                )
            }.onFailure { e ->
                showToast(str(R.string.sort_failed, e.message ?: ""))
            }
        }
    }

    @Deprecated("Use applySortMode() which goes through unified ActionExecutionService", level = DeprecationLevel.ERROR)
    private fun applySortModeLegacy(sortBy: String?, sortOrder: String = "desc") {
        error("Use applySortMode() — legacy path removed")
    }

    // ==================== NLP ====================

    private var nlpJob: kotlinx.coroutines.Job? = null

    fun parseNlp(userInput: String) {
        if (userInput.isBlank()) return
        val sessionId = _uiState.value.sessionId ?: return
        nlpJob?.cancel()
        val requestId = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(
            nlpFiltering = true,
            nlpMessage = str(R.string.nlp_understanding),
            nlpRequestId = requestId
        )

        nlpJob = viewModelScope.launch {
            val statusJob1 = launch {
                kotlinx.coroutines.delay(1000)
                if (_uiState.value.nlpRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(nlpMessage = str(R.string.nlp_filtering))
                }
            }
            val statusJob2 = launch {
                kotlinx.coroutines.delay(3000)
                if (_uiState.value.nlpRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(nlpMessage = str(R.string.nlp_expanding))
                }
            }

            val category = _uiState.value.categoryText
            val context = buildMap<String, String> {
                if (category.isNotBlank()) {
                    put("productName", category)
                    put("category", category)
                }
            }
            val result = repository.executeUserAction(
                UserActionRequest(
                    actionId = "nlp-$requestId",
                    source = "nlp",
                    sessionId = sessionId,
                    rawText = userInput,
                    payload = UserActionPayload(context = context)
                )
            )

            statusJob1.cancel()
            statusJob2.cancel()

            if (_uiState.value.nlpRequestId != requestId) {
                Log.d(TAG, "Ignoring stale NLP response for requestId=$requestId")
                return@launch
            }

            result.onSuccess { actionResult ->
                val newState = ActionStateReducer.reduceActionResult(_uiState.value, actionResult)
                _uiState.value = newState.copy(
                    nlpQuery = if (actionResult.filterApplied) userInput else _uiState.value.nlpQuery,
                    nlpMessage = null,
                    nlpFiltering = false
                )
                loadSuggestionCards(sessionId)
                if (actionResult.filterApplied && actionResult.appliedFilter != null) {
                    try {
                        val filterAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(SearchFilter::class.java)
                        repository.updateRecognitionNlp(sessionId, userInput, filterAdapter.toJson(actionResult.appliedFilter))
                    } catch (_: Exception) {}
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    nlpFiltering = false,
                    nlpMessage = null,
                    filterStatusMessage = str(R.string.filter_failed_keep_previous),
                    toastMessage = str(R.string.nlp_parse_failed, e.message ?: "")
                )
            }
        }
    }

    /** Cancel in-flight NLP filtering and keep current state */
    fun cancelNlp() {
        nlpJob?.cancel()
        _uiState.value = _uiState.value.copy(
            nlpFiltering = false,
            nlpMessage = null
        )
    }

    /** Remove a single filter field or tag.
     *  Supports:
     *  - Legacy field names: "price_range", "colors", "platforms"
     *  - Structured tag IDs: "clause-airplane_allowed", "field-colors-black"
     *  - Direct filterPaths: "capabilities.airplane_allowed", "colors.black"
     */
    fun removeFilterField(fieldName: String) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            val result = repository.executeUserAction(
                UserActionRequest(
                    actionId = "tag-${System.currentTimeMillis()}",
                    source = "tag_delete",
                    sessionId = sessionId,
                    rawText = "remove filter:$fieldName",
                    payload = UserActionPayload(tagId = fieldName, filterPath = fieldName)
                )
            )
            result.onSuccess { actionResult ->
                _uiState.value = ActionStateReducer.reduceActionResult(_uiState.value, actionResult)
                    .copy(deriveFilterTagsFromFilter = true)
                loadSuggestionCards(sessionId)
            }.onFailure { e ->
                showToast(str(R.string.filter_delete_failed, e.message ?: ""))
            }
        }
    }

    /** Undo the last NLP filter operation */
    fun undoNlpFilter() {
        undoLastAction()
    }

    /**
     * Unified undo: undo the last action regardless of source.
     * Uses POST /api/v1/actions/undo which returns ActionResult.
     * This is the PRIMARY undo entry point — replaces undoSuggestion() and undoNlpFilter().
     */
    fun undoLastAction() {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            val result = repository.undoLastAction(sessionId)
            result.onSuccess { actionResult ->
                var nextState = ActionStateReducer.reduceUndo(_uiState.value, actionResult)
                // Restore attributes if undo returned them (correction undo)
                if (actionResult.attributesUpdated == true && !actionResult.updatedAttributes.isNullOrEmpty()) {
                    val restoredAttributes = actionResult.updatedAttributes.toAttributeValues()
                    nextState = nextState.copy(
                        currentAttributes = restoredAttributes.mapValues { it.value.value },
                        recognitionState = nextState.recognitionState.let { state ->
                            if (state is UiState.Success) {
                                UiState.Success(state.data.copy(attributes = restoredAttributes))
                            } else state
                        }
                    )
                }
                _uiState.value = nextState
                loadSuggestionCards(sessionId, replace = true)
            }.onFailure { e ->
                showToast("撤回失败: ${e.message}")
            }
        }
    }

    // ==================== Favorites ====================

    fun toggleFavorite(product: ProductCard) {
        viewModelScope.launch {
            val isFav = repository.isFavoriteLocal(product.id)
            if (isFav) {
                // Optimistic remove: local first, rollback on backend failure
                repository.removeFavoriteLocal(product.id)
                showToast(str(R.string.unfavorited))
                try {
                    repository.removeFavoriteBackend(product.id).getOrThrow()
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Backend remove favorite failed, rolling back", e)
                    repository.addFavoriteLocal(product, _uiState.value.sessionId ?: "")
                    showToast(str(R.string.unfavorite_failed_rollback))
                }
            } else {
                // Optimistic add: local first, rollback on backend failure
                repository.addFavoriteLocal(product, _uiState.value.sessionId ?: "")
                try {
                    repository.addFavorite(product).getOrThrow()
                    showToast(str(R.string.favorited))
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Backend add favorite failed, rolling back", e)
                    repository.removeFavoriteLocal(product.id)
                    showToast(str(R.string.favorite_failed_rollback))
                }
            }
        }
    }

    // ==================== Price Alerts ====================

    fun loadPriceAlerts() {
        viewModelScope.launch {
            repository.getPriceAlerts()
                .onSuccess { alerts ->
                    _priceAlerts.value = alerts
                }
                .onFailure { e ->
                    Log.w(TAG, "loadPriceAlerts failed", e)
                }
        }
    }

    fun createPriceAlert(productId: String, targetPrice: Double) {
        viewModelScope.launch {
            repository.createPriceAlert(productId, targetPrice).onSuccess { alert ->
                _priceAlerts.value = _priceAlerts.value.filter { it.productId != productId } + alert
                showToast(str(R.string.price_alert_set))
            }.onFailure { e ->
                showToast(str(R.string.price_alert_set_failed, e.message ?: ""))
            }
        }
    }

    fun deletePriceAlert(productId: String) {
        viewModelScope.launch {
            repository.deletePriceAlert(productId).onSuccess {
                _priceAlerts.value = _priceAlerts.value.filter { it.productId != productId }
                showToast(str(R.string.price_alert_deleted))
            }.onFailure { e ->
                showToast(str(R.string.price_alert_delete_failed, e.message ?: ""))
            }
        }
    }

    // ==================== Sync ====================

    fun syncHistory() {
        viewModelScope.launch { repository.syncHistoryFromBackend() }
    }

    fun deleteHistory(sessionId: String) {
        viewModelScope.launch {
            repository.deleteHistory(sessionId)
                .onSuccess { showToast(str(R.string.history_deleted)) }
                .onFailure { showToast(str(R.string.history_delete_failed, it.message ?: "")) }
        }
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
            multiProductCandidates = emptyList(),
            products = emptyList(),
            productsLoading = true,
            suggestionCards = emptyList(),
            filterTags = emptyList(),
            structuredFilterTags = emptyList(),
            deriveFilterTagsFromFilter = true,
            progressStep = str(R.string.history_loading)
        )

        // 先尝试从后端加载商品快照，不自动重新搜索
        viewModelScope.launch {
            val snapshotProducts = repository.getHistoryProducts(record.sessionId)
            if (snapshotProducts.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    products = snapshotProducts,
                    productsLoading = false,
                    progressStep = null,
                    toastMessage = str(R.string.history_restored, snapshotProducts.size)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    productsLoading = false,
                    progressStep = null,
                    toastMessage = str(R.string.history_no_snapshot)
                )
            }
        }
    }

    /**
     * 从历史记录重新搜索（用户主动触发）。
     */
    fun researchFromHistory() {
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

    private fun Map<String, Any>.toAttributeValues(): Map<String, AttributeValue> {
        return mapValues { (_, raw) ->
            when (raw) {
                is AttributeValue -> raw
                is Map<*, *> -> AttributeValue(
                    value = raw["value"] as? String ?: raw["value"]?.toString().orEmpty(),
                    confidence = (raw["confidence"] as? Number)?.toDouble() ?: 1.0,
                    verified = raw["verified"] as? Boolean ?: true
                )
                else -> AttributeValue(raw.toString(), 1.0, true)
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

    class Factory(
        private val application: Application,
        private val repository: VisionCartRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application, repository) as T
        }
    }
}
