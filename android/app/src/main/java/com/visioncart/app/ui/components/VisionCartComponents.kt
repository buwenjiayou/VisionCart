package com.visioncart.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.visioncart.app.data.AttributeValue
import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.SearchFilter
import com.visioncart.app.data.SuggestionCard
import com.visioncart.app.ui.viewmodel.UiState

// ==================== Loading & Error States ====================

@Composable
fun LoadingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️ $message", color = Color(0xFFD32F2F))
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
fun EmptyState(message: String = "暂无数据") {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        color = Color(0xFF9E9E9E),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

// ==================== Recognition Panel ====================

@Composable
fun RecognitionPanel(
    categoryText: String,
    attributes: Map<String, AttributeValue>,
    state: UiState<com.visioncart.app.data.RecognitionResult>,
    onAttributeClick: (String, String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                is UiState.Loading -> {
                    Text("正在识别中...", style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                is UiState.Error -> {
                    Text("识别失败", style = MaterialTheme.typography.titleMedium, color = Color(0xFFD32F2F))
                    Text(state.message, color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)
                }
                is UiState.Success -> {
                    Text(
                        "识别结果：$categoryText",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "置信度 ${(state.data.overallConfidence * 100).toInt()}%",
                        color = Color(0xFF757575),
                        style = MaterialTheme.typography.bodySmall
                    )
                    attributes.forEach { (name, value) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onAttributeClick(name, value.value) },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = Color(0xFF53615E))
                                if (value.verified) {
                                    Spacer(Modifier.width(4.dp))
                                    Text("✓", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${value.value}  ${(value.confidence * 100).toInt()}%",
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("✎", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                is UiState.Idle -> {
                    Text("拍照或选择图片开始识别", color = Color(0xFF9E9E9E))
                }
            }
        }
    }
}

// ==================== Product Card ====================

@Composable
fun ProductCardView(
    product: ProductCard,
    isFavorite: Boolean = false,
    onFavoriteClick: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Product image
            if (product.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.title,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    product.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${product.platform} | ${product.shopName}",
                        color = Color(0xFF53615E),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (product.selfOperated) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "自营",
                            color = Color(0xFF0A7C66),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "¥${product.price}",
                            color = Color(0xFF0A7C66),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (product.originalPrice != null && product.originalPrice > product.price) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "¥${product.originalPrice}",
                                color = Color(0xFF9E9E9E),
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                        }
                    }
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (isFavorite) Color(0xFFE91E63) else Color(0xFF9E9E9E),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "⭐ ${product.rating}",
                        color = Color(0xFF757575),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "销量 ${formatSales(product.sales)}",
                        color = Color(0xFF757575),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (product.tags.isNotEmpty()) {
                        product.tags.take(2).forEach { tag ->
                            Text(
                                tag,
                                color = Color(0xFF53615E),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSales(sales: Long): String {
    return when {
        sales >= 10000 -> "${"%,.1f".format(sales / 10000.0)}万"
        sales >= 1000 -> "${"%,.1f".format(sales / 1000.0)}千"
        else -> sales.toString()
    }
}

// ==================== Suggestion Chips ====================

@Composable
fun SuggestionChipsRow(
    cards: List<SuggestionCard>,
    onCardClick: (SuggestionCard) -> Unit
) {
    if (cards.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { card ->
            AssistChip(
                onClick = { onCardClick(card) },
                label = { Text("${card.icon} ${card.title}") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFFF0F7F5),
                    labelColor = Color(0xFF0A7C66)
                )
            )
        }
    }
}

// ==================== NLP Input ====================

@Composable
fun NlpInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null) },
        label = { Text("自然语言追加筛选") },
        placeholder = { Text("1000元以内黑色款，要评价4.8分以上，按销量排") },
        trailingIcon = {
            if (value.isNotBlank()) {
                TextButton(onClick = onSubmit) { Text("搜索") }
            }
        },
        maxLines = 2
    )
}

// ==================== Attribute Correction Dialog ====================

@Composable
fun AttributeCorrectionDialog(
    attributeName: String,
    currentValue: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修正「$attributeName」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    val isSelected = option == currentValue
                    TextButton(
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            option,
                            color = if (isSelected) Color(0xFF0A7C66) else Color.Unspecified,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ==================== Filter Summary ====================

@Composable
fun FilterSummary(filter: SearchFilter, onClear: () -> Unit) {
    val parts = mutableListOf<String>()
    filter.priceRange.min?.let { parts.add("≥¥$it") }
    filter.priceRange.max?.let { parts.add("≤¥$it") }
    filter.platforms.take(2).forEach { parts.add(it) }
    filter.selfOperated?.let { if (it) parts.add("自营") }
    filter.ratingMin?.let { parts.add("≥${it}分") }
    filter.sortBy?.let { sort ->
        val label = when (sort) {
            "price" -> if (filter.sortOrder == "asc") "价格低→高" else "价格高→低"
            "sales" -> "销量排序"
            "rating" -> "评分排序"
            else -> sort
        }
        parts.add(label)
    }

    if (parts.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("筛选:", color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)
        parts.forEach { part ->
            AssistChip(
                onClick = {},
                label = { Text(part, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFFE8F5E9),
                    labelColor = Color(0xFF2E7D32)
                )
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClear) {
            Text("清除", style = MaterialTheme.typography.labelSmall)
        }
    }
}
