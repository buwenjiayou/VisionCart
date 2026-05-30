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
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import java.io.ByteArrayOutputStream
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

    // Screen metrics
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f
    private var currentX = 32
    private var currentY = 160
    private var ballX = 32
    private var ballY = 160

    // MediaProjection for screenshot
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureImageReader: ImageReader? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var captureCompleted = false
    private var frameRetryScheduled = false
    private var captureStartedAtMs = 0L

    // Coroutine scope for async operations
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    companion object {
        private const val ACTION_SCREENSHOT = "com.visioncart.ACTION_SCREENSHOT"
        private const val ACTION_SCREENSHOT_FAILED = "com.visioncart.ACTION_SCREENSHOT_FAILED"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_ERROR_MESSAGE = "errorMessage"
        private const val TAG = "FloatingWindowService"
        private const val CAPTURE_TIMEOUT_MS = 2_000L
        private const val FRAME_RETRY_DELAY_MS = 120L
        private const val HIDE_OVERLAY_DELAY_MS = 450L
        private const val MAX_SCREENSHOT_DIMENSION = 1280
        private const val MAX_SCREENSHOT_BYTES = 1_500_000
        private const val INITIAL_JPEG_QUALITY = 88
        private const val MIN_JPEG_QUALITY = 68

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

        fun reportScreenshotFailure(context: Context, message: String) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SCREENSHOT_FAILED
                putExtra(EXTRA_ERROR_MESSAGE, message)
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
        when (intent?.action) {
            ACTION_SCREENSHOT -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData != null) {
                    startScreenCapture(resultCode, resultData)
                } else {
                    showPanel(initialError = "屏幕捕获授权无效，请重试")
                }
            }

            ACTION_SCREENSHOT_FAILED -> {
                val message = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    ?: "屏幕捕获授权失败，请重试"
                showPanel(initialError = message)
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
        releaseCaptureResources()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ==================== State Management ====================

    private enum class OverlayState { COLLAPSED, MENU, PANEL }

    private var onStateChange: ((OverlayState) -> Unit)? = null

    private fun showCollapsed() {
        onStateChange?.invoke(OverlayState.COLLAPSED)
        replaceView(
            52,
            52,
            draggable = true,
            initialX = ballX,
            initialY = ballY,
            onClick = { showMenu() },
            onPositionChanged = { x, y ->
                ballX = x
                ballY = y
            }
        ) {
            CollapsedFloatingBall()
        }
    }

    private fun showMenu() {
        onStateChange?.invoke(OverlayState.MENU)
        val menuWidthDp = 176
        val menuHeightDp = 136
        val menuWidthPx = (menuWidthDp * density).toInt()
        val menuHeightPx = (menuHeightDp * density).toInt()
        val ballSizePx = (52 * density).toInt()
        val ballCenterX = ballX + ballSizePx / 2
        val ballCenterY = ballY + ballSizePx / 2
        val isOnLeftEdge = ballCenterX < screenWidth / 2
        val menuX = if (isOnLeftEdge) {
            ballX
        } else {
            ballX + ballSizePx - menuWidthPx
        }
        val menuY = ballCenterY - menuHeightPx / 2

        replaceView(
            menuWidthDp,
            menuHeightDp,
            draggable = false,
            initialX = menuX,
            initialY = menuY
        ) {
            SemiCircleMenuOverlay(
                isOnLeftEdge = isOnLeftEdge,
                onCamera = {
                    showCollapsed()
                    launchMainToCamera()
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
        val panelPosition = anchoredPanelPosition(320, 500)
        replaceView(
            320,
            500,
            draggable = false,
            focusable = true,
            initialX = panelPosition.first,
            initialY = panelPosition.second
        ) {
            OverlayPanel(
                onMinimize = { showCollapsed() },
                onClose = { stopSelf() },
                onRetakeScreenshot = { requestScreenshot() },
                initialResult = initialResult,
                pendingImageFile = pendingImageFile,
                initialError = initialError
            )
        }
    }

    private fun requestScreenshot() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to launch screenshot permission activity", error)
            showPanel(initialError = "无法打开屏幕捕获授权，请重试")
        }
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        startForegroundWithNotification(includeMediaProjection = true)
        releaseCaptureResources()
        captureCompleted = false
        frameRetryScheduled = false

        // 1. 先隐藏悬浮球，避免截进去
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null

        // 2. 等待界面刷新后再截图
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed({
            startCaptureInternal(resultCode, resultData)
        }, HIDE_OVERLAY_DELAY_MS)
    }

    private fun startCaptureInternal(resultCode: Int, resultData: Intent) {
        val handler = android.os.Handler(mainLooper)
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: run {
                    failScreenCapture("屏幕捕获授权无效，请重试")
                    return
                }
            mediaProjection = projection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post {
                        if (!captureCompleted) {
                            failScreenCapture("屏幕捕获已停止，请重试")
                        }
                    }
                }
            }
            mediaProjectionCallback = callback
            projection.registerCallback(callback, handler)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            captureImageReader = imageReader
            captureStartedAtMs = SystemClock.elapsedRealtime()

            imageReader.setOnImageAvailableListener({ reader ->
                tryAcquireFrame(reader, width, height)
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "VisionCartScreenCapture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )

            scheduleFrameRetry(imageReader, width, height)
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to start screen capture", error)
            failScreenCapture("系统拒绝屏幕捕获，请重新授权")
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Invalid screen capture state", error)
            failScreenCapture("屏幕捕获状态异常，请重试")
        } catch (error: Exception) {
            Log.w(TAG, "Failed to start screen capture", error)
            failScreenCapture("无法启动屏幕捕获，请重试")
        }
    }

    private fun tryAcquireFrame(reader: ImageReader, width: Int, height: Int) {
        if (captureCompleted || reader != captureImageReader) return
        frameRetryScheduled = false

        val image = try {
            reader.acquireLatestImage()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Failed to acquire screen frame", error)
            null
        }

        if (image == null) {
            scheduleFrameRetry(reader, width, height, "未能获取截图，请重试")
            return
        }

        val bitmap = try {
            bitmapFromImage(image, width, height)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to decode screen frame", error)
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            scheduleFrameRetry(reader, width, height, "截图解析失败，请重试")
            return
        }

        if (!isUsableScreenshot(bitmap)) {
            bitmap.recycle()
            scheduleFrameRetry(reader, width, height, "截图画面为空或受保护，请换个页面重试")
            return
        }

        completeScreenCapture(bitmap)
    }

    private fun scheduleFrameRetry(
        reader: ImageReader,
        width: Int,
        height: Int,
        timeoutMessage: String = "截图超时，请重试"
    ) {
        if (captureCompleted || frameRetryScheduled || reader != captureImageReader) return
        val elapsed = SystemClock.elapsedRealtime() - captureStartedAtMs
        if (elapsed >= CAPTURE_TIMEOUT_MS) {
            failScreenCapture(timeoutMessage)
            return
        }
        frameRetryScheduled = true
        android.os.Handler(mainLooper).postDelayed({
            tryAcquireFrame(reader, width, height)
        }, FRAME_RETRY_DELAY_MS)
    }

    private fun bitmapFromImage(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) {
            return paddedBitmap
        }

        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return cropped
    }

    private fun isUsableScreenshot(bitmap: Bitmap): Boolean {
        if (bitmap.width < 32 || bitmap.height < 32) return false

        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var samples = 0
        var alphaSum = 0
        var brightnessSum = 0
        var minBrightness = 255
        var maxBrightness = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = AndroidColor.alpha(pixel)
                val brightness = (
                        AndroidColor.red(pixel) +
                                AndroidColor.green(pixel) +
                                AndroidColor.blue(pixel)
                        ) / 3
                alphaSum += alpha
                brightnessSum += brightness
                minBrightness = minOf(minBrightness, brightness)
                maxBrightness = maxOf(maxBrightness, brightness)
                samples++
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return false
        val averageAlpha = alphaSum / samples
        val averageBrightness = brightnessSum / samples
        val brightnessRange = maxBrightness - minBrightness
        return averageAlpha >= 8 && averageBrightness >= 4 && brightnessRange > 2
    }

    private fun completeScreenCapture(bitmap: Bitmap) {
        if (captureCompleted) {
            bitmap.recycle()
            return
        }
        captureCompleted = true
        releaseCaptureResources()
        serviceScope.launch(Dispatchers.Default) {
            val result = runCatching { saveScreenshotBitmap(bitmap) }
            bitmap.recycle()
            android.os.Handler(mainLooper).post {
                result
                    .onSuccess { file -> showPanel(pendingImageFile = file) }
                    .onFailure { error ->
                        Log.w(TAG, "Failed to save screenshot", error)
                        showPanel(initialError = "截图保存失败，请重试")
                    }
            }
        }
    }

    private fun failScreenCapture(message: String) {
        if (captureCompleted) return
        captureCompleted = true
        releaseCaptureResources()
        showPanel(initialError = message)
    }

    private fun releaseCaptureResources(stopProjection: Boolean = true) {
        captureImageReader?.setOnImageAvailableListener(null, null)
        captureImageReader?.close()
        captureImageReader = null
        virtualDisplay?.release()
        virtualDisplay = null

        val projection = mediaProjection
        val callback = mediaProjectionCallback
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        if (stopProjection) {
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
        }
        mediaProjectionCallback = null
        mediaProjection = null
        frameRetryScheduled = false
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap): File {
        val constrained = resizeScreenshotIfNeeded(bitmap)
        val bytes = compressScreenshotToLimit(constrained)
        val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { output -> output.write(bytes) }
        if (constrained !== bitmap) constrained.recycle()
        return file
    }

    private fun resizeScreenshotIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= MAX_SCREENSHOT_DIMENSION) return bitmap
        val scale = MAX_SCREENSHOT_DIMENSION.toFloat() / maxDimension
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun compressScreenshotToLimit(bitmap: Bitmap): ByteArray {
        var working = bitmap
        var quality = INITIAL_JPEG_QUALITY
        while (true) {
            val output = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_SCREENSHOT_BYTES ||
                (quality <= MIN_JPEG_QUALITY && maxOf(working.width, working.height) <= 768)
            ) {
                if (working !== bitmap) working.recycle()
                return bytes
            }

            if (quality > MIN_JPEG_QUALITY) {
                quality -= 8
            } else {
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * 0.85f).toInt().coerceAtLeast(1),
                    (working.height * 0.85f).toInt().coerceAtLeast(1),
                    true
                )
                if (working !== bitmap) working.recycle()
                working = scaled
                quality = INITIAL_JPEG_QUALITY
            }
        }
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

    private fun anchoredPanelPosition(widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val margin = (12 * density).toInt()
        val ballSizePx = (52 * density).toInt()
        val ballCenterX = ballX + ballSizePx / 2
        val x = if (ballCenterX < screenWidth / 2) {
            margin
        } else {
            screenWidth - widthPx - margin
        }
        val y = ballY.coerceIn(margin, (screenHeight - heightPx - margin).coerceAtLeast(margin))
        return x to y
    }

    // ==================== View Management ====================

    private fun replaceView(
        widthDp: Int,
        heightDp: Int,
        draggable: Boolean,
        focusable: Boolean = false,
        initialX: Int? = null,
        initialY: Int? = null,
        onClick: (() -> Unit)? = null,
        onPositionChanged: ((Int, Int) -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        overlayView?.let { windowManager.removeView(it) }
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val savedX = initialX ?: currentParams?.x ?: currentX
        val savedY = initialY ?: currentParams?.y ?: currentY

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
        onPositionChanged?.invoke(params.x, params.y)

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent { MaterialTheme { content() } }

            if (draggable) {
                setOnTouchListener(createDragTouchListener(params, this, widthDp, onClick, onPositionChanged))
            }
        }
        windowManager.addView(overlayView, params)
    }

    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        view: ComposeView,
        widthDp: Int,
        onClick: (() -> Unit)?,
        onPositionChanged: ((Int, Int) -> Unit)?
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
                            currentX = params.x
                            currentY = params.y
                            onPositionChanged?.invoke(params.x, params.y)
                            windowManager.updateViewLayout(view, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                            onClick?.invoke()
                        } else {
                            snapToEdge(params, view, widthDp, onPositionChanged)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun snapToEdge(
        params: WindowManager.LayoutParams,
        view: ComposeView,
        widthDp: Int,
        onPositionChanged: ((Int, Int) -> Unit)?
    ) {
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
            currentX = params.x
            currentY = params.y
            onPositionChanged?.invoke(params.x, params.y)
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
private fun CollapsedFloatingBall() {
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
    isOnLeftEdge: Boolean,
    onCamera: () -> Unit,
    onScreenshot: () -> Unit,
    onHistory: () -> Unit,
    onFavorites: () -> Unit,
    onDismiss: () -> Unit
) {
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
        // 四个功能按钮：截图 + 拍照 + 历史 + 收藏
        val items = if (isOnLeftEdge) {
            listOf(
                MenuItemData(Icons.Default.Screenshot, "截图", Color(0xFF1976D2), onScreenshot, 0f),
                MenuItemData(Icons.Default.CameraAlt, "拍照", Color(0xFF0A7C66), onCamera, 44f),
                MenuItemData(Icons.Default.History, "历史", Color(0xFF7B61FF), onHistory, 88f),
                MenuItemData(Icons.Default.Favorite, "收藏", Color(0xFFE91E63), onFavorites, 132f)
            )
        } else {
            listOf(
                MenuItemData(Icons.Default.Favorite, "收藏", Color(0xFFE91E63), onFavorites, 48f),
                MenuItemData(Icons.Default.History, "历史", Color(0xFF7B61FF), onHistory, 92f),
                MenuItemData(Icons.Default.CameraAlt, "拍照", Color(0xFF0A7C66), onCamera, 136f),
                MenuItemData(Icons.Default.Screenshot, "截图", Color(0xFF1976D2), onScreenshot, 180f)
            )
        }

        val radius = 56.dp

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
            .width(68.dp)
            .height(78.dp)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(52.dp)
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
    onRetakeScreenshot: () -> Unit,
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
                        if (pendingImageFile == null) {
                            TextButton(onClick = onRetakeScreenshot) {
                                Text("重新截图", color = Color(0xFF0A7C66), fontWeight = FontWeight.SemiBold)
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
