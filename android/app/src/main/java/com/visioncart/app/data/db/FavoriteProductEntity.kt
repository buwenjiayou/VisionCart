package com.visioncart.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_products")
data class FavoriteProductEntity(
    @PrimaryKey val productId: String,
    val platform: String,
    val title: String,
    val imageUrl: String,
    val price: Double,
    val originalPrice: Double? = null,
    val shopName: String,
    val rating: Double,
    val sales: Long,
    val detailUrl: String,
    val sessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val brand: String? = null
)
