package com.visioncart.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SortModeTest {

    @Test
    fun `reputation aliases canonicalize to review quality`() {
        listOf("review_quality", "rating", "rating_desc", "reviews", "shop_trust", "seller_trust")
            .forEach { sortBy ->
                assertEquals("review_quality", canonicalSortBy(sortBy))
            }
    }

    @Test
    fun `empty sort canonicalizes to relevance sort payload`() {
        assertNull(canonicalSortBy(null))
        assertEquals("relevance", canonicalSortPayload(null, "desc"))
    }

    @Test
    fun `sort payloads use backend canonical keys`() {
        assertEquals("price_asc", canonicalSortPayload("price", "asc"))
        assertEquals("sales_desc", canonicalSortPayload("sales", "desc"))
        assertEquals("review_quality", canonicalSortPayload("review_quality", "desc"))
        assertEquals("review_quality", canonicalSortPayload("rating", "desc"))
    }
}
