package com.visioncart.app.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import com.visioncart.app.data.ApiClient
import com.visioncart.app.data.AttributeValue
import com.visioncart.app.data.NlpParseRequest
import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.RecognitionResult
import com.visioncart.app.data.SearchRequest
import com.visioncart.app.data.SuggestionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FloatingWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var currentParams: WindowManager.LayoutParams? = null

    // Drag state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showExpanded()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun showExpanded() {
        replaceView(320, 480, draggable = false) {
            OverlayPanel(
                onMinimize = { showDot() },
                onClose = { stopSelf() }
            )
        }
    }

    private fun showDot() {
        replaceView(56, 56, draggable = true) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .clickable { showExpanded() },
                color = Color(0xFF0A7C66)
            ) {
                Text(
                    "VC",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    private fun replaceView(widthDp: Int, heightDp: Int, draggable: Boolean, content: @Composable () -> Unit) {
        overlayView?.let { windowManager.removeView(it) }
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels

        val params = WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (draggable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 160
        }
        currentParams = params

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent { MaterialTheme { content() } }

            if (draggable) {
                setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        return when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                isDragging = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - initialTouchX
                                val dy = event.rawY - initialTouchY
                                if (dx * dx + dy * dy > 25) isDragging = true
                                if (isDragging) {
                                    params.x = initialX + dx.toInt()
                                    params.y = initialY + dy.toInt()
                                    windowManager.updateViewLayout(this@apply, params)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!isDragging) {
                                    // Treat as click
                                    showExpanded()
                                } else {
                                    // Snap to edge
                                    val edgeThreshold = 20 * density
                                    if (params.x < edgeThreshold) {
                                        params.x = 0
                                    } else if (params.x + (widthDp * density) > screenWidth - edgeThreshold) {
                                        params.x = screenWidth - (widthDp * density).toInt()
                                    }
                                    windowManager.updateViewLayout(this@apply, params)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                })
            }
        }
        windowManager.addView(overlayView, params)
    }
}

// ==================== Overlay Panel ====================

@Composable
private fun OverlayPanel(
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessionId by remember { mutableStateOf<String?>(null) }
    var recognitionResult by remember { mutableStateOf<RecognitionResult?>(null) }
    var products by remember { mutableStateOf<List<ProductCard>>(emptyList()) }
    var suggestionCards by remember { mutableStateOf<List<SuggestionCard>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("截图框选 → 云端识别 → 精简比价结果") }
    var nlpInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "VisionCart 悬浮识物",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    Button(
                        onClick = onMinimize,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF53615E)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                    ) { Text("收起", style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                    ) { Text("关闭", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // Status
            Text(statusText, color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)

            // Recognition result
            recognitionResult?.let { result ->
                Text(
                    "${result.category.level1}/${result.category.level2}/${result.category.level3}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                val attrsText = result.attributes.entries.joinToString(" ") { "${it.key}:${it.value.value}" }
                Text(attrsText, color = Color(0xFF53615E), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // Suggestion chips
            if (suggestionCards.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestionCards.take(3).forEach { card ->
                        Surface(
                            modifier = Modifier.clickable {
                                scope.launch {
                                    executeSuggestionAction(
                                        sessionId = sessionId ?: return@launch,
                                        action = card.action,
                                        onResult = { newProducts, newCards ->
                                            products = newProducts
                                            suggestionCards = newCards
                                        }
                                    )
                                }
                            },
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFFF0F7F5)
                        ) {
                            Text(
                                "${card.icon}${card.title}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color(0xFF0A7C66),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Loading
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("处理中...", color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Product list (compact)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(products.take(5)) { product ->
                    CompactProductCard(product)
                }
            }

            // NLP input
            androidx.compose.material3.OutlinedTextField(
                value = nlpInput,
                onValueChange = { nlpInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("追加筛选", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                trailingIcon = {
                    if (nlpInput.isNotBlank()) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val sid = sessionId ?: return@TextButton
                                scope.launch {
                                    isLoading = true
                                    try {
                                        val result = ApiClient.api.parseNlp(
                                            NlpParseRequest(sessionId = sid, userInput = nlpInput)
                                        )
                                        if (result.code == 200 && result.data != null) {
                                            // Re-search with new filter
                                            val searchResult = ApiClient.api.searchProducts(
                                                SearchRequest(
                                                    sessionId = sid,
                                                    attributes = emptyMap(),
                                                    filter = result.data.filter
                                                )
                                            )
                                            if (searchResult.code == 200 && searchResult.data != null) {
                                                products = searchResult.data.products
                                                suggestionCards = searchResult.data.suggestionCards
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    isLoading = false
                                    nlpInput = ""
                                }
                            }
                        ) { Text("搜", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            )
        }
    }
}

@Composable
private fun CompactProductCard(product: ProductCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9F8))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (product.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    product.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Row {
                    Text(
                        "¥${product.price}",
                        color = Color(0xFF0A7C66),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        product.platform,
                        color = Color(0xFF53615E),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (product.selfOperated) {
                        Spacer(Modifier.width(4.dp))
                        Text("自营", color = Color(0xFF0A7C66), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private suspend fun executeSuggestionAction(
    sessionId: String,
    action: String,
    onResult: (List<ProductCard>, List<SuggestionCard>) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.api.executeSuggestion(
                com.visioncart.app.data.SuggestionExecuteRequest(sessionId, action)
            )
            if (response.code == 200 && response.data != null) {
                onResult(response.data.products, response.data.cards)
            }
        } catch (_: Exception) {}
    }
}
