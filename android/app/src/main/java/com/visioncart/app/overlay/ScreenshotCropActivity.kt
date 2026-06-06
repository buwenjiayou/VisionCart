package com.visioncart.app.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

class ScreenshotCropActivity : ComponentActivity() {
    private var completed = false
    private var sourcePath: String? = null
    private var decodedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        sourcePath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val bitmap = sourcePath?.let { decodeSampledBitmap(it, MAX_BITMAP_DIMENSION) }
        if (bitmap == null) {
            failAndFinish("截图文件无法读取，请重新截图")
            return
        }
        decodedBitmap = bitmap

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAndFinish()
            }
        })

        setContent {
            MaterialTheme {
                ScreenshotCropScreen(
                    bitmap = bitmap,
                    onCancel = { cancelAndFinish() },
                    onConfirm = { cropped -> cropAndReturn(cropped) }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            decodedBitmap?.recycle()
            decodedBitmap = null
        }
        super.onDestroy()
    }

    private fun cropAndReturn(cropped: Bitmap) {
        try {
            if (cropped.width < MIN_CROP_PIXELS || cropped.height < MIN_CROP_PIXELS) {
                cropped.recycle()
                failAndFinish("框选区域太小，请重新截图后框选商品")
                return
            }
            val output = saveCrop(cropped)
            completed = true
            deleteSourceScreenshot()
            FloatingWindowService.reportCropComplete(applicationContext, output.absolutePath)
            finish()
        } catch (error: Exception) {
            failAndFinish("截图裁剪失败，请重试")
        }
    }

    private fun cancelAndFinish() {
        if (completed) return
        completed = true
        deleteSourceScreenshot()
        FloatingWindowService.reportCropCancelled(applicationContext)
        finish()
    }

    private fun failAndFinish(message: String) {
        if (completed) return
        completed = true
        deleteSourceScreenshot()
        FloatingWindowService.reportCropFailure(applicationContext, message)
        finish()
    }

    private fun deleteSourceScreenshot() {
        try {
            sourcePath?.let { File(it).delete() }
        } catch (_: Exception) {
        }
    }

    private fun saveCrop(bitmap: Bitmap): File {
        val output = File(cacheDir, "visioncart_crop_${System.currentTimeMillis()}.png")
        output.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return output
    }

    companion object {
        const val EXTRA_SCREENSHOT_PATH = "screenshotPath"
        private const val MIN_CROP_PIXELS = 32
        private const val MAX_BITMAP_DIMENSION = 2560
    }
}

/**
 * Decode a bitmap file with inSampleSize to avoid OOM on large screenshots.
 * Scales down so that neither width nor height exceeds [maxDimension].
 */
private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap? {
    // Step 1: read dimensions only
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val (w, h) = bounds.outWidth to bounds.outHeight
    if (w <= 0 || h <= 0) return null

    // Step 2: calculate sample size (power of 2)
    var sampleSize = 1
    while (w / sampleSize > maxDimension || h / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    // Step 3: decode with sample size
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(path, opts)
}

@Composable
private fun ScreenshotCropScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    var cropViewRef by remember { mutableStateOf<CropView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = {
                CropView(it, bitmap).also { view -> cropViewRef = view }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.68f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "拖动裁剪框选择商品",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        cropViewRef?.let { view ->
                            val cropped = view.cropBitmap()
                            onConfirm(cropped)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A7C66))
                ) {
                    Text("识别", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
