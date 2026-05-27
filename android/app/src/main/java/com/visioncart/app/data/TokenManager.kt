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

    suspend fun saveToken(context: Context, token: String, userId: Long, email: String) {
        context.tokenDataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[USER_ID_KEY] = userId.toString()
            prefs[EMAIL_KEY] = email
        }
    }

    suspend fun getToken(context: Context): String? {
        return context.tokenDataStore.data.first()[TOKEN_KEY]
    }

    fun getTokenFlow(context: Context): Flow<String?> {
        return context.tokenDataStore.data.map { it[TOKEN_KEY] }
    }

    suspend fun getEmail(context: Context): String? {
        return context.tokenDataStore.data.first()[EMAIL_KEY]
    }

    suspend fun isLoggedIn(context: Context): Boolean {
        return !getToken(context).isNullOrBlank()
    }

    suspend fun clearToken(context: Context) {
        context.tokenDataStore.edit { it.clear() }
    }
}
