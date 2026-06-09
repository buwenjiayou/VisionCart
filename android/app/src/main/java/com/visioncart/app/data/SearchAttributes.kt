package com.visioncart.app.data

fun RecognitionResult.toSearchAttributes(): Map<String, String> {
    val searchAttributes = LinkedHashMap<String, String>()
    attributes.forEach { (name, value) ->
        val useful = value.value.takeIf(::isUsefulSearchValue)
        if (useful != null) {
            searchAttributes[name] = useful
            if (name == "品牌" && (value.verified || value.confidence >= 0.7)) {
                searchAttributes["__brand_reliable"] = "true"
            }
        }
    }

    val categoryName = listOf(category.level3, category.level2, category.level1)
        .firstOrNull(::isUsefulSearchValue)
    if (!categoryName.isNullOrBlank()) {
        searchAttributes["类目"] = categoryName
    }

    val categoryChain = listOf(category.level3, category.level2, category.level1)
        .map(String::trim)
        .filter(::isUsefulSearchValue)
        .distinct()
    if (categoryChain.isNotEmpty()) {
        searchAttributes["__category_chain"] = categoryChain.joinToString(",")
    }

    val normalizedKeywords = keywords
        .map(String::trim)
        .filter(::isUsefulSearchValue)
        .distinct()
    val keyword = normalizedKeywords.firstOrNull()
    if (!keyword.isNullOrBlank()) {
        searchAttributes["关键词"] = keyword
    }
    if (normalizedKeywords.isNotEmpty()) {
        searchAttributes["__keywords"] = normalizedKeywords.joinToString(",")
    }

    return searchAttributes
}

private fun isUsefulSearchValue(value: String?): Boolean {
    val trimmed = value?.trim().orEmpty()
    return trimmed.isNotBlank() &&
            trimmed !in setOf("未知", "未识别", "通用", "常规", "常规款", "常规款式", "普通款", "标准款", "基础款", "无", "无品牌") &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("unknown", ignoreCase = true)
}
