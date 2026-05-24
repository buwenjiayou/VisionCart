package com.visioncart.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecognitionRecordDao {
    @Query("SELECT * FROM recognition_history ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<RecognitionRecordEntity>>

    @Query("SELECT * FROM recognition_history ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int = 20, offset: Int = 0): List<RecognitionRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecognitionRecordEntity)

    @Query("DELETE FROM recognition_history WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM recognition_history")
    suspend fun deleteAll()
}
