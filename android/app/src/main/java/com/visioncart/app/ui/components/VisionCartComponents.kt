package com.visioncart.app.ui.components

import androidx.compose.foundation.clickable
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.visioncart.app.data.AttributeValue
import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.RecognitionCandidate
import com.visioncart.app.data.SearchFilter
import com.visioncart.app.data.SuggestionCard
import com.visioncart.app.ui.viewmodel.UiState
import androidx.compose.ui.tooling.preview.Preview

// ==================== Loading & Error States ====================

@Composable
fun LoadingIndicator() {
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = androidx.compose.animation.core.FastOutLinearInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(
            Color(0xFFEAF3F0),
            Color(0xFFF8FBF9),
            Color(0xFFEAF3F0)
        ),
        start = androidx.compose.ui.geometry.Offset(10f, 10f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, translateAnim)
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Spacer(modifier = Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)).background(brush))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        Spacer(modifier = Modifier.height(20.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(brush))
                        Spacer(modifier = Modifier.height(16.dp).fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)).background(brush))
                        Spacer(modifier = Modifier.height(16.dp).fillMaxWidth(0.4f).clip(RoundedCornerShape(4.dp)).background(brush))
                    }
                }
            }
        }
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
    onAttributeClick: (String, String) -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFEAF3F0), shape = CircleShape) {
                    Icon(
                        Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        tint = Color(0xFF0A7C66),
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "识别结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10201C)
                    )
                    Text(
                        when (state) {
                            is UiState.Idle -> "等待图片输入"
                            is UiState.Loading -> "正在分析商品特征"
                            is UiState.Error -> "识别遇到问题"
                            is UiState.Success -> categoryText.ifBlank { "已识别商品" }
                        },
                        color = Color(0xFF687A75),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            when (state) {
                is UiState.Loading -> {
                    Surface(color = Color(0xFFF3F8F6), shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color(0xFF0A7C66),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在识别中...", color = Color(0xFF53615E), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is UiState.Error -> {
                    Surface(color = Color(0xFFFFF1F1), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "识别失败",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFD32F2F),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(state.message, color = Color(0xFF7A5A5A), style = MaterialTheme.typography.bodySmall)
                            if (onRetry != null) {
                                TextButton(onClick = onRetry) {
                                    Text("重试", color = Color(0xFF0A7C66), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    Surface(color = Color(0xFFF3F8F6), shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF0A7C66),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "置信度 ${(state.data.overallConfidence * 100).toInt()}%",
                                color = Color(0xFF0A7C66),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    attributes.forEach { (name, value) ->
                        Surface(
                            color = Color(0xFFFAFCFB),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAttributeClick(name, value.value) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = Color(0xFF53615E), style = MaterialTheme.typography.bodyMedium)
                                    if (value.verified) {
                                        Spacer(Modifier.width(5.dp))
                                        Text("✓", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${value.value}  ${(value.confidence * 100).toInt()}%",
                                        color = Color(0xFF10201C),
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("✎", color = Color(0xFF9EAAA6), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                is UiState.Idle -> {
                    Surface(color = Color(0xFFF7F9F8), shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "拍照或选择图片开始识别",
                            color = Color(0xFF7A8A85),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MultiProductSelectionPanel(
    candidates: List<RecognitionCandidate>,
    onSelect: (RecognitionCandidate) -> Unit
) {
    if (candidates.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "选择要识别的商品",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF10201C)
            )
            Text(
                "检测到 ${candidates.size} 个商品",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF687A75)
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                candidates.forEach { candidate ->
                    Surface(
                        modifier = Modifier
                            .width(150.dp)
                            .clickable { onSelect(candidate) },
                        color = Color(0xFFF8FBF9),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncImage(
                                model = candidate.previewImageUrl,
                                contentDescription = candidate.category,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(112.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEAF3F0)),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                candidate.category.ifBlank { "商品" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF10201C)
                            )
                            Text(
                                candidate.brand?.takeIf { it.isNotBlank() } ?: "品牌未知",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF687A75)
                            )
                            Text(
                                "置信度 ${(candidate.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF0A7C66),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().bounceClick(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Product image
            if (product.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.title,
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEAF3F0)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        tint = Color(0xFF0A7C66),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val brand = product.brand?.takeIf { it.isNotBlank() }
                Text(
                    product.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (brand != null) {
                        Text(
                            brand,
                            color = Color(0xFF8A5A00),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFFF3D8))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        product.platform,
                        color = Color(0xFF0A7C66),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        " · ${product.shopName}",
                        color = Color(0xFF687A75),
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEAF3F0))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
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
                            style = MaterialTheme.typography.titleLarge
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
                        salesText(product),
                        color = Color(0xFF687A75),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (product.tags.isNotEmpty()) {
                        product.tags.take(2).forEach { tag ->
                            Text(
                                tag,
                                color = Color(0xFF53615E),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFF3F8F6))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun salesText(product: ProductCard): String {
    return product.salesLabel?.takeIf { it.isNotBlank() }
        ?: if (product.sales > 0) "销量 ${formatSales(product.sales)}" else "销量未知"
}

private fun formatSales(sales: Long): String {
    return when {
        sales >= 10000 -> String.format(Locale.US, "%,.1f万", sales / 10000.0)
        sales >= 1000 -> String.format(Locale.US, "%,.1f千", sales / 1000.0)
        else -> sales.toString()
    }
}

// ==================== Suggestion Chips ====================

@Composable
fun SuggestionChipsRow(
    cards: List<SuggestionCard>,
    onCardClick: (SuggestionCard) -> Unit
) {
    val displayCards = cards
        .asSequence()
        .filter { it.title.isNotBlank() }
        .distinctBy { it.action.ifBlank { it.title } }
        .sortedByDescending { it.priority }
        .take(4)
        .toList()
    if (displayCards.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFF0A7C66), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("智能建议", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF10201C))
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            displayCards.forEach { card ->
                val iconLabel = suggestionIconLabel(card.icon)
                val label = suggestionChipLabel(card)
                AssistChip(
                    onClick = { onCardClick(card) },
                    leadingIcon = {
                        Text(
                            iconLabel,
                            color = Color(0xFF0A7C66),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    label = {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.widthIn(max = 176.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color.White,
                        labelColor = Color(0xFF0A7C66)
                    )
                )
            }
        }
    }
}

private fun suggestionChipLabel(card: SuggestionCard): String {
    val title = card.title.trim()
    val subtitle = card.subtitle?.trim().orEmpty()
    if (subtitle.isBlank() || subtitle == title) return title
    return "$title · $subtitle"
}

private fun suggestionIconLabel(icon: String): String {
    return when (icon.trim().lowercase()) {
        "money", "price", "coupon" -> "省"
        "shield", "official" -> "正"
        "palette", "similar" -> "似"
        "star", "rating" -> "评"
        "filter", "tune" -> "筛"
        "" -> "筛"
        else -> icon.trim().take(2).ifBlank { "筛" }
    }
}

// ==================== NLP Input ====================

@Composable
fun NlpInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null, tint = Color(0xFF0A7C66)) },
            placeholder = { Text("追加筛选") },
            trailingIcon = {
                if (value.isNotBlank()) {
                    IconButton(onClick = onSubmit) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = Color(0xFF0A7C66))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            maxLines = 1
        )
    }
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
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("筛选", color = Color(0xFF687A75), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                parts.forEach { part ->
                    AssistChip(
                        onClick = {},
                        label = { Text(part, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(999.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xFFEAF3F0),
                            labelColor = Color(0xFF0A7C66)
                        )
                    )
                }
            }
            TextButton(onClick = onClear) {
                Text("清除", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ==================== Previews ====================

@Preview(showBackground = true)
@Composable
private fun LoadingIndicatorPreview() {
    MaterialTheme { LoadingIndicator() }
}

@Preview(showBackground = true)
@Composable
private fun ErrorMessagePreview() {
    MaterialTheme { ErrorMessage("网络连接失败，请重试") {} }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    MaterialTheme { EmptyState() }
}

@Preview(showBackground = true)
@Composable
private fun ProductCardViewPreview() {
    MaterialTheme {
        ProductCardView(
            product = ProductCard(
                id = "1",
                title = "Apple iPhone 15 Pro Max 256GB 原色钛金属",
                imageUrl = "",
                price = 9299.0,
                originalPrice = 9999.0,
                platform = "京东",
                selfOperated = true,
                shopName = "Apple 自营旗舰店",
                rating = 4.9,
                sales = 50000,
                similarity = 0.95,
                tags = listOf("热卖", "正品"),
                detailUrl = ""
            ),
            isFavorite = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecognitionPanelSuccessPreview() {
    MaterialTheme {
        RecognitionPanel(
            categoryText = "手机",
            attributes = mapOf(
                "品牌" to AttributeValue("Apple", 0.98, true),
                "颜色" to AttributeValue("原色钛金属", 0.92, true),
                "存储" to AttributeValue("256GB", 0.85, false)
            ),
            state = UiState.Success(
                com.visioncart.app.data.RecognitionResult(
                    category = com.visioncart.app.data.CategoryDto("手机", "智能手机", "旗舰手机", 0.95),
                    overallConfidence = 0.95,
                    attributes = emptyMap(),
                    keywords = emptyList(),
                    sessionId = ""
                )
            ),
            onAttributeClick = { _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NlpInputBarPreview() {
    MaterialTheme {
        NlpInputBar(value = "", onValueChange = {}, onSubmit = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SuggestionChipsRowPreview() {
    MaterialTheme {
        SuggestionChipsRow(
            cards = listOf(
                SuggestionCard(id = "1", title = "手机", subtitle = "热门", icon = "📱", action = "search", priority = 1),
                SuggestionCard(id = "2", title = "笔记本", subtitle = "推荐", icon = "💻", action = "search", priority = 2),
                SuggestionCard(id = "3", title = "耳机", subtitle = "新品", icon = "🎧", action = "search", priority = 3)
            ),
            onCardClick = {}
        )
    }
}
