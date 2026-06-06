package com.visioncart.app.ui.history

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.visioncart.app.data.db.RecognitionRecordEntity
import com.visioncart.app.ui.components.rememberAuthenticatedImageModel
import com.visioncart.app.ui.viewmodel.MainViewModel
import androidx.compose.ui.tooling.preview.Preview
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun parseCategoryName(categoryJson: String): String {
    return try {
        val obj = JSONObject(categoryJson)
        listOfNotNull(
            obj.optString("level1").takeIf { it.isNotBlank() },
            obj.optString("level2").takeIf { it.isNotBlank() },
            obj.optString("level3").takeIf { it.isNotBlank() }
        ).joinToString(" / ").ifBlank { categoryJson }
    } catch (_: Exception) {
        categoryJson
    }
}

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val history by viewModel.history.collectAsState()

    if (history.isEmpty()) {
        Text(
            "暂无识物记录",
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            color = Color(0xFF7A8A85),
            style = MaterialTheme.typography.bodyLarge
        )
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { it.sessionId }) { record ->
                HistoryItemView(
                    record = record,
                    onClick = { onItemClick(record.sessionId) },
                    onDelete = { viewModel.deleteHistory(record.sessionId) }
                )
            }
        }
    }
}

@Composable
internal fun HistoryItemView(
    record: RecognitionRecordEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val timeText = dateFormat.format(Date(record.createdAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            HistoryThumbnail(record.imageUrl)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    parseCategoryName(record.categoryJson),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    record.keywords,
                    color = Color(0xFF53615E),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "置信度 ${(record.confidence * 100).toInt()}%",
                        color = Color(0xFF757575),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            timeText,
                            color = Color(0xFF9E9E9E),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = Color(0xFFBDBDBD),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryThumbnail(imageUrl: String) {
    val imageModel = rememberAuthenticatedImageModel(imageUrl)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFFEAF3F0)),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "识物图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Outlined.ImageSearch,
                contentDescription = null,
                tint = Color(0xFF7A8A85),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryItemViewPreview() {
    MaterialTheme {
        HistoryItemView(
            record = RecognitionRecordEntity(
                sessionId = "s001",
                imageUrl = "",
                categoryJson = "手机",
                attributesJson = "{}",
                keywords = "Apple iPhone 15 Pro Max",
                confidence = 0.95,
                createdAt = System.currentTimeMillis()
            ),
            onClick = {}
        )
    }
}
