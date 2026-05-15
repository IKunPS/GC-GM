package com.genshin.gm.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("genshin_gm_prefs")

class SessionManager(private val context: Context) {

    companion object {
        private val KEY_SESSION_TOKEN = stringPreferencesKey("session_token")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_ACTIVE_UID = stringPreferencesKey("active_uid")
    }

    val sessionToken: Flow<String?> = context.dataStore.data.map { it[KEY_SESSION_TOKEN] }
    val username: Flow<String?> = context.dataStore.data.map { it[KEY_USERNAME] }
    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "http://210.16.175.19:8080" }
    val activeUid: Flow<String?> = context.dataStore.data.map { it[KEY_ACTIVE_UID] }

    suspend fun getSessionToken(): String? = sessionToken.first()
    suspend fun getUsername(): String? = username.first()
    suspend fun getServerUrl(): String = serverUrl.first()
    suspend fun getActiveUid(): String? = activeUid.first()

    suspend fun saveLogin(sessionToken: String, username: String) {
        context.dataStore.edit {
            it[KEY_SESSION_TOKEN] = sessionToken
            it[KEY_USERNAME] = username
        }
    }

    suspend fun clearLogin() {
        context.dataStore.edit {
            it.remove(KEY_SESSION_TOKEN)
            it.remove(KEY_USERNAME)
        }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun setActiveUid(uid: String?) {
        context.dataStore.edit {
            if (uid != null) it[KEY_ACTIVE_UID] = uid
            else it.remove(KEY_ACTIVE_UID)
        }
    }
}
