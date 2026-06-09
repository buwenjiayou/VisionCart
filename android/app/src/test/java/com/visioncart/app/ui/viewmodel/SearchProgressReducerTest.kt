package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.SearchProgressMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProgressReducerTest {

    @Test
    fun `staging progress keeps current products and loading true`() {
        val currentProducts = listOf(product("current-1"))
        val state = MainUiState(products = currentProducts, productsLoading = true)

        val reduced = applySearchProgress(
            state,
            SearchProgressMessage(products = listOf(product("staging-1")), staging = true)
        )

        assertEquals(currentProducts, reduced.products)
        assertTrue(reduced.productsLoading)
    }

    @Test
    fun `final progress replaces products and stops loading`() {
        val reduced = applySearchProgress(
            MainUiState(products = listOf(product("old")), productsLoading = true),
            SearchProgressMessage(
                products = (1..60).map { product("final-$it") },
                totalCount = 120,
                staging = false
            )
        )

        assertEquals(50, reduced.products.size)
        assertEquals("final-1", reduced.products.first().id)
        assertEquals(false, reduced.productsLoading)
        assertEquals(120, reduced.poolSize)
    }

    @Test
    fun `final progress merge helper deduplicates by id`() {
        val pool = LinkedHashMap<String, ProductCard>()
        mergeProgressProducts(pool, (1..30).map { product("p-$it") })
        val merged = mergeProgressProducts(pool, (20..70).map { product("p-$it") })

        assertEquals(50, merged.size)
        assertEquals(50, merged.map { it.id }.toSet().size)
    }

    private fun product(id: String) = ProductCard(
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
