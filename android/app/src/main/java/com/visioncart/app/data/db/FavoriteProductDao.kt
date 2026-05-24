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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: FavoriteProductEntity)

    @Query("DELETE FROM favorite_products WHERE productId = :productId")
    suspend fun delete(productId: String)

    @Query("DELETE FROM favorite_products")
    suspend fun deleteAll()
}
