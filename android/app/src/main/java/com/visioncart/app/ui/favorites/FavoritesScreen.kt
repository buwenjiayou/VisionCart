package com.visioncart.app.ui.favorites

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.visioncart.app.data.db.FavoriteProductEntity
import com.visioncart.app.ui.viewmodel.MainViewModel
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun FavoritesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onProductClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()

    LaunchedEffect(Unit) { viewModel.syncFavorites() }

    if (favorites.isEmpty()) {
        Text(
            "暂无收藏商品",
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
            items(favorites, key = { it.productId }) { fav ->
                FavoriteItemView(
                    favorite = fav,
                    onRemove = { viewModel.toggleFavorite(
                        com.visioncart.app.data.ProductCard(
                            id = fav.productId,
                            title = fav.title,
                            imageUrl = fav.imageUrl,
                            price = fav.price,
                            originalPrice = fav.originalPrice,
                            platform = fav.platform,
                            selfOperated = false,
                            shopName = fav.shopName,
                            rating = fav.rating,
                            sales = fav.sales,
                            similarity = 0.0,
                            tags = emptyList(),
                            detailUrl = fav.detailUrl,
                            brand = fav.brand
                        )
                    ) },
                    onClick = { onProductClick(fav.detailUrl) }
                )
            }
        }
    }
}

@Composable
internal fun FavoriteItemView(
    favorite: FavoriteProductEntity,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            if (favorite.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = favorite.imageUrl,
                    contentDescription = favorite.title,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    favorite.title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${favorite.platform} | ${favorite.shopName}",
                    color = Color(0xFF53615E),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "¥${favorite.price}",
                        color = Color(0xFF0A7C66),
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "取消收藏",
                            tint = Color(0xFFE91E63),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FavoriteItemViewPreview() {
    MaterialTheme {
        FavoriteItemView(
            favorite = FavoriteProductEntity(
                productId = "1001",
                title = "Sony WH-1000XM5 无线降噪头戴耳机",
                imageUrl = "",
                price = 2299.0,
                originalPrice = 2999.0,
                platform = "京东",
                shopName = "Sony 自营旗舰店",
                rating = 4.9,
                sales = 30000,
                detailUrl = "",
                sessionId = "s001"
            ),
            onRemove = {},
            onClick = {}
        )
    }
}
