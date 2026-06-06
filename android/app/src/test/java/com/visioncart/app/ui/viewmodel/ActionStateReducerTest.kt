package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.ActionResult
import com.visioncart.app.data.ProductCard
import org.junit.Assert.assertEquals
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
