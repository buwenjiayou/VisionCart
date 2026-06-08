package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ActionStateReducerTest {

    @Test
    fun `judge result preserves specific message and mixed product order`() {
        val message = "已按「适合女生送礼」排序：优先展示礼盒和高颜值商品"
        val primary = product("p1", "香薰礼盒")
        val fallback = product("p2", "普通香薰")
        val current = MainUiState(products = listOf(product("old", "旧结果")))

        val result = ActionResult(
            products = listOf(primary),
            filterApplied = true,
            canUndo = true,
            message = message,
            messageCode = null,
            fallbackProducts = listOf(fallback),
            displayMode = "MIXED_RESULTS"
        )

        val reduced = ActionStateReducer.reduceActionResult(current, result)

        assertEquals(listOf("p1", "p2"), reduced.products.map { it.id })
        assertEquals(message, reduced.toastMessage)
        assertEquals(true, reduced.canUndo)
    }

    @Test
    fun `undo message code maps to user visible text`() {
        val result = ActionResult(
            products = listOf(product("p1", "当前结果")),
            filterApplied = true,
            message = "server fallback",
            messageCode = "filter.undone"
        )

        val reduced = ActionStateReducer.reduceUndo(MainUiState(), result)

        assertEquals("已撤回上一步筛选", reduced.toastMessage)
    }

    @Test
    fun `unknown message code falls back to backend message`() {
        val message = "已按具体语义排序"

        assertEquals(message, ActionStateReducer.resolveMessage("unknown.code", message))
    }

    @Test
    fun `suggestion result does not expose returned filter tags`() {
        val existingTag = FilterTag(
            id = "field-brands-Apple",
            label = "Apple",
            filterPath = "brands.Apple",
            source = "structured"
        )
        val current = MainUiState(
            products = listOf(product("old", "旧结果")),
            filterTags = listOf("Apple"),
            structuredFilterTags = listOf(existingTag),
            deriveFilterTagsFromFilter = true
        )
        val suggestionTag = FilterTag(
            id = "clause-cost-effective",
            label = "高性价比",
            filterPath = "preferences.cost_effective",
            source = "preference"
        )

        val reduced = ActionStateReducer.reduceActionResult(
            current,
            ActionResult(
                products = listOf(product("p1", "建议结果")),
                filterTags = listOf(suggestionTag),
                filterApplied = true,
                canUndo = true,
                actionSource = "suggestion"
            )
        )

        assertEquals(listOf("Apple"), reduced.filterTags)
        assertEquals(listOf(existingTag), reduced.structuredFilterTags)
        assertFalse(reduced.deriveFilterTagsFromFilter)
        assertTrue(reduced.canUndo)
    }

    @Test
    fun `sort result keeps tags visible but disables undo`() {
        val existingTag = FilterTag(
            id = "field-brands-Apple",
            label = "Apple",
            filterPath = "brands.Apple",
            source = "structured"
        )
        val current = MainUiState(
            filterTags = listOf("Apple"),
            structuredFilterTags = listOf(existingTag),
            deriveFilterTagsFromFilter = true
        )
        val sortTag = FilterTag(
            id = "field-sort",
            label = "价格低到高",
            filterPath = "sort",
            source = "structured"
        )

        val reduced = ActionStateReducer.reduceActionResult(
            current,
            ActionResult(
                products = listOf(product("p1", "排序结果")),
                appliedFilter = SearchFilter(sortBy = "price", sortOrder = "asc"),
                filterTags = listOf(sortTag),
                filterApplied = true,
                canUndo = true,
                actionSource = "sort"
            )
        )

        assertEquals(listOf("Apple"), reduced.filterTags)
        assertEquals(listOf(existingTag), reduced.structuredFilterTags)
        assertTrue(reduced.deriveFilterTagsFromFilter)
        assertFalse(reduced.canUndo)
    }

    private fun product(id: String, title: String) = ProductCard(
        id = id,
        title = title,
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
