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
    private const val USER_ID_KEY = "user_id"
    private const val EMAIL_KEY = "email"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedPrefs = it }
            }
        }
    }

    suspend fun saveToken(context: Context, token: String, userId: Long, email: String) {
        withContext(Dispatchers.IO) {
            getPrefs(context).edit()
                .putString(TOKEN_KEY, token)
                .putString(USER_ID_KEY, userId.toString())
                .putString(EMAIL_KEY, email)
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

    suspend fun isLoggedIn(context: Context): Boolean {
        return !getToken(context).isNullOrBlank()
    }

    suspend fun clearToken(context: Context) {
        withContext(Dispatchers.IO) {
            getPrefs(context).edit().clear().apply()
        }
    }
}
