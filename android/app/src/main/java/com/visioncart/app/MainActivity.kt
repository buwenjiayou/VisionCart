package com.visioncart.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.visioncart.app.data.PriceAlertWorker
import com.visioncart.app.overlay.FloatingWindowService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigationRequests = MutableStateFlow<String?>(null)

    private var shouldStartOverlayAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntentExtras(intent)
        PriceAlertWorker.schedule(this)
        val repository = VisionCartRepository(applicationContext)
        setContent {
            com.visioncart.app.ui.theme.VisionCartTheme {
                VisionCartApp(
                    repository = repository,
                    onOverlay = { toggleOverlay() },
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
            Toast.makeText(this, getString(R.string.float_window_permission_required), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.float_ball_off), Toast.LENGTH_SHORT).show()
        } else {
            startFloatingWindowService()
            Toast.makeText(this, getString(R.string.float_ball_on), Toast.LENGTH_SHORT).show()
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

private fun shouldShowReputation(sortBy: String?): Boolean {
    return sortBy == "rating" || sortBy == "reviews" || sortBy == "review_quality" ||
        sortBy == "rating_desc" || sortBy == "shop_trust" || sortBy == "seller_trust"
}

// ==================== Main App ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionCartApp(
    repository: VisionCartRepository,
    onOverlay: () -> Unit,
    navigationRequests: StateFlow<String?>,
    onNavigationRequestHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var userEmail by remember { mutableStateOf("") }
    var currentUserId by remember { mutableStateOf<Long?>(null) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route
    val snackbarHostState = remember { SnackbarHostState() }
    val isOverlayActive by FloatingWindowService.isRunning.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Log.i("VisionCart", "Post notifications permission denied")
        }
    }

    // Check saved token on startup — auto login (skip network validation for fast startup)
    LaunchedEffect(Unit) {
        ApiClient.appContext = context.applicationContext
        val token = TokenManager.getToken(context)
        if (!token.isNullOrBlank()) {
            ApiClient.authToken = token
            ApiClient.refreshTokenValue = TokenManager.getRefreshToken(context)
            currentUserId = TokenManager.getUserId(context)
            ApiClient.currentUserId = currentUserId
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
                currentUserId = ApiClient.currentUserId
                isLoggedIn = true
                scope.launch {
                    userEmail = TokenManager.getEmail(context) ?: ""
                }
            }
        )
        return
    }

    // ========== Logged in UI ==========

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: MainViewModel = viewModel(
        key = "main-${currentUserId ?: 0L}",
        factory = MainViewModel.Factory(app, repository)
    )
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()

    // Sync history from backend on first load
    LaunchedEffect(currentUserId) {
        viewModel.syncHistory()
    }

    // Double-back to exit (without logging out)
    var backPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - backPressTime < 2000) {
            (context as? ComponentActivity)?.moveTaskToBack(true)
        } else {
            backPressTime = now
            Toast.makeText(context, context.getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

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
        val category = uiState.categoryText
        attributeOptions = emptyList()
        showAttributeDialog = true
        // Load remote options asynchronously
        scope.launch {
            val result = repository.getAttributeOptions(category, name, uiState.sessionId)
            result.onSuccess { options ->
                attributeOptions = options
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                contentColor = Color(0xFF0A7C66)
            ) {
                NavigationBarItem(
                    selected = currentRoute == Screen.Home.route,
                    onClick = { if (currentRoute != Screen.Home.route) navigateHome(clearBackStack = true) },
                    icon = { Icon(Icons.Outlined.ImageSearch, contentDescription = null) },
                    label = { Text("首页") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A7C66),
                        selectedTextColor = Color(0xFF0A7C66),
                        unselectedIconColor = Color(0xFF9EAAA6),
                        unselectedTextColor = Color(0xFF9EAAA6),
                        indicatorColor = Color(0xFFEAF3F0)
                    )
                )
                NavigationBarItem(
                    selected = currentRoute == Screen.Favorites.route,
                    onClick = { if (currentRoute != Screen.Favorites.route) navigate(Screen.Favorites.route) },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                    label = { Text("收藏") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A7C66),
                        selectedTextColor = Color(0xFF0A7C66),
                        unselectedIconColor = Color(0xFF9EAAA6),
                        unselectedTextColor = Color(0xFF9EAAA6),
                        indicatorColor = Color(0xFFEAF3F0)
                    )
                )
                NavigationBarItem(
                    selected = currentRoute == Screen.History.route,
                    onClick = { if (currentRoute != Screen.History.route) navigate(Screen.History.route) },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("历史") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF0A7C66),
                        selectedTextColor = Color(0xFF0A7C66),
                        unselectedIconColor = Color(0xFF9EAAA6),
                        unselectedTextColor = Color(0xFF9EAAA6),
                        indicatorColor = Color(0xFFEAF3F0)
                    )
                )
            }
        },
        topBar = {
            when (currentRoute) {
                Screen.Home.route -> {
                    var showUserMenu by remember { mutableStateOf(false) }
                    val nickname = userEmail.substringBefore("@").ifBlank { "用户" }
                    val initial = nickname.firstOrNull()?.uppercase() ?: "U"

                    TopAppBar(
                        title = {
                            Text("🛒", fontSize = 22.sp)
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFFF8FBF9),
                            titleContentColor = Color(0xFF10201C)
                        ),
                        actions = {
                            IconButton(onClick = { onOverlay() }) {
                                Icon(
                                    Icons.Outlined.Window,
                                    contentDescription = if (isOverlayActive) "关闭悬浮窗" else "开启悬浮窗",
                                    tint = if (isOverlayActive) Color(0xFF0A7C66) else Color(0xFF53615E)
                                )
                            }
                            // User avatar + nickname
                            Box {
                                Surface(
                                    onClick = { showUserMenu = true },
                                    color = Color(0xFFEAF3F0),
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        Surface(
                                            color = Color(0xFF0A7C66),
                                            shape = CircleShape,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text(
                                                    initial,
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            nickname,
                                            color = Color(0xFF10201C),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 80.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showUserMenu,
                                    onDismissRequest = { showUserMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(userEmail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF53615E)) },
                                        onClick = { showUserMenu = false },
                                        enabled = false
                                    )
                                    DropdownMenuItem(
                                        text = { Text("退出登录", color = Color(0xFFD32F2F)) },
                                        onClick = {
                                            showUserMenu = false
                                            scope.launch {
                                                // 归档当前会话到 MySQL
                                                val currentSessionId = viewModel.uiState.value.sessionId
                                                if (!currentSessionId.isNullOrBlank()) {
                                                    try { repository.archiveSession(currentSessionId) } catch (_: Exception) {}
                                                }
                                                try { ApiClient.api.logout() } catch (_: Exception) {}
                                                ApiClient.authToken = null
                                                ApiClient.refreshTokenValue = null
                                                ApiClient.currentUserId = null
                                                TokenManager.clearToken(context)
                                                repository.clearLocalDataOnLogout()
                                                viewModel.resetState()
                                                currentUserId = null
                                                isLoggedIn = false
                                                navigateHome(clearBackStack = true)
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Color(0xFFD32F2F))
                                        }
                                    )
                                }
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
                    onProductClick = { url, _ ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
    // Sync nlpInput when restoring from history (sessionId changes)
    LaunchedEffect(uiState.sessionId) {
        nlpInput = uiState.nlpQuery
    }
    val favoriteIds = favorites.map { it.productId }.toSet()

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF8FBF9))) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HomeDashboardHeader(
                recognizedCount = uiState.products.size,
                favoriteCount = favorites.size,
                isRecognizing = uiState.recognitionState is UiState.Loading || uiState.productsLoading,
                regionMode = uiState.regionMode,
                onOverseasModeChange = { viewModel.setOverseasMode(it) }
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
        (uiState.imagePreviewUrl ?: uiState.imageUri?.toString())?.let { previewUrl ->
            item {
                val imageModel = rememberAuthenticatedImageModel(previewUrl)
                if (imageModel != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(8.dp)
                        ) {
                            coil.compose.AsyncImage(
                                model = imageModel,
                                contentDescription = "拍摄的图片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
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
                },
                progressStep = uiState.progressStep,
                confidenceHint = uiState.confidenceHint
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

        // Suggestion Cards (quick suggestions only, insight cards shown inline)
        val quickSuggestions = uiState.suggestionCards.filterNot { it.id.startsWith("insight_") }
        val insightSuggestions = uiState.suggestionCards.filter { it.id.startsWith("insight_") }.take(1)
        if (quickSuggestions.isNotEmpty()) {
            item {
                SuggestionChipsRow(
                    cards = quickSuggestions,
                    onCardClick = { viewModel.executeSuggestion(it) }
                )
            }
        }

        // Undo bar
        if (uiState.undoAction != null) {
            item {
                val toneColor = when (uiState.undoTone) {
                    "saving" -> Color(0xFFB66100)
                    "trust" -> Color(0xFF1D63A3)
                    "popularity" -> Color(0xFFC33A58)
                    "filter" -> Color(0xFF0A7C66)
                    else -> Color(0xFF53615E)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = toneColor.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "已应用: ${uiState.undoAction}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10201C)
                            )
                            uiState.undoMetric?.takeIf { it.isNotBlank() }?.let { metric ->
                                Text(
                                    metric,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = toneColor
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.undoLastAction() }) {
                            Text("撤销", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (uiState.products.isNotEmpty() || uiState.currentFilter.sortBy != null) {
            item {
                SortOptionsRow(
                    filter = uiState.currentFilter,
                    onSortSelected = { sortBy, sortOrder ->
                        viewModel.applySortMode(sortBy, sortOrder)
                    }
                )
            }
        }

        // Filter Summary
        item {
            FilterSummary(
                filter = uiState.currentFilter,
                onClear = { viewModel.clearFilter() },
                filterTags = uiState.filterTags,
                structuredFilterTags = uiState.structuredFilterTags,
                deriveFromFilter = uiState.deriveFilterTagsFromFilter,
                canUndo = uiState.canUndo && uiState.undoAction == null,
                onUndo = { viewModel.undoLastAction() },
                keptPreviousResults = uiState.keptPreviousResults,
                statusMessage = uiState.filterStatusMessage
            )
        }

        // NLP filtering progress bar (non-blocking, shows above products)
        if (uiState.nlpFiltering) {
            item {
                NlpFilteringBar(
                    message = uiState.nlpMessage ?: "正在筛选…",
                    onCancel = { viewModel.cancelNlp() }
                )
            }
        }

        // Products Loading — only show skeleton when no products yet
        if (uiState.productsLoading && uiState.products.isEmpty()) {
            item { LoadingIndicator() }
        }

        // Products List
        if (uiState.products.isNotEmpty()) {
            if (uiState.searchRelaxed) {
                item {
                    Text(
                        "未找到精确匹配，已为您放宽条件",
                        color = Color(0xFFFF9800),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }
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
            itemsIndexed(uiState.products, key = { _, product -> product.id }) { index, product ->
                ProductCardView(
                    product = product,
                    isFavorite = favoriteIds.contains(product.id),
                    showRating = shouldShowReputation(uiState.currentFilter.sortBy),
                    onFavoriteClick = { viewModel.toggleFavorite(product) },
                    onClick = {
                        if (product.detailUrl.isNotBlank()) {
                            onProductClick(product.detailUrl, product.title)
                        }
                    }
                )
                // Insert AI insight cards after the 5th product
                if (insightSuggestions.isNotEmpty() && index == 4) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color(0xFFE0E8E4),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    insightSuggestions.forEach { card ->
                        GuideInsightCard(
                            card = card,
                            onClick = { viewModel.executeSuggestion(it) }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    HorizontalDivider(
                        color = Color(0xFFE0E8E4),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else if (uiState.recognitionState is UiState.Success && !uiState.productsLoading) {
            item {
                EmptyState(message = "没有高相关商品，试试点击上方属性修正识别结果")
            }
        }
    }
    // Pinned NLP Input Bar — always visible at bottom
    if (uiState.sessionId != null) {
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
}

// ==================== Action Panel ====================

@Composable
private fun HomeDashboardHeader(
    recognizedCount: Int,
    favoriteCount: Int,
    isRecognizing: Boolean,
    regionMode: String,
    onOverseasModeChange: (Boolean) -> Unit
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
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF0A7C66), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.ImageSearch,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "智能识物比价",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isRecognizing) "正在为你整理识别结果" else "拍照、截屏或从相册开始",
                            color = Color(0xFFBFD5CF),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    RegionModeToggle(
                        overseas = regionMode == "international",
                        onChange = onOverseasModeChange
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun RegionModeToggle(
    overseas: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegionModeSegment(
                text = "自动",
                selected = !overseas,
                onClick = { onChange(false) }
            )
            RegionModeSegment(
                text = "海外",
                selected = overseas,
                onClick = { onChange(true) }
            )
        }
    }
}

@Composable
private fun RegionModeSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (selected) Color(0xFF0A7C66) else Color(0xFFE6F4F0),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeMetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                label,
                color = Color(0xFFBFD5CF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
