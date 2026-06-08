package com.visioncart.app.ui.components

import com.visioncart.app.data.ProductCard
import java.util.Locale

fun productReputationDisplayText(product: ProductCard): String? {
    product.ratingDisplayLabel?.takeIf { it.isNotBlank() }?.let { return shortReputationLabel(it) }
    if (isPdd(product.platform)) {
        product.shopReputationLevel?.let { return "\u5e97\u94fa\u53e3\u7891 ${pddLevelText(it)}" }
        product.shopReputationScore?.let { return "\u5e97\u94fa\u53e3\u7891 ${pddLevelTextFromNormalized(it)}" }
        product.rating.takeIf { it > 0 }?.let { return "\u5e97\u94fa\u53e3\u7891 ${pddLevelTextFromFiveStar(it)}" }
    }
    if (isTaobaoOrTmall(product.platform)) {
        val score = product.shopReputationScore?.let { it * 5 }
            ?: product.itemRating
            ?: product.rating.takeIf { it > 0 }
        return score?.let { String.format(Locale.US, "\u5e97\u94fa\u8bc4\u5206 %.1f", it) }
    }
    return when (product.reputationEvidence) {
        "seller" -> product.sellerReputationScore?.let {
            String.format(Locale.US, "\u5356\u5bb6\u4fe1\u8a89 %.0f%%", it * 100)
        }
        "pdd_shop_level" -> product.shopReputationLevel?.let { level ->
            val text = when (level.lowercase(Locale.ROOT)) {
                "high" -> "\u9ad8"
                "mid", "medium" -> "\u4e2d"
                "low" -> "\u4f4e"
                else -> level
            }
            "\u5e97\u94fa\u53e3\u7891 $text"
        }
        "shop_dsr" -> product.shopReputationScore?.let {
            String.format(Locale.US, "\u5e97\u94fa\u8bc4\u5206 %.1f", it * 5)
        } ?: product.rating.takeIf { it > 0 }?.let {
            String.format(Locale.US, "\u5e97\u94fa\u8bc4\u5206 %.1f", it)
        }
        else -> null
    }
}

fun shortReputationLabel(label: String): String {
    val text = label.trim()
    val suffix = text.substringAfter("\u00b7", text).trim()
    return if (
        suffix.startsWith("\u5e97\u94fa\u8bc4\u5206")
        || suffix.startsWith("\u5e97\u94fa\u53e3\u7891")
        || suffix.startsWith("\u5356\u5bb6\u4fe1\u8a89")
    ) {
        suffix
    } else {
        text
    }
}

private fun isPdd(platform: String): Boolean {
    val text = platform.lowercase(Locale.ROOT)
    return text.contains("pdd") || platform.contains("\u62fc\u591a\u591a")
}

private fun isTaobaoOrTmall(platform: String): Boolean {
    val text = platform.lowercase(Locale.ROOT)
    return text.contains("taobao") || text.contains("tmall") ||
        platform.contains("\u6dd8\u5b9d") || platform.contains("\u5929\u732b")
}

private fun pddLevelText(level: String): String {
    return when (level.trim().lowercase(Locale.ROOT)) {
        "high", "\u9ad8" -> "\u9ad8"
        "mid", "medium", "\u4e2d" -> "\u4e2d"
        "low", "\u4f4e" -> "\u4f4e"
        else -> level
    }
}

private fun pddLevelTextFromNormalized(score: Double): String {
    return when {
        score >= 0.80 -> "\u9ad8"
        score >= 0.50 -> "\u4e2d"
        else -> "\u4f4e"
    }
}

private fun pddLevelTextFromFiveStar(score: Double): String {
    return when {
        score >= 4.4 -> "\u9ad8"
        score >= 3.8 -> "\u4e2d"
        else -> "\u4f4e"
    }
}
