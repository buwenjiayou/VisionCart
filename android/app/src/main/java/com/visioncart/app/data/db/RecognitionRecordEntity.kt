package com.visioncart.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recognition_history",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["createdAt"]),
        Index(value = ["userId", "createdAt"])
    ]
)
data class RecognitionRecordEntity(
    @PrimaryKey val sessionId: String,
    val imageUrl: String,
    val categoryJson: String,
    val attributesJson: String,
    val keywords: String,
    val confidence: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val nlpQuery: String = "",
    val filterJson: String = "{}",
    val userId: Long? = null
)
