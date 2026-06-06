package com.visioncart.app.ui.detail

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private val ALLOWED_HOSTS = setOf(
    "taobao.com", "tmall.com", "yangkeduo.com", "pinduoduo.com",
    "ebay.com", "jd.com", "amazon.com", "amazon.cn",
    "detail.tmall.com", "item.taobao.com", "mobile.yangkeduo.com"
)

private fun isAllowedProductUrl(raw: String): Boolean {
    val uri = Uri.parse(raw)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return false
    val host = uri.host?.lowercase().orEmpty()
    return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
}

private fun safeProductUrl(url: String): String {
    return if (isAllowedProductUrl(url)) url else "about:blank"
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProductDetailScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    title,
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (webViewRef?.canGoBack() == true) {
                        webViewRef?.goBack()
                    } else {
                        onBack()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        userAgentString = userAgentString + " VisionCart/App"
                        // Security hardening
                        @Suppress("DEPRECATION")
                        allowFileAccess = false       // 禁止 file:// 访问
                        @Suppress("DEPRECATION")
                        allowContentAccess = false    // 禁止 content:// 访问
                        safeBrowsingEnabled = true    // 启用安全浏览
                        @Suppress("DEPRECATION")
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW // 禁止混合内容
                        setSupportMultipleWindows(false) // 禁止多窗口弹出
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val requestUrl = request?.url?.toString() ?: return true
                            // Block navigation outside allowed domains — open in system browser
                            if (!isAllowedProductUrl(requestUrl)) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(requestUrl))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {}
                                return true
                            }
                            return false
                        }
                    }
                    webChromeClient = WebChromeClient()
                    // Downloads delegate to system browser
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl))
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    val safeUrl = if (url.isNotBlank()) safeProductUrl(url) else "about:blank"
                    loadUrl(safeUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
