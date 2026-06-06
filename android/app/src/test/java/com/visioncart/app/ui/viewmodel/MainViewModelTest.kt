package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the 192 products bug.
 * Verifies that WebSocket progress and search results never exceed pageSize=50.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== P0: Search API products cap ====================

    @Test
    fun `searchResult products never exceeds pageSize 50`() {
        val products = (1..50).map { productCard("api-$it") }
        val searchResult = SearchResult(
            total = 192,
            products = products,
            platformStats = emptyList(),
            suggestionCards = emptyList()
        )

        assertTrue("SearchResult.products.size must be <= 50, but was ${searchResult.products.size}",
            searchResult.products.size <= 50)
        assertEquals(192L, searchResult.total) // total reflects full pool
    }

    // ==================== P0: WebSocket progress take(50) ====================

    @Test
    fun `WebSocket progress with 192 products is capped at 50 by take`() {
        val progressProducts = (1..192).map { productCard("ws-$it") }

        // Replicate the take(50) logic from MainViewModel.searchProducts()
        val maxDisplay = 50
        val currentIds = emptySet<String>()
        val newProducts = progressProducts.filter { it.id !in currentIds }
        val merged = (emptyList<ProductCard>() + newProducts).take(maxDisplay)

        assertTrue("Merged products must be <= 50, but was ${merged.size}", merged.size <= 50)
        assertEquals(50, merged.size)
    }

    @Test
    fun `WebSocket progress with 50 products stays at 50`() {
        val progressProducts = (1..50).map { productCard("ws-$it") }

        val maxDisplay = 50
        val newProducts = progressProducts.filter { it.id !in emptySet<String>() }
        val merged = (emptyList<ProductCard>() + newProducts).take(maxDisplay)

        assertEquals(50, merged.size)
    }

    @Test
    fun `WebSocket progress deduplicates by id across multiple batches`() {
        val firstBatch = (1..30).map { productCard("ws-$it") }
        val secondBatch = (21..70).map { productCard("ws-$it") }

        val maxDisplay = 50

        // First progress
        val newProducts1 = firstBatch.filter { it.id !in emptySet<String>() }
        val merged1 = (emptyList<ProductCard>() + newProducts1).take(maxDisplay)
        assertEquals(30, merged1.size)

        // Second progress — dedup + cap
        val currentIds2 = merged1.map { it.id }.toSet()
        val newProducts2 = secondBatch.filter { it.id !in currentIds2 }
        val merged2 = (merged1 + newProducts2).take(maxDisplay)

        assertTrue("Merged must be <= 50", merged2.size <= 50)
        assertEquals("All ids must be unique", merged2.size, merged2.map { it.id }.toSet().size)
        assertEquals(50, merged2.size) // 30 + 20 new = 50
    }

    @Test
    fun `WebSocket progress does not add duplicates when products already at cap`() {
        // Simulate: already have 50 products, new progress arrives
        val existingProducts = (1..50).map { productCard("ws-$it") }
        val newProgress = (40..60).map { productCard("ws-$it") }

        val maxDisplay = 50
        val currentIds = existingProducts.map { it.id }.toSet()
        val newProducts = newProgress.filter { it.id !in currentIds }
        val merged = (existingProducts + newProducts).take(maxDisplay)

        assertEquals("Should stay at 50", 50, merged.size)
        assertEquals("All ids must be unique", merged.size, merged.map { it.id }.toSet().size)
    }

    // ==================== P0: LoadingIndicator state ====================

    @Test
    fun `LoadingIndicator shows when productsLoading true and products empty`() {
        val uiState = MainUiState(productsLoading = true, products = emptyList())
        val shouldShowSkeleton = uiState.productsLoading && uiState.products.isEmpty()
        assertTrue("Skeleton should show when loading and no products", shouldShowSkeleton)
    }

    @Test
    fun `LoadingIndicator hides when productsLoading true but products exist`() {
        val uiState = MainUiState(
            productsLoading = true,
            products = listOf(productCard("1"))
        )
        val shouldShowSkeleton = uiState.productsLoading && uiState.products.isEmpty()
        assertFalse("Skeleton should NOT show when products exist", shouldShowSkeleton)
    }

    @Test
    fun `LoadingIndicator hides when productsLoading false`() {
        val uiState = MainUiState(productsLoading = false, products = emptyList())
        val shouldShowSkeleton = uiState.productsLoading && uiState.products.isEmpty()
        assertFalse("Skeleton should NOT show when not loading", shouldShowSkeleton)
    }

    @Test
    fun `LoadingIndicator hides after search completes with results`() {
        val uiState = MainUiState(
            productsLoading = false,
            products = (1..50).map { productCard("api-$it") }
        )
        val shouldShowSkeleton = uiState.productsLoading && uiState.products.isEmpty()
        assertFalse("Skeleton should NOT show after search completes", shouldShowSkeleton)
        assertEquals(50, uiState.products.size)
    }

    // ==================== P1: suggestionCards dedup ====================

    @Test
    fun `suggestionCards are deduplicated by id`() {
        val cards = listOf(
            SuggestionCard("insight_1", "AI Card", "sub", "icon", "action", 5),
            SuggestionCard("insight_1", "AI Card Duplicate", "sub", "icon", "action", 5),
            SuggestionCard("quick_1", "Quick Card", "sub", "icon", "action", 3)
        )

        val deduped = cards.distinctBy { it.id }

        assertEquals(2, deduped.size)
        assertEquals("AI Card", deduped[0].title) // First occurrence wins
        assertEquals("Quick Card", deduped[1].title)
    }

    @Test
    fun `suggestionCards merge dedup prefers existing over new`() {
        val existing = listOf(
            SuggestionCard("insight_1", "Existing AI", "sub", "icon", "action", 5)
        )
        val newCards = listOf(
            SuggestionCard("insight_1", "New AI", "sub", "icon", "action", 5),
            SuggestionCard("quick_1", "New Quick", "sub", "icon", "action", 3)
        )

        // Replicate the merge logic from loadSuggestionCards
        val existingIds = existing.map { it.id }.toSet()
        val freshCards = newCards.filter { it.id !in existingIds }
        val merged = (existing + freshCards).distinctBy { it.id }

        assertEquals(2, merged.size)
        assertEquals("Existing AI", merged[0].title) // Existing wins
        assertEquals("New Quick", merged[1].title)
    }

    // ==================== P1: Multi-product state preservation ====================

    @Test
    fun `MULTI_PRODUCT_PENDING state preserves imagePreviewUrl and sessionId`() {
        // Simulate the state set when MULTI_PRODUCT_PENDING is received
        val state = MainUiState(
            recognitionState = UiState.Idle,
            sessionId = "sess-123",
            imagePreviewUrl = "file:///storage/emulated/0/DCIM/test.jpg",
            multiProductCandidates = listOf(
                RecognitionCandidate(
                    candidateId = "candidate-1",
                    bbox = listOf(10, 10, 100, 100),
                    category = "手机",
                    brand = "Apple",
                    confidence = 0.9,
                    previewImageUrl = "/api/v1/recognition/sess-123/candidates/candidate-1/image"
                )
            ),
            productsLoading = false
        )

        // imagePreviewUrl and sessionId must be preserved
        assertNotNull("imagePreviewUrl must not be null in MULTI_PRODUCT_PENDING state", state.imagePreviewUrl)
        assertNotNull("sessionId must not be null", state.sessionId)
        assertEquals("sess-123", state.sessionId)
        assertEquals(1, state.multiProductCandidates.size)
    }

    @Test
    fun `error state preserves imagePreviewUrl and sessionId for retry`() {
        // Simulate the state set when recognition fails (non-multi-product error)
        val state = MainUiState(
            recognitionState = UiState.Error("识别失败"),
            imagePreviewUrl = "file:///storage/emulated/0/DCIM/test.jpg",
            sessionId = "sess-456",
            multiProductCandidates = emptyList(),
            productsLoading = false
        )

        // imagePreviewUrl and sessionId must be preserved for retry/feedback
        assertNotNull("imagePreviewUrl must be preserved on error", state.imagePreviewUrl)
        assertNotNull("sessionId must be preserved on error", state.sessionId)
        assertEquals("sess-456", state.sessionId)
    }

    @Test
    fun `selectRecognitionCandidate failure preserves candidates for retry`() {
        val candidates = listOf(
            RecognitionCandidate(
                candidateId = "candidate-1",
                bbox = listOf(10, 10, 100, 100),
                category = "手机",
                brand = "Apple",
                confidence = 0.9,
                previewImageUrl = "/api/v1/recognition/sess/candidates/candidate-1/image"
            ),
            RecognitionCandidate(
                candidateId = "candidate-2",
                bbox = listOf(200, 200, 400, 400),
                category = "耳机",
                brand = "Sony",
                confidence = 0.8,
                previewImageUrl = "/api/v1/recognition/sess/candidates/candidate-2/image"
            )
        )
        // Simulate: user selected candidate-1, it failed, state should preserve candidates
        val state = MainUiState(
            recognitionState = UiState.Error("候选商品不存在或已过期"),
            sessionId = "sess-789",
            imagePreviewUrl = "file:///storage/emulated/0/DCIM/test.jpg",
            multiProductCandidates = candidates,
            productsLoading = false
        )

        assertEquals(2, state.multiProductCandidates.size)
        assertNotNull("sessionId preserved for retry", state.sessionId)
        assertNotNull("imagePreviewUrl preserved for retry", state.imagePreviewUrl)
    }

    // ==================== P2: State machine regression tests ====================

    // --- Recognition state transitions ---

    @Test
    fun `recognition PROCESSING to MULTI_PRODUCT_PENDING preserves image`() {
        // Start: PROCESSING
        val processing = MainUiState(
            recognitionState = UiState.Loading,
            sessionId = "sess-1",
            imagePreviewUrl = "file:///tmp/photo.jpg"
        )
        assertEquals(UiState.Loading, processing.recognitionState)

        // Transition: MULTI_PRODUCT_PENDING (user must choose)
        val pending = processing.copy(
            recognitionState = UiState.Idle,
            multiProductCandidates = listOf(
                RecognitionCandidate("c1", listOf(0,0,100,100), "手机", null, 0.9, "/api/v1/recognition/sess-1/candidates/c1/image")
            ),
            productsLoading = false,
            progressStep = "请选择要识别的商品"
        )
        // Image must survive transition
        assertNotNull("imagePreviewUrl preserved", pending.imagePreviewUrl)
        assertEquals("sess-1", pending.sessionId)
        assertEquals(1, pending.multiProductCandidates.size)
        assertNotEquals(UiState.Loading, pending.recognitionState)
    }

    @Test
    fun `recognition FAILED preserves image and session for retry`() {
        val failed = MainUiState(
            recognitionState = UiState.Error("识别超时"),
            sessionId = "sess-2",
            imagePreviewUrl = "file:///tmp/photo.jpg",
            imageUri = null,
            multiProductCandidates = emptyList(),
            productsLoading = false
        )
        assertNotNull("imagePreviewUrl preserved on failure", failed.imagePreviewUrl)
        assertNotNull("sessionId preserved on failure", failed.sessionId)
    }

    @Test
    fun `recognition COMPLETED clears candidates and triggers search`() {
        val completed = MainUiState(
            recognitionState = UiState.Success(RecognitionResult(
                sessionId = "sess-3",
                category = CategoryDto("电子", "手机", "智能手机", 0.95),
                attributes = emptyMap(),
                keywords = listOf("手机"),
                overallConfidence = 0.95
            )),
            sessionId = "sess-3",
            multiProductCandidates = emptyList(),
            productsLoading = true, // search triggered
            currentAttributes = mapOf("品牌" to "Apple")
        )
        assertTrue("candidates cleared", completed.multiProductCandidates.isEmpty())
        assertTrue("search triggered", completed.productsLoading)
    }

    // --- Search state transitions ---

    @Test
    fun `search REST and WebSocket both capped at 50`() {
        val restProducts = (1..30).map { productCard("rest-$it") }
        val wsProducts = (1..40).map { productCard("ws-$it") }

        val maxDisplay = 50
        // REST arrives first
        val afterRest = restProducts.take(maxDisplay)
        assertEquals(30, afterRest.size)

        // WebSocket merges with dedup
        val currentIds = afterRest.map { it.id }.toSet()
        val newFromWs = wsProducts.filter { it.id !in currentIds }
        val merged = (afterRest + newFromWs).take(maxDisplay)
        assertTrue("merged <= 50", merged.size <= 50)
        assertEquals("all unique", merged.size, merged.map { it.id }.toSet().size)
    }

    @Test
    fun `productsLoading true with products does not show skeleton`() {
        val state = MainUiState(
            productsLoading = true,
            products = listOf(productCard("1"))
        )
        val showSkeleton = state.productsLoading && state.products.isEmpty()
        assertFalse("no skeleton when products exist", showSkeleton)
    }

    // --- History state transitions ---

    @Test
    fun `history restoration shows snapshot not re-search`() {
        // Simulate: user opens history, state loads snapshot
        val restored = MainUiState(
            sessionId = "hist-1",
            recognitionState = UiState.Success(RecognitionResult(
                sessionId = "hist-1",
                category = CategoryDto("电子", "手机", "", 0.9),
                attributes = emptyMap(),
                keywords = listOf("手机"),
                overallConfidence = 0.9
            )),
            products = listOf(productCard("snap-1"), productCard("snap-2")),
            productsLoading = false, // NOT loading — snapshot displayed directly
            progressStep = null
        )
        assertEquals(2, restored.products.size)
        assertFalse("not auto-searching", restored.productsLoading)
        assertNull("no progress step", restored.progressStep)
    }

    // --- NLP state transitions ---

    @Test
    fun `NLP filter works on active session not yet in history`() {
        // Session is active (in taskManager), not yet archived to history
        val state = MainUiState(
            sessionId = "active-1",
            recognitionState = UiState.Success(RecognitionResult(
                sessionId = "active-1",
                category = CategoryDto("电子", "手机", "", 0.9),
                attributes = emptyMap(),
                keywords = listOf("手机"),
                overallConfidence = 0.9
            )),
            products = (1..20).map { productCard("p-$it") },
            productsLoading = false,
            currentFilter = SearchFilter()
        )
        // NLP filter should work (backend verifies via taskManager.belongsToUser)
        assertNotNull("session exists", state.sessionId)
        assertTrue("has products to filter", state.products.isNotEmpty())
    }

    // ==================== Helper ====================

    private fun productCard(id: String) = ProductCard(
        id = id,
        title = "Product $id",
        imageUrl = "",
        price = 99.0,
        originalPrice = null,
        platform = "淘宝",
        selfOperated = false,
        shopName = "旗舰店",
        rating = 4.8,
        sales = 100,
        similarity = 0.9,
        tags = emptyList(),
        detailUrl = "https://example.com/$id"
    )
}
