package com.visioncart.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.visioncart.app.BuildConfig
import com.visioncart.app.data.ApiClient

@Composable
fun rememberAuthenticatedImageModel(rawUrl: String?, token: String? = ApiClient.authToken): Any? {
    val context = LocalContext.current
    val resolvedUrl = remember(rawUrl) { resolveImageUrl(rawUrl) }
    val isBackendUrl = resolvedUrl != null && resolvedUrl.startsWith(BuildConfig.API_BASE_URL.trimEnd('/'))
    return remember(context, resolvedUrl, token) {
        if (resolvedUrl.isNullOrBlank()) {
            null
        } else if (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")) {
            ImageRequest.Builder(context)
                .data(resolvedUrl)
                .apply {
                    // Only add auth header for backend-proxied images
                    if (isBackendUrl && !token.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer $token")
                        diskCachePolicy(CachePolicy.DISABLED)
                    }
                }
                .crossfade(true)
                .build()
        } else {
            resolvedUrl
        }
    }
}

fun resolveImageUrl(rawUrl: String?): String? {
    val value = rawUrl?.trim().orEmpty()
    if (value.isBlank() || value.startsWith("upload://")) return null
    if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://") || value.startsWith("content://")) {
        return value
    }
    if (value.startsWith("/")) {
        return BuildConfig.API_BASE_URL.trimEnd('/') + value
    }
    return value
}
