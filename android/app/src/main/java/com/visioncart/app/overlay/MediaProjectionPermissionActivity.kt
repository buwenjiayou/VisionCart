package com.visioncart.app.overlay

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MediaProjectionPermissionActivity : ComponentActivity() {
    private var launched = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            FloatingWindowService.startScreenshot(
                applicationContext,
                result.resultCode,
                result.data!!
            )
        } else {
            FloatingWindowService.reportScreenshotFailure(
                applicationContext,
                "已取消屏幕捕获授权"
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        overridePendingTransition(0, 0)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false
        if (!launched) {
            launched = true
            requestCapturePermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun requestCapturePermission() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            permissionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (error: Exception) {
            FloatingWindowService.reportScreenshotFailure(
                applicationContext,
                "无法打开屏幕捕获授权，请重试"
            )
            finish()
        }
    }

    private companion object {
        const val KEY_LAUNCHED = "launched"
    }
}
