package com.visioncart.app.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import com.visioncart.app.data.NlpParseRequest
import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.RecognitionResult
import com.visioncart.app.data.SearchRequest
import com.visioncart.app.data.SuggestionCard
import com.visioncart.app.data.toSearchAttributes
import com.visioncart.app.data.repository.VisionCartRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

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

    // Screen metrics
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f
    private var currentX = 32  // 悬浮球当前 X 位置，用于菜单展开方向
    private var currentY = 160

    // MediaProjection for screenshot
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Coroutine scope for async operations
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    companion object {
        private const val ACTION_SCREENSHOT = "com.visioncart.ACTION_SCREENSHOT"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val TAG = "FloatingWindowService"

        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun startScreenshot(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SCREENSHOT
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        startForegroundWithNotification()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.density
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showCollapsed()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    private fun startForegroundWithNotification(includeMediaProjection: Boolean = false) {
        val channelId = "visioncart_overlay"
        val channel = NotificationChannel(
            channelId, "VisionCart 悬浮窗",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VisionCart 悬浮识物服务"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.visioncart.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("VisionCart")
            .setContentText("悬浮识物已开启")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (includeMediaProjection) {
                foregroundServiceType = foregroundServiceType or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(1, notification, foregroundServiceType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeMediaProjection) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCREENSHOT) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultData != null) {
                startScreenCapture(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning.value = false
        serviceScope.cancel()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ==================== State Management ====================

    private enum class OverlayState { COLLAPSED, MENU, PANEL }

    private var onStateChange: ((OverlayState) -> Unit)? = null

    private fun showCollapsed() {
        onStateChange?.invoke(OverlayState.COLLAPSED)
        replaceView(52, 52, draggable = true) {
            CollapsedFloatingBall(
                onClick = { showMenu() }
            )
        }
    }

    private fun showMenu() {
        onStateChange?.invoke(OverlayState.MENU)
        // 半圆形菜单，大小限制在悬浮球周围区域
        replaceView(220, 220, draggable = false) {
            SemiCircleMenuOverlay(
                currentX = currentX,
                screenWidthPx = screenWidth,
                onCamera = {
                    launchMainToCamera()
                    showCollapsed()
                },
                onScreenshot = {
                    showCollapsed()
                    requestScreenshot()
                },
                onHistory = {
                    launchMainToScreen("history")
                    showCollapsed()
                },
                onFavorites = {
                    launchMainToScreen("favorites")
                    showCollapsed()
                },
                onDismiss = { showCollapsed() }
            )
        }
    }

    private fun showPanel(
        initialResult: RecognitionResult? = null,
        pendingImageFile: File? = null,
        initialError: String? = null
    ) {
        onStateChange?.invoke(OverlayState.PANEL)
        replaceView(320, 500, draggable = true, focusable = true) {
            OverlayPanel(
                onMinimize = { showCollapsed() },
                onClose = { stopSelf() },
                initialResult = initialResult,
                pendingImageFile = pendingImageFile,
                initialError = initialError
            )
        }
    }

    private fun requestScreenshot() {
        // Navigate to MainActivity which holds the MediaProjection launcher
        val intent = Intent(this, com.visioncart.app.MainActivity::class.java).apply {
            putExtra("navigate", "screenshot")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        startForegroundWithNotification(includeMediaProjection = true)

        // 1. 先隐藏悬浮球，避免截进去
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null

        // 2. 等待界面刷新后再截图
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed({
            startCaptureInternal(resultCode, resultData)
        }, 300)
    }

    private fun startCaptureInternal(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        val handler = android.os.Handler(mainLooper)
        handler.postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * metrics.widthPixels

                val bitmap = Bitmap.createBitmap(
                    metrics.widthPixels + rowPadding / pixelStride,
                    metrics.heightPixels,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop to screen size
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
                bitmap.recycle()

                // Save to temp file
                val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                cropped.recycle()

                // Release capture resources
                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection?.stop()
                mediaProjection = null
                imageReader.close()

                showPanel(pendingImageFile = file)
            } else {
                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection?.stop()
                mediaProjection = null
                imageReader.close()

                showPanel(initialError = "未能获取截图，请重试")
            }
        }, 500)
    }

    private fun launchMainToCamera() {
        val intent = Intent(this, com.visioncart.app.MainActivity::class.java).apply {
            putExtra("navigate", "camera")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun launchMainToScreen(screen: String) {
        val intent = Intent(this, com.visioncart.app.MainActivity::class.java).apply {
            putExtra("navigate", screen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ==================== View Management ====================

    private fun replaceView(
        widthDp: Int,
        heightDp: Int,
        draggable: Boolean,
        focusable: Boolean = false,
        content: @Composable () -> Unit
    ) {
        overlayView?.let { windowManager.removeView(it) }
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val savedX = currentParams?.x ?: currentX
        val savedY = currentParams?.y ?: currentY

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(-widthPx / 3, screenWidth - (widthPx * 2 / 3))
            y = savedY.coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0))
        }
        currentParams = params
        currentX = params.x
        currentY = params.y

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent { MaterialTheme { content() } }

            if (draggable) {
                setOnTouchListener(createDragTouchListener(params, this, widthDp))
            }
        }
        windowManager.addView(overlayView, params)
    }

    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        view: ComposeView,
        widthDp: Int
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
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
                        if (dx * dx + dy * dy > 100) isDragging = true
                        if (isDragging) {
                            val widthPx = (widthDp * density).toInt()
                            val heightPx = (view.height).coerceAtLeast(widthPx)
                            params.x = (initialX + dx).toInt().coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))
                            params.y = (initialY + dy).toInt().coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0))
                            currentX = params.x  // 更新当前位置
                            currentY = params.y
                            windowManager.updateViewLayout(view, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                        } else {
                            snapToEdge(params, view, widthDp)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, view: ComposeView, widthDp: Int) {
        val ballWidth = (widthDp * density).toInt()
        val centerX = params.x + ballWidth / 2
        val halfScreen = screenWidth / 2

        // Snap to nearest edge with partial hide (30% off-screen)
        val hideOffset = (ballWidth * 0.3).toInt()
        val targetX = if (centerX < halfScreen) -hideOffset else screenWidth - ballWidth + hideOffset

        // Animate to edge
        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            currentX = params.x  // 更新当前位置
            currentY = params.y
            try {
                windowManager.updateViewLayout(view, params)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to update floating window position", error)
            }
        }
        animator.start()
    }
}

// ==================== Collapsed Floating Ball ====================

@Composable
private fun CollapsedFloatingBall(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "breath")

    // Breathing glow effect
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Subtle scale pulse
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Outer glow ring
        Canvas(modifier = Modifier.size(56.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        com.visioncart.app.ui.theme.BrandGradientStart.copy(alpha = glowAlpha * 0.6f),
                        Color.Transparent
                    ),
                    radius = size.minDimension / 2 * 1.5f
                ),
                radius = size.minDimension / 2 * 1.4f
            )
        }

        // Main ball
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(12.dp, CircleShape, spotColor = com.visioncart.app.ui.theme.BrandGradientEnd)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            com.visioncart.app.ui.theme.BrandGradientStart,
                            com.visioncart.app.ui.theme.BrandGradientEnd
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Icon(
                Icons.Default.ShoppingCart,
                contentDescription = "VisionCart",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ==================== Radial Menu ====================

/**
 * 极简双按钮菜单 - 截图 + 拍照，根据悬浮球位置向内侧展开
 */
@Composable
private fun SemiCircleMenuOverlay(
    currentX: Int,
    screenWidthPx: Int,
    onCamera: () -> Unit,
    onScreenshot: () -> Unit,
    onHistory: () -> Unit,
    onFavorites: () -> Unit,
    onDismiss: () -> Unit
) {
    // 获取悬浮球当前位置（左边缘或右边缘）
    val isOnLeftEdge = currentX < screenWidthPx / 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = if (isOnLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        // 两个功能按钮：截图 + 拍照，向屏幕内侧展开
        val items = if (isOnLeftEdge) {
            // 悬浮球在左边，向右展开
            listOf(
                MenuItemData(Icons.Default.Screenshot, "截图", Color(0xFF1976D2), onScreenshot, 0f),
                MenuItemData(Icons.Default.CameraAlt, "拍照", Color(0xFF0A7C66), onCamera, 45f)
            )
        } else {
            // 悬浮球在右边，向左展开
            listOf(
                MenuItemData(Icons.Default.CameraAlt, "拍照", Color(0xFF0A7C66), onCamera, 135f),
                MenuItemData(Icons.Default.Screenshot, "截图", Color(0xFF1976D2), onScreenshot, 180f)
            )
        }

        val radius = 70.dp

        items.forEachIndexed { index, item ->
            DualMenuItem(
                item = item,
                radius = radius,
                delay = index * 80
            )
        }

        // 关闭按钮（在悬浮球位置）
        var centerVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(150)
            centerVisible = true
        }

        val centerScale by animateFloatAsState(
            targetValue = if (centerVisible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "centerScale"
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = if (isOnLeftEdge) 8.dp else 0.dp, end = if (isOnLeftEdge) 0.dp else 8.dp)
                .size(46.dp)
                .graphicsLayer {
                    scaleX = centerScale
                    scaleY = centerScale
                }
                .shadow(12.dp, CircleShape)
                .background(Color.White, CircleShape)
                .clickable(onClick = onDismiss)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun DualMenuItem(
    item: MenuItemData,
    radius: androidx.compose.ui.unit.Dp,
    delay: Int
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delay.toLong())
        visible = true
    }

    val animProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dualMenuItem"
    )

    val angleRad = Math.toRadians(item.angle.toDouble())
    val offsetX = radius * kotlin.math.cos(angleRad).toFloat()
    val offsetY = radius * kotlin.math.sin(angleRad).toFloat()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset {
                IntOffset(
                    (offsetX.toPx() * animProgress).toInt(),
                    (offsetY.toPx() * animProgress).toInt()
                )
            }
            .graphicsLayer {
                alpha = animProgress
                scaleX = animProgress
                scaleY = animProgress
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                item.onClick()
            }
    ) {
        // 图标圆形按钮
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .shadow(12.dp, CircleShape, spotColor = item.color)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(item.color.copy(alpha = 0.8f), item.color)
                    ),
                    shape = CircleShape
                )
        ) {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        // 标签
        Text(
            item.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .shadow(2.dp, RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private data class MenuItemData(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit,
    val angle: Float = 0f  // 展开角度
)

// ==================== Overlay Panel ====================

@Composable
private fun OverlayPanel(
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    initialResult: RecognitionResult? = null,
    pendingImageFile: File? = null,
    initialError: String? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember { VisionCartRepository(context) }
    var sessionId by remember { mutableStateOf(initialResult?.sessionId) }
    var recognitionResult by remember { mutableStateOf(initialResult) }
    var products by remember { mutableStateOf<List<ProductCard>>(emptyList()) }
    var suggestionCards by remember { mutableStateOf<List<SuggestionCard>>(emptyList()) }
    var isLoading by remember { mutableStateOf(pendingImageFile != null) }
    var errorText by remember { mutableStateOf(initialError) }
    var statusText by remember {
        mutableStateOf(
            when {
                pendingImageFile != null -> "截图已获取，正在云端识别..."
                initialResult != null -> "识别完成，正在加载商品..."
                initialError != null -> "截图识别遇到问题"
                else -> "截图框选 → 云端识别 → 精简比价结果"
            }
        )
    }
    var nlpInput by remember { mutableStateOf("") }

    suspend fun loadProducts(result: RecognitionResult) {
        val searchResult = repository.searchProducts(
            SearchRequest(
                sessionId = result.sessionId,
                attributes = result.toSearchAttributes()
            )
        )
        searchResult
            .onSuccess { search ->
                products = search.products
                suggestionCards = search.suggestionCards
                statusText = "已识别 ${result.category.level1.ifBlank { "商品" }}，找到 ${search.products.size} 件商品"
            }
            .onFailure { error ->
                errorText = "商品搜索失败: ${error.message ?: "请稍后重试"}"
                statusText = "识别完成，商品加载失败"
            }
    }

    suspend fun analyzePendingImage(file: File) {
        isLoading = true
        errorText = null
        statusText = "截图已获取，正在云端识别..."
        repository.analyzeImageFile(file, file.toURI().toString())
            .onSuccess { result ->
                recognitionResult = result
                sessionId = result.sessionId
                statusText = "识别完成，正在加载商品..."
                loadProducts(result)
            }
            .onFailure { error ->
                errorText = error.message ?: "截图识别失败，请重试"
                statusText = "截图识别遇到问题"
            }
        isLoading = false
    }

    LaunchedEffect(initialResult, pendingImageFile) {
        when {
            pendingImageFile != null -> analyzePendingImage(pendingImageFile)
            initialResult != null -> {
                isLoading = true
                loadProducts(initialResult)
                isLoading = false
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "VisionCart",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0A7C66)
                )
                Row {
                    IconButton(onClick = onMinimize, modifier = Modifier.size(32.dp)) {
                        Text("收起", style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFFD32F2F), modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Status
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF0F7F5)
            ) {
                Text(
                    statusText,
                    color = Color(0xFF53615E),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            errorText?.let { error ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF1F1)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            error,
                            color = Color(0xFFB3261E),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        pendingImageFile?.let { retryFile ->
                            TextButton(
                                onClick = {
                                    scope.launch { analyzePendingImage(retryFile) }
                                }
                            ) {
                                Text("重试", color = Color(0xFF0A7C66), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Recognition result
            recognitionResult?.let { result ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${result.category.level1} / ${result.category.level2}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                        val attrsText = result.attributes.entries.joinToString("  ") { "${it.key}: ${it.value.value}" }
                        Text(
                            attrsText,
                            color = Color(0xFF53615E),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Suggestion chips
            if (suggestionCards.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestionCards.take(3).forEach { card ->
                        Surface(
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val sid = sessionId ?: return@launch
                                    try {
                                        val response = repository.executeSuggestion(sid, card.action)
                                        response.onSuccess { result ->
                                            products = result.products
                                            suggestionCards = result.cards
                                        }
                                    } catch (error: Exception) {
                                        Log.w("FloatingWindowService", "Failed to execute suggestion ${card.action}", error)
                                        errorText = "建议执行失败: ${error.message ?: "请稍后重试"}"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFF0F7F5)
                        ) {
                            Text(
                                "${card.icon} ${card.title}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF0A7C66))
                    Spacer(Modifier.width(8.dp))
                    Text("处理中...", color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Product list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (products.isEmpty() && !isLoading) {
                    item {
                        Text(
                            if (recognitionResult == null) "截图后会在这里显示比价结果" else "暂未找到匹配商品",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            color = Color(0xFF7A8A85),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(products.take(5)) { product ->
                        CompactProductCard(product)
                    }
                }
            }

            // NLP input
            OutlinedTextField(
                value = nlpInput,
                onValueChange = { nlpInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("追加筛选条件...", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (nlpInput.isNotBlank()) {
                        TextButton(
                            onClick = {
                                val sid = sessionId ?: return@TextButton
                                scope.launch {
                                    isLoading = true
                                    try {
                                        val nlpResult = repository.parseNlp(
                                            NlpParseRequest(sessionId = sid, userInput = nlpInput)
                                        )
                                        nlpResult.onSuccess { result ->
                                            val searchResult = repository.searchProducts(
                                                SearchRequest(
                                                    sessionId = sid,
                                                    attributes = recognitionResult?.toSearchAttributes() ?: emptyMap(),
                                                    filter = result.filter
                                                )
                                            )
                                            searchResult.onSuccess { search ->
                                                products = search.products
                                                suggestionCards = search.suggestionCards
                                            }
                                        }
                                    } catch (error: Exception) {
                                        Log.w("FloatingWindowService", "Failed to parse overlay NLP filter", error)
                                        errorText = "筛选失败: ${error.message ?: "请稍后重试"}"
                                    }
                                    isLoading = false
                                    nlpInput = ""
                                }
                            }
                        ) { Text("搜", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold) }
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
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9F8))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (product.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    product.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    product.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                        Text(
                            brand,
                            color = Color(0xFF8A5A00),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                    }
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
                Text(
                    product.salesLabel?.takeIf { it.isNotBlank() }
                        ?: if (product.sales > 0) "销量 ${product.sales}" else "销量未知",
                    color = Color(0xFF7A8A85),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}
