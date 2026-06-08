package com.visioncart.app.ui.components

import com.visioncart.app.data.ProductCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReputationDisplayFormatterTest {

    @Test
    fun shortReputationLabelRemovesLegacyPlatformPrefix() {
        assertEquals("店铺评分 4.8", shortReputationLabel("淘宝 · 店铺评分 4.8"))
        assertEquals("店铺口碑 高", shortReputationLabel("拼多多 · 店铺口碑 高"))
        assertEquals("卖家信誉 98%", shortReputationLabel("eBay · 卖家信誉 98%"))
    }

    @Test
    fun productReputationDisplayTextUsesBackendShortLabelFirst() {
        val product = product("淘宝 · 店铺评分 4.8")

        assertEquals("店铺评分 4.8", productReputationDisplayText(product))
    }

    @Test
    fun taobaoItemRatingFallsBackToShopScoreLabel() {
        val product = product(
            label = null,
            platform = "淘宝",
            rating = 4.8,
            ratingSource = "item_rating"
        )

        assertEquals("店铺评分 4.8", productReputationDisplayText(product))
    }

    @Test
    fun pddRatingFallsBackToShopReputationLevelLabel() {
        val product = product(
            label = null,
            platform = "拼多多",
            rating = 4.0,
            ratingSource = "shop_dsr"
        )

        assertEquals("店铺口碑 中", productReputationDisplayText(product))
    }

    @Test
    fun productWithoutReputationDoesNotShowLegacyRatingText() {
        val product = product(
            label = null,
            platform = "other",
            rating = 4.8,
            ratingSource = "item_rating"
        )

        assertNull(productReputationDisplayText(product))
    }

    private fun product(
        label: String?,
        platform: String = "淘宝",
        rating: Double = 4.8,
        ratingSource: String? = "shop_dsr"
    ) = ProductCard(
        id = "p1",
        title = "商品",
        imageUrl = "",
        price = 99.0,
        originalPrice = null,
        platform = platform,
        selfOperated = false,
        shopName = "测试店铺",
        rating = rating,
        sales = 100,
        similarity = 0.9,
        tags = emptyList(),
        detailUrl = "",
        ratingSource = ratingSource,
        ratingDisplayLabel = label
    )
}
