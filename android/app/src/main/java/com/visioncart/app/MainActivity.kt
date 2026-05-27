package com.visioncart.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Window
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigationRequests = MutableStateFlow<String?>(null)

    private var shouldStartOverlayAfterPermission = false

    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            FloatingWindowService.startScreenshot(this, result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntentExtras(intent)
        val repository = VisionCartRepository(applicationContext)
        setContent {
            com.visioncart.app.ui.theme.VisionCartTheme {
                VisionCartApp(
                    repository = repository,
                    onOverlay = { toggleOverlay() },
                    onRequestScreenshot = {
                        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    navigationRequests = navigationRequests,
                    onNavigationRequestHandled = { navigationRequests.value = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!shouldStartOverlayAfterPermission) return

        shouldStartOverlayAfterPermission = false
        if (Settings.canDrawOverlays(this)) {
            startFloatingWindowService()
        } else {
            Toast.makeText(this, "请授予悬浮窗权限后再开启", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent) {
        intent.getStringExtra("navigate")?.let { navigationRequests.value = it }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            shouldStartOverlayAfterPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (FloatingWindowService.isRunning.value) {
            stopFloatingWindowService()
            Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
        } else {
            startFloatingWindowService()
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFloatingWindowService() {
        startForegroundService(Intent(this, FloatingWindowService::class.java))
    }

    private fun stopFloatingWindowService() {
        stopService(Intent(this, FloatingWindowService::class.java))
    }
}

// ==================== Navigation Routes ====================

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Favorites : Screen("favorites")
    data object History : Screen("history")
    data object ProductDetail : Screen("detail?url={url}&title={title}") {
        fun createRoute(url: String, title: String) =
            "detail?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
    }
}

// ==================== Main App ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionCartApp(
    repository: VisionCartRepository,
    onOverlay: () -> Unit,
    onRequestScreenshot: () -> Unit = {},
    navigationRequests: StateFlow<String?>,
    onNavigationRequestHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var userEmail by remember { mutableStateOf("") }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route
    val snackbarHostState = remember { SnackbarHostState() }
    val isOverlayActive by FloatingWindowService.isRunning.collectAsState()

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

    // Handle navigation requests from the floating window service.
    LaunchedEffect(navController, isLoggedIn) {
        navigationRequests.collect { target ->
            if (target == null || !isLoggedIn) return@collect
            when (target) {
                Screen.Camera.route -> navController.navigate(Screen.Camera.route) { launchSingleTop = true }
                Screen.Favorites.route -> navController.navigate(Screen.Favorites.route) { launchSingleTop = true }
                Screen.History.route -> navController.navigate(Screen.History.route) { launchSingleTop = true }
                "screenshot" -> onRequestScreenshot()
                else -> Log.w("VisionCart", "Unknown navigation request: $target")
            }
            onNavigationRequestHandled()
        }
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
    val history by viewModel.history.collectAsState()

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

    fun navigate(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateHome(clearBackStack: Boolean = false) {
        navController.navigate(Screen.Home.route) {
            launchSingleTop = true
            if (clearBackStack) {
                popUpTo(Screen.Home.route) { inclusive = false }
            }
        }
    }

    fun navigateBackOrHome() {
        if (!navController.popBackStack()) {
            navigateHome()
        }
    }

    // Handle attribute correction
    fun onAttributeClick(name: String, value: String) {
        correctingAttribute = name
        correctingCurrentValue = value
        val category = uiState.categoryText.split(" / ").lastOrNull() ?: ""
        if (category.isNotBlank()) {
            // Show local attribute suggestions immediately
            attributeOptions = getDefaultOptions(name)
            showAttributeDialog = true
            // Load remote options asynchronously
            scope.launch {
                val result = repository.getAttributeOptions(category, name, uiState.sessionId)
                result.onSuccess { options ->
                    if (options.isNotEmpty()) {
                        attributeOptions = options
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentRoute == Screen.Home.route) {
                FloatingActionButton(
                    onClick = { navigate(Screen.Favorites.route) },
                    containerColor = Color(0xFF0A7C66)
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = "收藏", tint = Color.White)
                }
            }
        },
        topBar = {
            when (currentRoute) {
                Screen.Home.route -> {
                    TopAppBar(
                        title = {
                            Text(
                                "VisionCart",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10201C),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                fontSize = 14.sp
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFFF8FBF9),
                            titleContentColor = Color(0xFF10201C)
                        ),
                        actions = {
                            // Show user email hint
                            if (userEmail.isNotBlank()) {
                                Surface(
                                    color = Color(0xFFEAF3F0),
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        userEmail,
                                        color = Color(0xFF53615E),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .widthIn(max = 132.dp)
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = Color(0xFFEAF3F0),
                                    shape = CircleShape,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Person,
                                        contentDescription = "账号",
                                        tint = Color(0xFF0A7C66),
                                        modifier = Modifier.padding(6.dp).size(18.dp)
                                    )
                                }
                            }
                            IconButton(onClick = { navigate(Screen.History.route) }) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = "历史",
                                    tint = Color(0xFF53615E)
                                )
                            }
                            IconButton(onClick = { onOverlay() }) {
                                Icon(
                                    Icons.Outlined.Window,
                                    contentDescription = if (isOverlayActive) "关闭悬浮窗" else "开启悬浮窗",
                                    tint = if (isOverlayActive) Color(0xFF0A7C66) else Color(0xFF53615E)
                                )
                            }
                            IconButton(onClick = {
                                // Logout
                                scope.launch {
                                    try {
                                        ApiClient.api.logout()
                                    } catch (error: Exception) {
                                        Log.w("VisionCart", "Logout request failed; clearing local session anyway", error)
                                    }
                                    ApiClient.authToken = null
                                    TokenManager.clearToken(context)
                                    isLoggedIn = false
                                    navigateHome(clearBackStack = true)
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "退出登录",
                                    tint = Color(0xFF53615E)
                                )
                            }
                        }
                    )
                }
                Screen.Favorites.route -> {
                    TopAppBar(
                        title = { Text("我的收藏") },
                        navigationIcon = {
                            IconButton(onClick = { navigateBackOrHome() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    )
                }
                Screen.History.route -> {
                    TopAppBar(
                        title = { Text("识物历史") },
                        navigationIcon = {
                            IconButton(onClick = { navigateBackOrHome() }) {
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
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) + androidx.compose.animation.slideInVertically(initialOffsetY = { 50 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)) }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    favorites = favorites,
                    onCameraClick = { navigate(Screen.Camera.route) },
                    onGalleryClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOverlay = onOverlay,
                    onAttributeClick = ::onAttributeClick,
                    onProductClick = { url, title ->
                        navigate(Screen.ProductDetail.createRoute(url, title))
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            composable(Screen.Camera.route) {
                CameraScreen(
                    onImageCaptured = { uri ->
                        viewModel.analyzeImage(uri)
                        navigateHome(clearBackStack = true)
                    },
                    onBack = { navigateBackOrHome() }
                )
            }
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    viewModel = viewModel,
                    onBack = { navigateBackOrHome() },
                    onProductClick = { url ->
                        navigate(Screen.ProductDetail.createRoute(url, ""))
                    },
                    modifier = Modifier.padding(padding).background(Color(0xFFF8FBF9))
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    viewModel = viewModel,
                    onBack = { navigateBackOrHome() },
                    onItemClick = { sessionId ->
                        val record = history.find { it.sessionId == sessionId }
                        if (record != null) {
                            viewModel.restoreFromHistory(record)
                            navigateHome(clearBackStack = true)
                        }
                    },
                    modifier = Modifier.padding(padding).background(Color(0xFFF8FBF9))
                )
            }
            composable(
                route = Screen.ProductDetail.route,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                ProductDetailScreen(
                    url = entry.arguments?.getString("url").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onBack = { navigateBackOrHome() }
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
            .background(Color(0xFFF8FBF9)),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HomeDashboardHeader(
                recognizedCount = uiState.products.size,
                favoriteCount = favorites.size,
                isRecognizing = uiState.recognitionState is UiState.Loading || uiState.productsLoading
            )
        }

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
                onAttributeClick = onAttributeClick,
                onRetry = uiState.imageUri?.let { uri ->
                    { viewModel.analyzeImage(uri) }
                }
            )
        }

        if (uiState.multiProductCandidates.isNotEmpty()) {
            item {
                MultiProductSelectionPanel(
                    candidates = uiState.multiProductCandidates,
                    onSelect = { viewModel.selectRecognitionCandidate(it) }
                )
            }
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
                    viewModel.clearFilter()
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
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "匹配商品",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10201C)
                    )
                    Text(
                        "${uiState.products.size} 件结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF687A75)
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
            item { EmptyState("没有高相关商品，试试纠正品牌或放宽筛选条件") }
        }
    }
}

// ==================== Action Panel ====================

@Composable
private fun HomeDashboardHeader(
    recognizedCount: Int,
    favoriteCount: Int,
    isRecognizing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        com.visioncart.app.ui.theme.BrandGradientStart,
                        com.visioncart.app.ui.theme.BrandGradientEnd
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF0A7C66), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(23.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "智能识物比价",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isRecognizing) "正在为你整理识别结果" else "拍照、截屏或从相册开始",
                        color = Color(0xFFBFD5CF),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetricChip(
                    label = "当前结果",
                    value = if (recognizedCount > 0) "${recognizedCount}件" else "待识别",
                    modifier = Modifier.weight(1f)
                )
                HomeMetricChip(label = "收藏", value = "${favoriteCount}件", modifier = Modifier.weight(1f))
                HomeMetricChip(label = "模式", value = "悬浮可用", modifier = Modifier.weight(1f))
            }
        }
        }
    }
}

@Composable
private fun HomeMetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = Color(0xFFBFD5CF), style = MaterialTheme.typography.labelSmall)
            Text(value, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActionPanel(
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onOverlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "选择识别方式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10201C)
                    )
                    Text(
                        "识别完成后会自动生成筛选建议",
                        color = Color(0xFF687A75),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(color = Color(0xFFEAF3F0), shape = CircleShape) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Color(0xFF0A7C66),
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
            }
            Button(
                onClick = onCameraClick,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A7C66))
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("拍照识物")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryActionButton(
                    label = "相册",
                    icon = Icons.Outlined.PhotoLibrary,
                    onClick = onGalleryClick,
                    modifier = Modifier.weight(1f)
                )
                SecondaryActionButton(
                    label = "悬浮窗",
                    icon = Icons.Outlined.Window,
                    onClick = onOverlay,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0A7C66))
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
}

// ==================== Default Options ====================

private fun getDefaultOptions(attribute: String): List<String> = when (attribute) {
    "颜色" -> listOf("黑色", "白色", "红色", "蓝色", "深蓝色", "藏青", "灰色", "绿色", "黄色", "粉色", "棕色", "米白色", "紫色", "橙色", "卡其色", "酒红色", "天蓝色", "墨绿色")
    "品牌" -> listOf("罗技", "雷蛇", "卓威", "富勒", "雷神", "英菲克", "Apple", "华为", "小米", "未知")
    "材质" -> listOf("网面", "飞织", "真皮", "PU皮", "帆布", "橡胶", "EVA", "Boost", "React", "Gore-Tex", "皮革", "合成革", "棉", "涤纶", "尼龙")
    "款式" -> listOf("跑鞋", "篮球鞋", "板鞋", "休闲鞋", "凉鞋", "拖鞋", "登山鞋", "足球鞋", "帆布鞋", "老爹鞋", "运动鞋", "小白鞋", "高帮", "低帮", "中帮")
    else -> listOf("选项1", "选项2", "选项3")
}
