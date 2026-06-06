package com.visioncart.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecognitionRecordDao {
    @Query("SELECT * FROM recognition_history WHERE userId = :userId ORDER BY createdAt DESC")
    fun getAllFlow(userId: Long): Flow<List<RecognitionRecordEntity>>

    @Query("SELECT * FROM recognition_history WHERE userId = :userId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(userId: Long, limit: Int = 20, offset: Int = 0): List<RecognitionRecordEntity>

    @Query("SELECT * FROM recognition_history WHERE userId = :userId")
    suspend fun getAllForUser(userId: Long): List<RecognitionRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecognitionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<RecognitionRecordEntity>)

    @Query("UPDATE recognition_history SET nlpQuery = :nlpQuery, filterJson = :filterJson WHERE sessionId = :sessionId")
    suspend fun updateNlpAndFilter(sessionId: String, nlpQuery: String, filterJson: String)

    @Query("DELETE FROM recognition_history WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM recognition_history")
    suspend fun deleteAll()

    @Query("DELETE FROM recognition_history WHERE userId = :userId")
    suspend fun deleteByUserId(userId: Long)

    /**
     * Delete history records for a user except those with the given session IDs.
     * Caller must ensure keepSessionIds is not empty to avoid deleting all records.
     */
    @Query("DELETE FROM recognition_history WHERE userId = :userId AND sessionId NOT IN (:keepSessionIds)")
    suspend fun deleteByUserIdExcept(userId: Long, keepSessionIds: List<String>)
}
