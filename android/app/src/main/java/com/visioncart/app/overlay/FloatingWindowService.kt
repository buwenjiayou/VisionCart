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
import android.net.Uri
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Screenshot
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
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
import com.visioncart.app.data.ActionResult
import com.visioncart.app.data.FilterTag
import com.visioncart.app.data.ProductCard
import com.visioncart.app.data.RecognitionCandidate
import com.visioncart.app.data.RecognitionResult
import com.visioncart.app.data.SearchFilter
import com.visioncart.app.data.SearchRequest
import com.visioncart.app.data.SuggestionCard
import com.visioncart.app.data.UserActionPayload
import com.visioncart.app.data.UserActionRequest
import com.visioncart.app.data.toSearchAttributes
import com.visioncart.app.data.repository.VisionCartRepository
import com.visioncart.app.ui.components.MultiProductSelectionPanel
import com.visioncart.app.ui.components.SortOptionsRow
import com.visioncart.app.ui.components.SuggestionChipsRow
import com.visioncart.app.ui.components.productReputationDisplayText
import com.visioncart.app.ui.components.rememberAuthenticatedImageModel
import com.visioncart.app.ui.viewmodel.ActionStateReducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        private const val ACTION_SHOW_PANEL = "com.visioncart.ACTION_SHOW_PANEL"
        private const val ACTION_SCREENSHOT = "com.visioncart.ACTION_SCREENSHOT"
        private const val ACTION_SCREENSHOT_FAILED = "com.visioncart.ACTION_SCREENSHOT_FAILED"
        private const val ACTION_CROP_COMPLETE = "com.visioncart.ACTION_CROP_COMPLETE"
        private const val ACTION_CROP_CANCELLED = "com.visioncart.ACTION_CROP_CANCELLED"
        private const val ACTION_CROP_FAILED = "com.visioncart.ACTION_CROP_FAILED"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_ERROR_MESSAGE = "errorMessage"
        private const val EXTRA_IMAGE_PATH = "imagePath"
        private const val TAG = "FloatingWindowService"
        private const val CAPTURE_TIMEOUT_MS = 2_000L
        private const val FRAME_RETRY_DELAY_MS = 120L
        private const val HIDE_OVERLAY_DELAY_MS = 450L

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

        fun reportCropComplete(context: Context, imagePath: String) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_CROP_COMPLETE
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reportCropCancelled(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_CROP_CANCELLED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reportCropFailure(context: Context, message: String) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_CROP_FAILED
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

        val pendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW_PANEL
            },
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
            ACTION_SHOW_PANEL -> {
                showPanel()
            }

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

            ACTION_CROP_COMPLETE -> {
                val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val file = path?.let { File(it) }
                if (file != null && file.isFile) {
                    showPanel(pendingImageFile = file)
                } else {
                    showPanel(initialError = "裁剪图片不存在，请重新截图")
                }
            }

            ACTION_CROP_CANCELLED -> {
                showCollapsed()
            }

            ACTION_CROP_FAILED -> {
                val message = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    ?: "截图裁剪失败，请重试"
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
            64,
            64,
            draggable = true,
            initialX = ballX,
            initialY = ballY,
            onClick = { requestScreenshot() },
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
        val ballSizePx = (64 * density).toInt()
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
                onScreenshot = {
                    showCollapsed()
                    requestScreenshot()
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
        val panelWidthDp = (screenWidth / density).toInt().coerceAtLeast(1)
        val panelHeightPx = (screenHeight / 2).coerceAtLeast(1)
        val panelHeightDp = (panelHeightPx / density).toInt().coerceAtLeast(1)
        replaceView(
            panelWidthDp,
            panelHeightDp,
            draggable = false,
            focusable = true,
            initialX = 0,
            initialY = (screenHeight - panelHeightPx).coerceAtLeast(0)
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
        // 不要提前移除悬浮窗 — startScreenCapture() 会在截图前移除
        // 提前移除会导致透明 Activity 覆盖在桌面上，用户需要额外点击才能触发权限弹窗
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

            // Downscale to half resolution to reduce memory (~55MB -> ~14MB)
            val captureWidth = (width / 2).coerceAtLeast(1)
            val captureHeight = (height / 2).coerceAtLeast(1)
            val imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            captureImageReader = imageReader
            captureStartedAtMs = SystemClock.elapsedRealtime()

            imageReader.setOnImageAvailableListener({ reader ->
                tryAcquireFrame(reader, captureWidth, captureHeight)
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "VisionCartScreenCapture",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )

            scheduleFrameRetry(imageReader, captureWidth, captureHeight)
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
                if (!isRunning.value) return@post
                result
                    .onSuccess { file -> launchCropActivity(file) }
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
        val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        }
        return file
    }

    private fun launchCropActivity(screenshotFile: File) {
        val intent = Intent(this, ScreenshotCropActivity::class.java).apply {
            putExtra(ScreenshotCropActivity.EXTRA_SCREENSHOT_PATH, screenshotFile.absolutePath)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to launch screenshot crop activity", error)
            showPanel(initialError = "无法打开截图框选界面，请重试")
        }
    }

    private fun anchoredPanelPosition(widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val margin = (12 * density).toInt()
        val ballSizePx = (64 * density).toInt()
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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Subtle scale pulse
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Ring rotation
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
        ),
        label = "ringRotation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Outer soft glow
        Canvas(modifier = Modifier.size(64.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        com.visioncart.app.ui.theme.BrandGradientStart.copy(alpha = glowAlpha * 0.35f),
                        com.visioncart.app.ui.theme.BrandGradientStart.copy(alpha = glowAlpha * 0.1f),
                        Color.Transparent
                    ),
                    radius = size.minDimension / 2
                ),
                radius = size.minDimension / 2
            )
        }

        // Spinning ring accent
        Canvas(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer { rotationZ = ringRotation }
        ) {
            val ringRadius = size.minDimension / 2
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.White.copy(alpha = 0.5f),
                    0.3f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to Color.White.copy(alpha = 0.35f)
                ),
                radius = ringRadius,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Main ball
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    spotColor = com.visioncart.app.ui.theme.BrandGradientEnd,
                    ambientColor = com.visioncart.app.ui.theme.BrandGradientStart.copy(alpha = 0.3f)
                )
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            com.visioncart.app.ui.theme.BrandGradientStart,
                            com.visioncart.app.ui.theme.BrandGradientEnd
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.1f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Icon(
                Icons.Default.Screenshot,
                contentDescription = "截图识物",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ==================== Radial Menu ====================

/**
 * 极简悬浮窗菜单，根据悬浮球位置向内侧展开截图入口。
 */
@Composable
private fun SemiCircleMenuOverlay(
    isOnLeftEdge: Boolean,
    onScreenshot: () -> Unit,
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
        // 悬浮窗菜单只保留可在悬浮窗内闭环完成的截图识别入口。
        val items = if (isOnLeftEdge) {
            listOf(
                MenuItemData(Icons.Default.Screenshot, "截图", Color(0xFF1976D2), onScreenshot, 0f)
            )
        } else {
            listOf(
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
    var currentFilter by remember { mutableStateOf(SearchFilter()) }
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
    var pendingCandidates by remember { mutableStateOf<List<RecognitionCandidate>>(emptyList()) }
    var pendingSessionId by remember { mutableStateOf<String?>(null) }
    var pendingImageUri by remember { mutableStateOf(pendingImageFile?.let(Uri::fromFile)) }
    var selectedCandidateLoading by remember { mutableStateOf(false) }

    // Undo state for the compact applied-action bar.
    var undoAction by remember { mutableStateOf<String?>(null) }

    // Filter tags state
    var filterTags by remember { mutableStateOf<List<FilterTag>>(emptyList()) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    fun actionMessage(result: ActionResult): String? {
        val parts = mutableListOf<String>()
        ActionStateReducer.resolveMessage(result.messageCode, result.message)?.let { parts.add(it) }
        result.warnings.firstOrNull()?.let { parts.add(it) }
        result.explanations.firstOrNull()?.let { parts.add(it) }
        return parts.distinct().joinToString("\n").ifBlank { null }
    }

    fun applyActionResult(result: ActionResult, updateFilterTags: Boolean = true) {
        val displayMessage = actionMessage(result)
        if (!result.filterApplied) {
            displayMessage?.let {
                resultMessage = it
                statusText = it
            }
            return
        }
        products = result.allDisplayProducts.take(50)
        currentFilter = result.appliedFilter ?: currentFilter
        if (updateFilterTags) {
            filterTags = result.filterTags
        }
        suggestionCards = result.suggestionCards ?: suggestionCards
        displayMessage?.let {
            resultMessage = it
            statusText = it
        }
    }

    suspend fun loadProducts(result: RecognitionResult) {
        val searchResult = repository.searchProducts(
            SearchRequest(
                sessionId = result.sessionId,
                attributes = result.toSearchAttributes(),
                filter = currentFilter,
                clientType = "overlay"
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
        val imageUri = Uri.fromFile(file)
        isLoading = true
        errorText = null
        pendingCandidates = emptyList()
        pendingSessionId = null
        pendingImageUri = imageUri
        recognitionResult = null
        products = emptyList()
        suggestionCards = emptyList()
        statusText = "截图已获取，正在云端识别..."
        repository.analyzeImage(imageUri)
            .onSuccess { result ->
                recognitionResult = result
                sessionId = result.sessionId
                pendingCandidates = emptyList()
                pendingSessionId = null
                statusText = "识别完成，正在加载商品..."
                loadProducts(result)
            }
            .onFailure { error ->
                if (error is VisionCartRepository.MultiProductPendingException) {
                    sessionId = error.sessionId
                    pendingSessionId = error.sessionId
                    pendingCandidates = error.candidates
                    pendingImageUri = error.imageUrl?.let(Uri::parse) ?: imageUri
                    errorText = null
                    statusText = "检测到 ${error.candidates.size} 个商品，请选择要识别的商品"
                } else {
                    errorText = error.message ?: "截图识别失败，请重试"
                    statusText = "截图识别遇到问题"
                }
            }
        isLoading = false
    }

    suspend fun selectPendingCandidate(candidate: RecognitionCandidate) {
        val sid = pendingSessionId ?: sessionId ?: return
        val selectedPreviewUrl = candidate.previewImageUrl ?: pendingImageUri?.toString()
        selectedCandidateLoading = true
        isLoading = true
        errorText = null
        pendingImageUri = selectedPreviewUrl?.let(Uri::parse) ?: pendingImageUri
        statusText = "正在识别选中的商品..."
        repository.selectProductForRecognition(
            sid,
            candidate.candidateId,
            selectedPreviewUrl
        )
            .onSuccess { result ->
                recognitionResult = result
                sessionId = result.sessionId
                pendingSessionId = null
                pendingCandidates = emptyList()
                statusText = "识别完成，正在加载商品..."
                loadProducts(result)
            }
            .onFailure { error ->
                errorText = error.message ?: "选中商品识别失败，请重试"
                statusText = "选中商品识别遇到问题"
            }
        selectedCandidateLoading = false
        isLoading = false
    }

    LaunchedEffect(initialResult, pendingImageFile) {
        when {
            pendingImageFile != null -> {
                try {
                    analyzePendingImage(pendingImageFile)
                } finally {
                    isLoading = false
                }
            }
            initialResult != null -> {
                isLoading = true
                try {
                    loadProducts(initialResult)
                } finally {
                    isLoading = false
                }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    TextButton(onClick = onRetakeScreenshot) {
                        Text("重新截图", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0A7C66))
                    }
                    IconButton(onClick = onMinimize, modifier = Modifier.size(32.dp)) {
                        Text("收起", style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFFD32F2F), modifier = Modifier.size(18.dp))
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
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
                }

                // Filter tags row
                if (filterTags.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            filterTags.forEach { tag ->
                                val fieldName = tag.filterPath ?: tagToFilterField(tag.label)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0A7C66).copy(alpha = 0.12f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                                    ) {
                                        Text(
                                            tag.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF0A7C66)
                                        )
                                        if (fieldName != null) {
                                            IconButton(
                                                onClick = {
                                                    val sid = sessionId ?: return@IconButton
                                                    scope.launch {
                                                        try {
                                                            repository.executeUserAction(
                                                                UserActionRequest(
                                                                    actionId = "overlay-tag-${System.currentTimeMillis()}",
                                                                    source = "tag_delete",
                                                                    sessionId = sid,
                                                                    rawText = "remove filter:$fieldName",
                                                                    payload = UserActionPayload(
                                                                        tagId = fieldName,
                                                                        filterPath = fieldName
                                                                    )
                                                                )
                                                            ).onSuccess { actionResult ->
                                                                applyActionResult(actionResult)
                                                            }.onFailure { error ->
                                                                errorText = "删除筛选失败: ${error.message ?: "请稍后重试"}"
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w("FloatingWindowService", "Failed to remove filter field", e)
                                                            errorText = "删除筛选失败: ${e.message ?: "请稍后重试"}"
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(16.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "删除",
                                                    tint = Color(0xFF0A7C66).copy(alpha = 0.7f),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Result message
                resultMessage?.let { msg ->
                    item {
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }

                pendingImageUri?.toString()?.let { previewUrl ->
                    item {
                        rememberAuthenticatedImageModel(previewUrl)?.let { imageModel ->
                            AsyncImage(
                                model = imageModel,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEAF3F0)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }

                errorText?.let { error ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFF1F1)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    error,
                                    color = Color(0xFFB3261E),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pendingImageFile?.let { retryFile ->
                                        TextButton(
                                            onClick = {
                                                scope.launch { analyzePendingImage(retryFile) }
                                            }
                                        ) {
                                            Text("重试", color = Color(0xFF0A7C66), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    TextButton(onClick = onRetakeScreenshot) {
                                        Text("重新截图", color = Color(0xFF0A7C66), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                if (pendingCandidates.isNotEmpty()) {
                    item {
                        MultiProductSelectionPanel(
                            candidates = pendingCandidates,
                            onSelect = { candidate ->
                                if (!selectedCandidateLoading) {
                                    scope.launch { selectPendingCandidate(candidate) }
                                }
                            }
                        )
                    }
                }

                recognitionResult?.let { result ->
                    item {
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
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (suggestionCards.isNotEmpty()) {
                    item {
                        SuggestionChipsRow(
                            cards = suggestionCards,
                            maxCards = 4,
                            compact = true,
                            onCardClick = { card ->
                                scope.launch {
                                    val sid = sessionId ?: return@launch
                                    val action = card.action.takeIf { it.isNotBlank() } ?: return@launch
                                    try {
                                        repository.executeUserAction(
                                            UserActionRequest(
                                                actionId = "overlay-suggestion-${System.currentTimeMillis()}",
                                                source = "suggestion",
                                                sessionId = sid,
                                                rawText = action,
                                                payload = UserActionPayload(action = action)
                                            )
                                        ).onSuccess { result ->
                                            applyActionResult(result, updateFilterTags = false)
                                            if (result.canUndo) {
                                                undoAction = card.title
                                            }
                                        }.onFailure { error ->
                                            errorText = "建议执行失败: ${error.message ?: "请稍后重试"}"
                                        }
                                    } catch (error: Exception) {
                                        Log.w("FloatingWindowService", "Failed to execute suggestion $action", error)
                                        errorText = "建议执行失败: ${error.message ?: "请稍后重试"}"
                                    }
                                }
                            }
                        )
                    }
                }

                // Undo bar after suggestion card is applied
                if (undoAction != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已应用: $undoAction",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF53615E),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val sid = sessionId ?: return@launch
                                        try {
                                            repository.undoLastAction(sid).onSuccess { result ->
                                                applyActionResult(result)
                                            }.onFailure { error ->
                                                errorText = "撤回失败: ${error.message ?: "请稍后重试"}"
                                            }
                                        } catch (error: Exception) {
                                            Log.w("FloatingWindowService", "Failed to undo overlay action", error)
                                            errorText = "撤回失败: ${error.message ?: "请稍后重试"}"
                                        }
                                        undoAction = null
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "撤回",
                                    tint = Color(0xFF53615E),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                if (products.isNotEmpty() || currentFilter.sortBy != null) {
                    item {
                        SortOptionsRow(
                            filter = currentFilter,
                            onSortSelected = { sortBy, sortOrder ->
                                undoAction = null
                                currentFilter = currentFilter.copy(
                                    sortBy = sortBy,
                                    sortOrder = if (sortBy == null) "desc" else sortOrder
                                )
                                recognitionResult?.let { result ->
                                    scope.launch {
                                        isLoading = true
                                        loadProducts(result)
                                        isLoading = false
                                    }
                                }
                            }
                        )
                    }
                }

                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF0A7C66))
                            Spacer(Modifier.width(8.dp))
                            Text("处理中...", color = Color(0xFF757575), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (products.isEmpty() && !isLoading && pendingCandidates.isEmpty()) {
                    item {
                        Text(
                            if (recognitionResult == null) "框选商品后会在这里显示比价结果" else "暂未找到匹配商品",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            color = Color(0xFF7A8A85),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(products) { product ->
                        CompactProductCard(
                            product = product,
                            showReputation = shouldShowOverlayReputation(currentFilter.sortBy)
                        )
                    }
                }
            }

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
                                        val query = nlpInput
                                        val category = recognitionResult?.category?.level1 ?: ""
                                        repository.executeUserAction(
                                            UserActionRequest(
                                                actionId = "overlay-nlp-${System.currentTimeMillis()}",
                                                source = "nlp",
                                                sessionId = sid,
                                                rawText = query,
                                                payload = UserActionPayload(
                                                    context = mapOf(
                                                        "productName" to category,
                                                        "category" to category
                                                    )
                                                )
                                            )
                                        ).onSuccess { result ->
                                            applyActionResult(result)
                                            if (result.canUndo) {
                                                undoAction = query
                                            }
                                        }.onFailure { error ->
                                            errorText = "筛选失败: ${error.message ?: "请稍后重试"}"
                                        }
                                    } catch (error: Exception) {
                                        Log.w("FloatingWindowService", "Failed to parse overlay NLP filter", error)
                                        errorText = "筛选失败: ${error.message ?: "请稍后重试"}"
                                    } finally {
                                        isLoading = false
                                        nlpInput = ""
                                    }
                                }
                            }
                        ) { Text("搜", color = Color(0xFF0A7C66), fontWeight = FontWeight.Bold) }
                    }
                }
            )
        }
    }
}

/**
 * Maps a display tag to its corresponding filter field name for deletion.
 * Returns null if the tag doesn't map to a deletable field.
 */
private fun tagToFilterField(tag: String): String? {
    return when {
        tag.startsWith("≤¥") || tag.startsWith("≥¥") -> "price_range"
        tag == "京东" || tag == "淘宝" || tag == "天猫" || tag == "拼多多" -> "platforms"
        tag == "自营" -> "self_operated"
        tag.startsWith("≥") && tag.endsWith("分") -> "rating_min"
        else -> {
            val colors = listOf("黑色", "白色", "红色", "蓝色", "绿色", "黄色", "粉色", "紫色", "灰色", "金色", "银色")
            if (colors.any { tag.contains(it) }) return "colors"
            // 未知标签返回 null，不显示删除按钮
            null
        }
    }
}

private fun shouldShowOverlayReputation(sortBy: String?): Boolean {
    return sortBy == "rating" || sortBy == "reviews" || sortBy == "review_quality" ||
        sortBy == "rating_desc" || sortBy == "shop_trust" || sortBy == "seller_trust"
}

@Composable
private fun CompactProductCard(product: ProductCard, showReputation: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9F8))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val imageModel = rememberAuthenticatedImageModel(product.imageUrl)
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
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
                val reputationText = if (showReputation) productReputationDisplayText(product) else null
                if (!reputationText.isNullOrBlank()) {
                    Text(
                        reputationText,
                        color = Color(0xFFEF6C00),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
