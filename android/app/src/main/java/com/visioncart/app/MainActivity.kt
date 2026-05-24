package com.visioncart.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.visioncart.app.data.ApiClient
import com.visioncart.app.data.TokenManager
import com.visioncart.app.data.db.FavoriteProductEntity
import com.visioncart.app.data.repository.VisionCartRepository
import com.visioncart.app.ui.auth.LoginScreen
import com.visioncart.app.ui.camera.CameraScreen
import com.visioncart.app.ui.components.*
import com.visioncart.app.ui.detail.ProductDetailScreen
import com.visioncart.app.ui.favorites.FavoritesScreen
import com.visioncart.app.ui.history.HistoryScreen
import com.visioncart.app.ui.viewmodel.MainViewModel
import com.visioncart.app.ui.viewmodel.UiState
import com.visioncart.app.overlay.FloatingWindowService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = VisionCartRepository(applicationContext)
        setContent {
            MaterialTheme {
                VisionCartApp(
                    repository = repository,
                    onOverlay = {
                        if (!Settings.canDrawOverlays(this)) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } else {
                            startService(Intent(this, FloatingWindowService::class.java))
                        }
                    }
                )
            }
        }
    }
}

// ==================== Navigation Routes ====================

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Favorites : Screen("favorites")
    data object History : Screen("history")
    data object ProductDetail : Screen("detail/{url}/{title}") {
        fun createRoute(url: String, title: String) = "detail/${android.net.Uri.encode(url)}/${android.net.Uri.encode(title)}"
    }
}

// ==================== Main App ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionCartApp(
    repository: VisionCartRepository,
    onOverlay: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var userEmail by remember { mutableStateOf("") }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var detailUrl by remember { mutableStateOf("") }
    var detailTitle by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // Check saved token on startup — auto login
    LaunchedEffect(Unit) {
        val token = TokenManager.getToken(context)
        if (!token.isNullOrBlank()) {
            ApiClient.authToken = token
            userEmail = TokenManager.getEmail(context) ?: ""
            isLoggedIn = true
        }
        isChecking = false
    }

    // Show loading while checking token
    if (isChecking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF0A7C66))
        }
        return
    }

    // Show login screen if not logged in
    if (!isLoggedIn) {
        LoginScreen(
            context = context,
            onLoginSuccess = {
                isLoggedIn = true
                scope.launch {
                    userEmail = TokenManager.getEmail(context) ?: ""
                }
            }
        )
        return
    }

    // ========== Logged in UI ==========

    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.Factory(repository)
    )
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    // Show toast messages
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearToast()
        }
    }

    // Attribute correction dialog state
    var showAttributeDialog by remember { mutableStateOf(false) }
    var correctingAttribute by remember { mutableStateOf("") }
    var correctingCurrentValue by remember { mutableStateOf("") }
    var attributeOptions by remember { mutableStateOf(listOf<String>()) }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.analyzeImage(it) }
    }

    // Navigate to screen
    fun navigate(screen: Screen) {
        when (screen) {
            is Screen.ProductDetail -> {
                detailUrl = screen.route
            }
            else -> currentScreen = screen
        }
    }

    // Handle attribute correction
    fun onAttributeClick(name: String, value: String) {
        correctingAttribute = name
        correctingCurrentValue = value
        // Load options from API
        val category = uiState.categoryText.split(" / ").lastOrNull() ?: ""
        if (category.isNotBlank()) {
            // Show local attribute suggestions while the remote options request is pending.
            attributeOptions = getDefaultOptions(name)
            showAttributeDialog = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentScreen == Screen.Home) {
                FloatingActionButton(
                    onClick = { navigate(Screen.Favorites) },
                    containerColor = Color(0xFF0A7C66)
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = "收藏", tint = Color.White)
                }
            }
        },
        topBar = {
            when (currentScreen) {
                Screen.Home -> {
                    TopAppBar(
                        title = {
                            Text(
                                "VisionCart AI 比价助手",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.White
                        ),
                        actions = {
                            // Show user email hint
                            if (userEmail.isNotBlank()) {
                                Text(
                                    userEmail,
                                    color = Color(0xFF757575),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(end = 4.dp, top = 2.dp)
                                )
                            }
                            IconButton(onClick = { navigate(Screen.History) }) {
                                Icon(Icons.Default.History, contentDescription = "历史")
                            }
                            IconButton(onClick = onOverlay) {
                                Icon(Icons.Outlined.Window, contentDescription = "悬浮窗")
                            }
                            IconButton(onClick = {
                                // Logout
                                scope.launch {
                                    try { ApiClient.api.logout() } catch (_: Exception) {}
                                    ApiClient.authToken = null
                                    TokenManager.clearToken(context)
                                    isLoggedIn = false
                                    currentScreen = Screen.Home
                                }
                            }) {
                                Icon(Icons.Default.Logout, contentDescription = "退出登录")
                            }
                        }
                    )
                }
                Screen.Favorites -> {
                    TopAppBar(
                        title = { Text("我的收藏") },
                        navigationIcon = {
                            IconButton(onClick = { navigate(Screen.Home) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    )
                }
                Screen.History -> {
                    TopAppBar(
                        title = { Text("识物历史") },
                        navigationIcon = {
                            IconButton(onClick = { navigate(Screen.Home) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    )
                }
                else -> {}
            }
        }
    ) { padding ->
        when (currentScreen) {
            Screen.Home -> {
                HomeScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    favorites = favorites,
                    onCameraClick = { navigate(Screen.Camera) },
                    onGalleryClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOverlay = onOverlay,
                    onAttributeClick = ::onAttributeClick,
                    onProductClick = { url, title ->
                        detailUrl = url
                        detailTitle = title
                        currentScreen = Screen.ProductDetail
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            Screen.Camera -> {
                CameraScreen(
                    onImageCaptured = { uri ->
                        viewModel.analyzeImage(uri)
                        navigate(Screen.Home)
                    },
                    onBack = { navigate(Screen.Home) }
                )
            }
            Screen.Favorites -> {
                FavoritesScreen(
                    viewModel = viewModel,
                    onBack = { navigate(Screen.Home) },
                    onProductClick = { url ->
                        detailUrl = url
                        detailTitle = ""
                        currentScreen = Screen.ProductDetail
                    }
                )
            }
            Screen.History -> {
                HistoryScreen(
                    viewModel = viewModel,
                    onBack = { navigate(Screen.Home) },
                    onItemClick = { /* TODO: reload history session */ }
                )
            }
            Screen.ProductDetail -> {
                ProductDetailScreen(
                    url = detailUrl,
                    title = detailTitle,
                    onBack = { navigate(Screen.Home) }
                )
            }
        }
    }

    // Attribute correction dialog
    if (showAttributeDialog) {
        AttributeCorrectionDialog(
            attributeName = correctingAttribute,
            currentValue = correctingCurrentValue,
            options = attributeOptions,
            onDismiss = { showAttributeDialog = false },
            onSelect = { newValue ->
                showAttributeDialog = false
                viewModel.correctAttribute(correctingAttribute, correctingCurrentValue, newValue)
            }
        )
    }
}

// ==================== Home Screen ====================

@Composable
private fun HomeScreen(
    uiState: com.visioncart.app.ui.viewmodel.MainUiState,
    viewModel: MainViewModel,
    favorites: List<FavoriteProductEntity>,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onOverlay: () -> Unit,
    onAttributeClick: (String, String) -> Unit,
    onProductClick: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nlpInput by remember { mutableStateOf("") }
    val favoriteIds = favorites.map { it.productId }.toSet()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F9F8))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Action Panel
        item {
            ActionPanel(
                onCameraClick = onCameraClick,
                onGalleryClick = onGalleryClick,
                onOverlay = onOverlay
            )
        }

        // Image Preview (if captured)
        uiState.imageUri?.let { uri ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(8.dp)
                    ) {
                        coil.compose.AsyncImage(
                            model = uri,
                            contentDescription = "拍摄的图片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }
                }
            }
        }

        // Recognition Panel
        item {
            RecognitionPanel(
                categoryText = uiState.categoryText,
                attributes = when (val s = uiState.recognitionState) {
                    is UiState.Success -> s.data.attributes
                    else -> emptyMap()
                },
                state = uiState.recognitionState,
                onAttributeClick = onAttributeClick
            )
        }

        // Suggestion Cards
        if (uiState.suggestionCards.isNotEmpty()) {
            item {
                SuggestionChipsRow(
                    cards = uiState.suggestionCards,
                    onCardClick = { viewModel.executeSuggestion(it) }
                )
            }
        }

        // Filter Summary
        item {
            FilterSummary(
                filter = uiState.currentFilter,
                onClear = {
                    // Reset filter and re-search
                }
            )
        }

        // NLP Input
        if (uiState.sessionId != null) {
            item {
                NlpInputBar(
                    value = nlpInput,
                    onValueChange = { nlpInput = it },
                    onSubmit = {
                        viewModel.parseNlp(nlpInput)
                        nlpInput = ""
                    }
                )
            }
        }

        // Products Loading
        if (uiState.productsLoading) {
            item { LoadingIndicator() }
        }

        // Products List
        if (uiState.products.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "找到 ${uiState.products.size} 件商品",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            items(uiState.products) { product ->
                ProductCardView(
                    product = product,
                    isFavorite = favoriteIds.contains(product.id),
                    onFavoriteClick = { viewModel.toggleFavorite(product) },
                    onClick = {
                        if (product.detailUrl.isNotBlank()) {
                            onProductClick(product.detailUrl, product.title)
                        }
                    }
                )
            }
        } else if (uiState.recognitionState is UiState.Success && !uiState.productsLoading) {
            item { EmptyState("暂未找到匹配商品") }
        }
    }
}

// ==================== Action Panel ====================

@Composable
private fun ActionPanel(
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onOverlay: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("拍照识物", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "拍摄或选择商品图片，AI 自动识别并全网比价",
                color = Color(0xFF53615E),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCameraClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A7C66))
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("拍照")
                }
                Button(onClick = onGalleryClick) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("相册")
                }
                Button(
                    onClick = onOverlay,
                    colors = ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(Icons.Outlined.Window, contentDescription = null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("悬浮窗")
                }
            }
        }
    }
}

// ==================== Default Options ====================

private fun getDefaultOptions(attribute: String): List<String> = when (attribute) {
    "颜色" -> listOf("黑色", "白色", "红色", "蓝色", "深蓝色", "藏青", "灰色", "绿色", "黄色", "粉色", "棕色", "米白色", "紫色", "橙色", "卡其色", "酒红色", "天蓝色", "墨绿色")
    "品牌" -> listOf("耐克", "阿迪达斯", "新百伦", "李宁", "安踏", "特步", "匡威", "VANS", "彪马", "亚瑟士", "斯凯奇", "乔丹", "安德玛", "斐乐", "彪马")
    "材质" -> listOf("网面", "飞织", "真皮", "PU皮", "帆布", "橡胶", "EVA", "Boost", "React", "Gore-Tex", "皮革", "合成革", "棉", "涤纶", "尼龙")
    "款式" -> listOf("跑鞋", "篮球鞋", "板鞋", "休闲鞋", "凉鞋", "拖鞋", "登山鞋", "足球鞋", "帆布鞋", "老爹鞋", "运动鞋", "小白鞋", "高帮", "低帮", "中帮")
    else -> listOf("选项1", "选项2", "选项3")
}
