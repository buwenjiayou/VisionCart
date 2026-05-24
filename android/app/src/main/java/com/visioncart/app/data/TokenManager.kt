package com.visioncart.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

object TokenManager {

    private val TOKEN_KEY = stringPreferencesKey("jwt_token")
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val EMAIL_KEY = stringPreferencesKey("email")
    private val NICKNAME_KEY = stringPreferencesKey("nickname")

    /**
     * Save token and user info to DataStore (persistent across app restarts)
     */
    suspend fun saveToken(context: Context, token: String, userId: Long, email: String, nickname: String) {
        context.tokenDataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[USER_ID_KEY] = userId.toString()
            prefs[EMAIL_KEY] = email
            prefs[NICKNAME_KEY] = nickname
        }
    }

    /**
     * Get saved token, or null if not logged in
     */
    suspend fun getToken(context: Context): String? {
        return context.tokenDataStore.data.first()[TOKEN_KEY]
    }

    /**
     * Get token as Flow for reactive observation
     */
    fun getTokenFlow(context: Context): Flow<String?> {
        return context.tokenDataStore.data.map { it[TOKEN_KEY] }
    }

    /**
     * Get user email
     */
    suspend fun getEmail(context: Context): String? {
        return context.tokenDataStore.data.first()[EMAIL_KEY]
    }

    /**
     * Get user nickname
     */
    suspend fun getNickname(context: Context): String? {
        return context.tokenDataStore.data.first()[NICKNAME_KEY]
    }

    /**
     * Check if user is logged in
     */
    suspend fun isLoggedIn(context: Context): Boolean {
        return !getToken(context).isNullOrBlank()
    }

    /**
     * Clear all auth data (logout)
     */
    suspend fun clearToken(context: Context) {
        context.tokenDataStore.edit { it.clear() }
    }
}
