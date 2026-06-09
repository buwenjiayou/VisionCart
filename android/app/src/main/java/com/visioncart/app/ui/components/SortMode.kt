package com.visioncart.app.ui.components

private val reputationSortKeys = setOf(
    "rating",
    "reviews",
    "review_quality",
    "rating_desc",
    "shop_trust",
    "seller_trust"
)

fun canonicalSortBy(sortBy: String?): String? {
    return when (sortBy) {
        null, "" -> null
        in reputationSortKeys -> "review_quality"
        else -> sortBy
    }
}

fun canonicalSortPayload(sortBy: String?, sortOrder: String): String {
    return when (canonicalSortBy(sortBy)) {
        null -> "relevance"
        "price" -> if (sortOrder.equals("desc", ignoreCase = true)) "price_desc" else "price_asc"
        "sales" -> "sales_desc"
        "review_quality" -> "review_quality"
        else -> {
            val order = sortOrder.ifBlank { "desc" }
            "${sortBy}_${order}"
        }
    }
}
