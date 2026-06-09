package com.visioncart.app.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RetryInterceptor(private val maxRetries: Int = 2) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only retry idempotent methods (GET, HEAD, OPTIONS)
        val shouldRetry = request.method.equals("GET", ignoreCase = true) ||
                request.method.equals("HEAD", ignoreCase = true) ||
                request.method.equals("OPTIONS", ignoreCase = true)
        if (!shouldRetry) {
            return chain.proceed(request)
        }
        var lastException: IOException? = null
        for (attempt in 0..maxRetries) {
            try {
                val response = chain.proceed(request)
                // Don't retry on 4xx client errors (except 429 rate limit)
                if (response.code in 400..499 && response.code != 429) {
                    return response
                }
                // Retry on 429 or 5xx with backoff
                if (response.code == 429 || response.code >= 500) {
                    response.close()
                    if (attempt < maxRetries) {
                        Thread.sleep(500L * (1 shl attempt)) // exponential backoff: 500ms, 1000ms
                        lastException = IOException("HTTP ${response.code}")
                        continue
                    }
                }
                return response
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep(500L * (1 shl attempt))
                }
            }
        }
        throw lastException ?: IOException("Request failed after ${maxRetries + 1} attempts")
    }
}
