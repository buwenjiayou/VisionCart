package com.visioncart.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteProductDao {
    @Query("SELECT * FROM favorite_products ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<FavoriteProductEntity>>

    @Query("SELECT * FROM favorite_products ORDER BY createdAt DESC")
    suspend fun getAll(): List<FavoriteProductEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_products WHERE productId = :productId LIMIT 1)")
    suspend fun isFavorite(productId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(product: FavoriteProductEntity)

    @Query("UPDATE favorite_products SET platform = :platform, title = :title, imageUrl = :imageUrl, price = :price, originalPrice = :originalPrice, shopName = :shopName, rating = :rating, sales = :sales, detailUrl = :detailUrl, updatedAt = :updatedAt, brand = :brand WHERE productId = :productId")
    suspend fun update(productId: String, platform: String, title: String, imageUrl: String, price: Double, originalPrice: Double?, shopName: String, rating: Double, sales: Long, detailUrl: String, updatedAt: Long, brand: String?)

    @Query("DELETE FROM favorite_products WHERE productId = :productId")
    suspend fun delete(productId: String)

    @Query("DELETE FROM favorite_products")
    suspend fun deleteAll()
}
