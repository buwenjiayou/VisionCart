package com.visioncart.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TokenManager {

    private const val PREFS_NAME = "auth_encrypted"
    private const val TOKEN_KEY = "jwt_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val USER_ID_KEY = "user_id"
    private const val EMAIL_KEY = "email"
    private const val TOKEN_SAVED_AT_KEY = "token_saved_at"

    /** Token validity duration — matches backend JWT expiry (default 24h) */
    private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: run {
                try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    // Keystore invalidated (backup/restore, key rotation failure)
                    // Delete corrupted preferences and recreate
                    context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    context.deleteSharedPreferences(PREFS_NAME)
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.also { cachedPrefs = it }
            }
        }
    }

    suspend fun saveToken(context: Context, token: String, refreshToken: String?, userId: Long, email: String) {
        withContext(Dispatchers.IO) {
            getPrefs(context).edit()
                .putString(TOKEN_KEY, token)
                .putString(REFRESH_TOKEN_KEY, refreshToken)
                .putString(USER_ID_KEY, userId.toString())
                .putString(EMAIL_KEY, email)
                .putLong(TOKEN_SAVED_AT_KEY, System.currentTimeMillis())
                .apply()
        }
    }

    suspend fun getToken(context: Context): String? {
        return withContext(Dispatchers.IO) {
            getPrefs(context).getString(TOKEN_KEY, null)
        }
    }

    suspend fun getEmail(context: Context): String? {
        return withContext(Dispatchers.IO) {
            getPrefs(context).getString(EMAIL_KEY, null)
        }
    }

    suspend fun getUserId(context: Context): Long? {
        return withContext(Dispatchers.IO) {
            getPrefs(context).getString(USER_ID_KEY, null)?.toLongOrNull()
        }
    }

    suspend fun getRefreshToken(context: Context): String? {
        return withContext(Dispatchers.IO) {
            getPrefs(context).getString(REFRESH_TOKEN_KEY, null)
        }
    }

    suspend fun isLoggedIn(context: Context): Boolean {
        val token = getToken(context) ?: return false
        if (token.isBlank()) return false
        // Check if token has expired based on local timestamp
        val savedAt = withContext(Dispatchers.IO) {
            getPrefs(context).getLong(TOKEN_SAVED_AT_KEY, 0L)
        }
        if (savedAt > 0 && System.currentTimeMillis() - savedAt > TOKEN_VALIDITY_MS) {
            // Token expired — clear it
            clearToken(context)
            return false
        }
        return true
    }

    suspend fun clearToken(context: Context) {
        withContext(Dispatchers.IO) {
            getPrefs(context).edit().clear().apply()
        }
    }
}
