package com.sportday.mobile.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sportday_prefs")

class TokenManager(private val context: Context) {

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("jwt_token")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val ROLE_KEY = stringPreferencesKey("role")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val FULL_NAME_KEY = stringPreferencesKey("full_name")
        val BASE_URL_KEY = stringPreferencesKey("base_url")
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080/"
    }

    suspend fun saveAuth(response: com.sportday.mobile.data.model.AuthResponse) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = response.token
            prefs[USERNAME_KEY] = response.username
            prefs[ROLE_KEY] = response.role
            prefs[USER_ID_KEY] = response.userId.toString()
            prefs[FULL_NAME_KEY] = response.fullName
        }
    }

    suspend fun getToken(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[TOKEN_KEY]
        }.first()
    }

    suspend fun getUsername(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[USERNAME_KEY]
        }.first()
    }

    suspend fun getRole(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[ROLE_KEY]
        }.first()
    }

    suspend fun getUserId(): Long? {
        return context.dataStore.data.map { prefs ->
            prefs[USER_ID_KEY]?.toLongOrNull()
        }.first()
    }

    suspend fun getFullName(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[FULL_NAME_KEY]
        }.first()
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[BASE_URL_KEY] = url
        }
    }

    suspend fun getBaseUrl(): String {
        return context.dataStore.data.map { prefs ->
            prefs[BASE_URL_KEY] ?: DEFAULT_BASE_URL
        }.first()
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
