package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.*

/**
 * Unified state reducer for ALL user actions.
 * Takes an ActionResult from the backend and produces a new MainUiState.
 *
 * Guarantees:
 * - filterApplied=false does NOT overwrite products/filter/tags
 * - products never exceed 50
 * - stale requestId cannot overwrite newer state
 * - loading never overwrites existing products
 * - failure preserves current state
 */
object ActionStateReducer {

    private const val MAX_PRODUCTS = 50

    /**
     * messageCode → 中文文案映射。
     * 后端 ActionResult.messageCode 通过此表解析为用户可见中文，
     * 避免直接透传英文或乱码到 Android UI。
     */
    private val MESSAGE_CODE_MAP = mapOf(
        "filter.applied" to "已筛选",
        "filter.rollback" to "没有找到符合条件的商品，已保留原结果",
        "filter.cleared" to "已清空筛选条件",
        "filter.removed" to "已删除筛选标签",
        "filter.undone" to "已撤回上一步筛选",
        "filter.soft_reference" to "没有找到明确符合的商品，下方保留可能相关结果供参考",
        "preference.applied" to "已按偏好调整排序",
        "exclusion.applied" to "已排除相关商品",
        "semantic.judge.applied" to "已按语义偏好调整排序",
        "llm.rerank.fallback" to "AI语义评分不可用，已使用本地排序规则",
        "action.payload.required" to "操作参数不能为空",
        "unauthorized.session" to "无权访问该识别任务",
        "action.execution.failed" to "操作执行失败，请稍后重试",
        // Attribute correction message codes
        "attribute.updated.products.refreshed" to "已根据新属性刷新商品",
        "attribute.updated.search.empty" to "属性已修正，但暂未找到相关商品，已保留原结果",
        "attribute.updated.search.failed" to "属性已修正，但重新搜索失败，已保留原结果",
        "attribute.updated.products.kept" to "属性已修正，已保留原商品列表",
        "attribute.updated.only" to "属性已修正"
    )

    /**
     * 优先使用 messageCode 解析中文文案；messageCode 无映射时回退到 message。
     */
    fun resolveMessage(messageCode: String?, message: String?): String? {
        if (messageCode != null) {
            val mapped = MESSAGE_CODE_MAP[messageCode]
            if (mapped != null) return mapped
        }
        return message
    }

    /**
     * Reduce an ActionResult into a new MainUiState.
     *
     * @deprecated Use reduceActionResult with unified ActionResult instead.
     * @param current The current UI state
     * @param result The ActionResult from the backend
     * @param actionSource The source of the action (nlp, suggestion, correction, tag_delete)
     * @param requestId Optional request ID for stale response protection
     * @return The new UI state
     */
    @Deprecated("Use reduceActionResult with unified ActionResult", level = DeprecationLevel.ERROR)
    fun reduce(
        current: MainUiState,
        result: UnifiedActionResult,
        actionSource: String,
        requestId: Long? = null
    ): MainUiState {
        error("Use reduceActionResult() — legacy reduce() removed to enforce unified ActionResult consumption")
    }

    /**
     * Reduce a suggestion execution result.
     * @deprecated Use reduceActionResult with unified ActionResult instead.
     */
    @Deprecated("Use reduceActionResult with unified ActionResult", level = DeprecationLevel.ERROR)
    fun reduceSuggestion(
        current: MainUiState,
        result: SuggestionExecuteResult
    ): MainUiState {
        error("Use reduceActionResult() — legacy reduceSuggestion() removed")
    }

    /**
     * Reduce an NLP filter result.
     * @deprecated Use reduceActionResult with unified ActionResult instead.
     */
    @Deprecated("Use reduceActionResult with unified ActionResult", level = DeprecationLevel.ERROR)
    fun reduceNlp(
        current: MainUiState,
        result: NlpFilterResult,
        requestId: Long
    ): MainUiState {
        error("Use reduceActionResult() — legacy reduceNlp() removed")
    }

    /**
     * Reduce an undo result.
     * @deprecated Use the ActionResult overload instead.
     */
    @Deprecated("Use reduceUndo(current, result: ActionResult)", level = DeprecationLevel.ERROR)
    fun reduceUndo(
        current: MainUiState,
        result: UndoResult
    ): MainUiState {
        error("Use reduceUndo(current, result: ActionResult) — legacy UndoResult overload removed")
    }

    /**
     * Handle a flow action (UI action, no filter change).
     */
    fun reduceFlowAction(
        current: MainUiState,
        uiAction: UiAction
    ): MainUiState {
        return when (uiAction.type) {
            "OPEN_PRICE_ALERT_DIALOG" -> {
                val productId = uiAction.payload?.get("productId") as? String
                current.copy(
                    toastMessage = "请设置降价提醒"
                )
            }
            else -> current.copy(
                toastMessage = uiAction.message
            )
        }
    }

    /**
     * Reduce state from a unified ActionResult (suggestion, NLP, correction, etc.).
     * Single entry point for all action results from the unified pipeline.
     * Used by executeSuggestion() via /api/v1/suggestions/action.
     */
    fun reduceActionResult(current: MainUiState, result: ActionResult): MainUiState {
        // 优先用 messageCode 解析中文，回退到 message
        val displayMessage = resolveMessage(result.messageCode, result.message)

        // Handle flow actions (no state change)
        if (result.isFlowAction) {
            return current.copy(
                toastMessage = displayMessage
            )
        }

        // Correction actions — MUST be before the filterApplied=false guard.
        // Correction sets filterApplied=false (it's not a filter), so the guard would swallow it.
        // Attribute updates (currentAttributes, recognitionState) are handled by MainViewModel
        // after reduceActionResult, using toAttributeValues() — not here.
        if (result.actionSource == "correction") {
            val productsUpdated = result.productsUpdated == true

            // Products: only replace when backend confirmed new results
            val displayProducts = if (productsUpdated) {
                result.products.take(MAX_PRODUCTS)
            } else {
                current.products
            }

            return current.copy(
                products = displayProducts,
                canUndo = result.canUndo,
                keptPreviousResults = !productsUpdated,
                toastMessage = displayMessage,
                filterStatusMessage = displayMessage,
                suggestionCards = result.suggestionCards ?: current.suggestionCards
            )
        }

        val filterApplied = result.filterApplied

        // Guard: filterApplied=false must NOT overwrite current products/filter/tags
        if (!filterApplied) {
            return current.copy(
                toastMessage = displayMessage ?: "未找到更优结果，已保留当前商品",
                canUndo = result.canUndo,
                keptPreviousResults = true,
                filterStatusMessage = displayMessage
            )
        }

        // P2-8: Mixed results — primary + fallback products
        val displayProducts = if (result.isMixedResults) {
            result.allDisplayProducts.take(MAX_PRODUCTS)
        } else {
            result.products.take(MAX_PRODUCTS)
        }
        val actionSource = result.actionSource
        val shouldExposeFilterTags = actionSource != "suggestion" && actionSource != "sort"
        val newTags = if (shouldExposeFilterTags) result.filterTags.map { it.label } else current.filterTags
        val newStructuredTags = if (shouldExposeFilterTags) result.filterTags else current.structuredFilterTags
        val deriveFromFilter = when (actionSource) {
            "suggestion" -> false
            else -> true
        }

        return current.copy(
            products = displayProducts,
            currentFilter = result.appliedFilter ?: current.currentFilter,
            filterTags = newTags,
            structuredFilterTags = newStructuredTags,
            deriveFilterTagsFromFilter = deriveFromFilter,
            poolSize = result.totalInPool,
            canUndo = if (actionSource == "sort") false else result.canUndo,
            filterApplied = true,
            keptPreviousResults = result.keptPreviousResults,
            toastMessage = displayMessage,
            filterStatusMessage = null,
            suggestionCards = result.suggestionCards ?: current.suggestionCards
        )
    }

    /**
     * Reduce state from a unified undo ActionResult.
     * Used by undoLastAction() via POST /api/v1/actions/undo.
     */
    fun reduceUndo(current: MainUiState, result: ActionResult): MainUiState {
        val newProducts = result.products.take(MAX_PRODUCTS)
        val newTags = result.filterTags.map { it.label }
        return current.copy(
            products = newProducts,
            currentFilter = result.appliedFilter ?: current.currentFilter,
            filterTags = newTags,
            structuredFilterTags = result.filterTags,
            deriveFilterTagsFromFilter = true,
            poolSize = result.totalInPool,
            canUndo = result.canUndo,
            toastMessage = resolveMessage(result.messageCode, result.message),
            filterApplied = true,
            keptPreviousResults = false,
            nlpFiltering = false,
            productsLoading = false,
            // Clear stale status/message after undo
            filterStatusMessage = null,
            nlpQuery = "",
            nlpMessage = null,
            // Clear stale suggestion cards — loadSuggestionCards will repopulate
            suggestionCards = emptyList(),
            // Clear undo snapshot after undo
            undoProducts = null,
            undoFilter = null,
            undoAction = null,
            undoMetric = null,
            undoTone = null
        )
    }
}

// === Unified ActionResult model (matches backend ActionResult) ===

data class UnifiedActionResult(
    val products: List<ProductCard>?,
    val appliedFilter: SearchFilter?,
    val filterTags: List<FilterTag>?,
    val filterApplied: Boolean,
    val keptPreviousResults: Boolean,
    val canUndo: Boolean,
    val message: String?,
    val warnings: List<String>?,
    val explanations: List<String>?,
    val uiAction: UiAction?,
    val totalInPool: Int?,
    val suggestionCards: List<SuggestionCard>?,
    val actionSource: String?
)

data class UiAction(
    val type: String,
    val payload: Map<String, Any?>?,
    val message: String? = null
)

data class UndoResult(
    val products: List<ProductCard>?,
    val filter: SearchFilter?,
    val canUndo: Boolean,
    val message: String?
)
