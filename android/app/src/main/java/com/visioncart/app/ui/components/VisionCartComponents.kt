package com.visioncart.app.ui.components

import androidx.compose.foundation.clickable
import com.visioncart.app.ui.components.bounceClick
import com.visioncart.app.ui.components.rememberAuthenticatedImageModel
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun EmptyState(
    message: String = "暂无数据",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            message,
            color = Color(0xFF9E9E9E),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

// ==================== Recognition Panel ====================

@Composable
fun RecognitionPanel(
    categoryText: String,
    attributes: Map<String, AttributeValue>,
    state: UiState<com.visioncart.app.data.RecognitionResult>,
    onAttributeClick: (String, String) -> Unit,
    onRetry: (() -> Unit)? = null,
    progressStep: String? = null,
    confidenceHint: String? = null
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
                            is UiState.Loading -> progressStep ?: "正在分析商品特征"
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
                            Text(
                                progressStep ?: "正在识别中...",
                                color = Color(0xFF53615E),
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                    // 置信度提示（中/低置信度时显示）
                    if (!confidenceHint.isNullOrBlank()) {
                        Surface(
                            color = Color(0xFFFFF8E1),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚠", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    confidenceHint,
                                    color = Color(0xFFE65100),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // 左侧：属性名 + ✓
                                Column(
                                    modifier = Modifier.width(80.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            name,
                                            color = Color(0xFF53615E),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (value.verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                // 右侧：值 + 置信度 + 编辑
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            value.value,
                                            color = Color(0xFF10201C),
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${(value.confidence * 100).toInt()}%",
                                            color = Color(0xFF9EAAA6),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "编辑",
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFF9EAAA6)
                                    )
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
                            val cropModel = rememberAuthenticatedImageModel(candidate.previewImageUrl)
                            AsyncImage(
                                model = cropModel,
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
    showRating: Boolean = false,
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
            val imageModel = rememberAuthenticatedImageModel(product.imageUrl)
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
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
                val reputationText = if (showRating) productReputationDisplayText(product) else null
                if (!reputationText.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            reputationText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color(0xFFEF6C00),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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

// ==================== Decision Suggestions ====================

@Composable
fun SuggestionChipsRow(
    cards: List<SuggestionCard>,
    onCardClick: (SuggestionCard) -> Unit,
    maxCards: Int = 6,
    compact: Boolean = false
) {
    val displayCards = cards
        .asSequence()
        .filter { it.title.isNotBlank() }
        .filterNot { it.id.startsWith("insight_") }
        .distinctBy { it.action.ifBlank { it.title } }
        .sortedByDescending { it.priority }
        .take(maxCards)
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            displayCards.forEach { card ->
                DecisionSuggestionCard(
                    card = card,
                    compact = compact,
                    onClick = { onCardClick(card) }
                )
            }
        }
    }
}

// ==================== AI Guide Insight Card ====================

@Composable
fun GuideInsightCard(
    card: SuggestionCard,
    onClick: (SuggestionCard) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FDFC)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: badge + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF0A7C66).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        card.badge?.takeIf { it.isNotBlank() } ?: "AI 导购分析",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0A7C66),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10201C),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Body: reason / subtitle
            Text(
                card.reason?.takeIf { it.isNotBlank() } ?: card.subtitle ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF53615E),
                lineHeight = 18.sp
            )

            // Action button
            androidx.compose.material3.Button(
                onClick = { onClick(card) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0A7C66)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    card.actionLabel?.takeIf { it.isNotBlank() } ?: "应用建议",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun DecisionSuggestionCard(
    card: SuggestionCard,
    compact: Boolean,
    onClick: () -> Unit
) {
    val colors = suggestionToneColors(card.tone)
    val width = if (compact) 178.dp else 224.dp
    val minHeight = if (compact) 108.dp else 132.dp
    Surface(
        modifier = Modifier
            .width(width)
            .heightIn(min = minHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = colors.container
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 26.dp else 30.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            suggestionIconLabel(card.icon),
                            color = colors.accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        card.title,
                        color = Color(0xFF10201C),
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                card.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                    Text(
                        badge,
                        color = colors.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = if (compact) 42.dp else 54.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                card.reason?.takeIf { it.isNotBlank() } ?: card.subtitle.orEmpty(),
                color = Color(0xFF53615E),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (compact) 2 else 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    card.metric?.takeIf { it.isNotBlank() } ?: card.subtitle.orEmpty(),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    card.actionLabel?.takeIf { it.isNotBlank() } ?: "应用",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private data class SuggestionToneColors(
    val container: Color,
    val accent: Color
)

private fun suggestionToneColors(tone: String?): SuggestionToneColors {
    return when (tone?.trim()?.lowercase()) {
        "saving" -> SuggestionToneColors(Color(0xFFFFF7E8), Color(0xFFB66100))
        "trust" -> SuggestionToneColors(Color(0xFFEAF5FF), Color(0xFF1D63A3))
        "popularity" -> SuggestionToneColors(Color(0xFFFFEEF2), Color(0xFFC33A58))
        "filter" -> SuggestionToneColors(Color(0xFFF0F7F5), Color(0xFF0A7C66))
        "warning" -> SuggestionToneColors(Color(0xFFFFF3E0), Color(0xFFE65100))
        else -> SuggestionToneColors(Color(0xFFF4F6FA), Color(0xFF53615E))
    }
}

private fun suggestionIconLabel(icon: String): String {
    return when (icon.trim().lowercase()) {
        "money", "price", "coupon", "wallet" -> "省"
        "shield", "official" -> "正"
        "palette", "similar" -> "似"
        "star", "rating" -> "评"
        "trending", "sales" -> "热"
        "brand" -> "牌"
        "platform" -> "台"
        "spark" -> "荐"
        "filter", "tune" -> "筛"
        "discount" -> "折"
        "truck" -> "邮"
        "trophy" -> "优"
        "bell" -> "铃"
        "compare" -> "比"
        "warning" -> "⚠"
        "" -> "筛"
        else -> icon.trim().take(2).ifBlank { "筛" }
    }
}

@Composable
fun SortOptionsRow(
    filter: SearchFilter,
    onSortSelected: (sortBy: String?, sortOrder: String) -> Unit
) {
    val options = listOf(
        SortOption("综合推荐", null, "desc"),
        SortOption("价格低到高", "price", "asc"),
        SortOption("销量优先", "sales", "desc"),
        SortOption("口碑优先", "review_quality", "desc")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFF53615E), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("排序方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF10201C))
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val selected = canonicalSortBy(option.sortBy) == canonicalSortBy(filter.sortBy) &&
                        (option.sortBy == null || option.sortOrder.equals(filter.sortOrder, ignoreCase = true))
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(min = 86.dp)
                        .clickable { onSortSelected(option.sortBy, option.sortOrder) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) Color(0xFF0A7C66) else Color.White
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            option.label,
                            color = if (selected) Color.White else Color(0xFF53615E),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private data class SortOption(
    val label: String,
    val sortBy: String?,
    val sortOrder: String
)

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
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() })
        )
    }
}

@Composable
fun NlpFilteringBar(message: String, onCancel: () -> Unit) {
    Surface(
        color = Color(0xFFEAF3F0),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF0A7C66)
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF0A7C66),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("取消", style = MaterialTheme.typography.labelSmall, color = Color(0xFF687A75))
            }
        }
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
    var customValue by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修正「$attributeName」") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 自由输入行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { customValue = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入自定义值") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (customValue.isNotBlank()) onSelect(customValue.trim()) },
                        enabled = customValue.isNotBlank()
                    ) { Text("确定") }
                }
                // 选项列表
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
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
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ==================== Filter Summary ====================

internal fun filterTagDeleteTarget(fieldName: String?, tagId: String?, label: String): String {
    return fieldName ?: tagId ?: label
}

internal data class TagItem(val label: String, val fieldName: String?, val tagId: String? = null)

internal fun buildFilterSummaryTags(
    filter: SearchFilter,
    filterTags: List<String> = emptyList(),
    structuredFilterTags: List<com.visioncart.app.data.FilterTag> = emptyList(),
    deriveFromFilter: Boolean = true
): List<TagItem> {
    val tags = mutableListOf<TagItem>()

    if (structuredFilterTags.isNotEmpty()) {
        structuredFilterTags.forEach { st ->
            tags.add(TagItem(st.label, st.filterPath ?: st.id, st.id))
        }
        return tags
    }

    if (deriveFromFilter) {
        filter.priceRange.min?.let { tags.add(TagItem("≥¥$it", "price_range.min")) }
        filter.priceRange.max?.let { tags.add(TagItem("≤¥$it", "price_range.max")) }
        filter.platforms.take(2).forEach { tags.add(TagItem(it, "platforms.$it")) }
        filter.selfOperated?.let { if (it) tags.add(TagItem("自营", "self_operated")) }
        filter.colors.forEach { tags.add(TagItem(it, "colors.$it")) }
        filter.brands.forEach { tags.add(TagItem(it, "brands.$it")) }
        filter.ratingMin?.let { tags.add(TagItem("≥${it}分", "rating_min")) }
        filter.keyword?.let { if (it.isNotBlank() && !it.startsWith("!")) tags.add(TagItem(it, "keyword")) }
    }

    val existingLabels = tags.map { it.label }.toSet()
    filterTags.forEach { tag ->
        if (tag !in existingLabels) {
            tags.add(TagItem(tag, null))
        }
    }

    return tags
}

@Composable
fun FilterSummary(
    filter: SearchFilter,
    onClear: () -> Unit,
    onRemoveTag: ((String) -> Unit)? = null,
    filterTags: List<String> = emptyList(),
    structuredFilterTags: List<com.visioncart.app.data.FilterTag> = emptyList(),
    deriveFromFilter: Boolean = true,
    canUndo: Boolean = false,
    onUndo: (() -> Unit)? = null,
    keptPreviousResults: Boolean = false,
    statusMessage: String? = null
) {
    val tags = buildFilterSummaryTags(
        filter = filter,
        filterTags = filterTags,
        structuredFilterTags = structuredFilterTags,
        deriveFromFilter = deriveFromFilter
    )

    if (tags.isEmpty() && statusMessage == null) return

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            // Status message for keptPreviousResults or sparse results
            if (statusMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (keptPreviousResults) Color(0xFFE65100) else Color(0xFF687A75)
                    )
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (keptPreviousResults) Color(0xFFE65100) else Color(0xFF687A75),
                        modifier = Modifier.weight(1f)
                    )
                    if (canUndo && onUndo != null) {
                        TextButton(
                            onClick = onUndo,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("撤回", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0A7C66))
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
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
                        tags.forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = { Text(tag.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                shape = RoundedCornerShape(999.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (tag.fieldName == null) Color(0xFFFFF3E0) else Color(0xFFEAF3F0),
                                    labelColor = if (tag.fieldName == null) Color(0xFFE65100) else Color(0xFF0A7C66)
                                )
                            )
                        }
                    }
                    if (canUndo && onUndo != null && statusMessage == null) {
                        IconButton(onClick = onUndo, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "撤回", tint = Color(0xFF0A7C66), modifier = Modifier.size(18.dp))
                        }
                    }
                    TextButton(onClick = onClear) {
                        Text("清除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Filter status bar for showing persistent filter status with actionable buttons.
 * Shows status (applied/partial/rollback/expanded/failed) with explanation and CTA.
 */
@Composable
fun FilterStatusBar(
    status: FilterStatus,
    modifier: Modifier = Modifier,
    explanation: String?,
    keptPreviousResults: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onRelax: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    if (status == FilterStatus.NONE && explanation == null) return

    val (backgroundColor, textColor, icon) = when (status) {
        FilterStatus.APPLIED -> Triple(Color(0xFFEAF3F0), Color(0xFF0A7C66), Icons.Outlined.CheckCircle)
        FilterStatus.PARTIAL -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), Icons.Outlined.Info)
        FilterStatus.ROLLBACK -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), Icons.AutoMirrored.Outlined.Undo)
        FilterStatus.EXPANDED -> Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), Icons.Outlined.Search)
        FilterStatus.FAILED -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Outlined.Info)
        FilterStatus.NONE -> Triple(Color(0xFFF5F5F5), Color(0xFF687A75), Icons.Outlined.Info)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = textColor)
            Text(
                explanation ?: status.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            if (canUndo) {
                TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("撤回", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0A7C66))
                }
            }
            if (onRelax != null && (status == FilterStatus.PARTIAL || status == FilterStatus.ROLLBACK)) {
                TextButton(onClick = onRelax, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("放宽条件", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
                }
            }
            if (onRetry != null && status == FilterStatus.FAILED) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("重试", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC62828))
                }
            }
        }
    }
}

enum class FilterStatus(val displayName: String) {
    NONE("无筛选"),
    APPLIED("已应用筛选"),
    PARTIAL("部分匹配"),
    ROLLBACK("已回退"),
    EXPANDED("已扩展搜索"),
    FAILED("筛选失败")
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
