package com.visioncart.app.ui.viewmodel

import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.SearchProgressMessage

private const val MAX_PROGRESS_PRODUCTS = 50

internal fun applySearchProgress(
    current: MainUiState,
    progress: SearchProgressMessage
): MainUiState {
    if (progress.staging) {
        return current.copy(productsLoading = true)
    }
    return current.copy(
        products = progress.products.take(MAX_PROGRESS_PRODUCTS),
        productsLoading = false,
        poolSize = progress.totalCount
    )
}

internal fun mergeProgressProducts(
    pool: LinkedHashMap<String, ProductCard>,
    products: List<ProductCard>
): List<ProductCard> {
    products.forEach { product ->
        pool[product.id] = product
    }
    return pool.values.take(MAX_PROGRESS_PRODUCTS).toList()
}
