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
        const val FIXED_SERVER_URL = "http://210.16.175.19:5203"

        private val KEY_SESSION_TOKEN = stringPreferencesKey("session_token")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_ACTIVE_UID = stringPreferencesKey("active_uid")
    }

    val sessionToken: Flow<String?> = context.dataStore.data.map { it[KEY_SESSION_TOKEN] }
    val username: Flow<String?> = context.dataStore.data.map { it[KEY_USERNAME] }

    /**
     * Android APK 固定连接后端 http://210.16.175.19:5203。
     * 忽略旧版本 DataStore 中保存的服务器地址，防止覆盖安装后继续连接旧服务器。
     */
    val serverUrl: Flow<String> = context.dataStore.data.map { FIXED_SERVER_URL }

    val activeUid: Flow<String?> = context.dataStore.data.map { it[KEY_ACTIVE_UID] }

    suspend fun getSessionToken(): String? = sessionToken.first()
    suspend fun getUsername(): String? = username.first()

    suspend fun getServerUrl(): String {
        // 清理旧版本保存的服务器地址并固定到当前后端。
        context.dataStore.edit { preferences ->
            if (preferences[KEY_SERVER_URL] != FIXED_SERVER_URL) {
                preferences[KEY_SERVER_URL] = FIXED_SERVER_URL
            }
        }
        return FIXED_SERVER_URL
    }

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

    /**
     * 保留旧接口兼容调用方，但 APK 始终固定到 FIXED_SERVER_URL。
     */
    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = FIXED_SERVER_URL }
    }

    suspend fun setActiveUid(uid: String?) {
        context.dataStore.edit {
            if (uid != null) it[KEY_ACTIVE_UID] = uid
            else it.remove(KEY_ACTIVE_UID)
        }
    }
}
