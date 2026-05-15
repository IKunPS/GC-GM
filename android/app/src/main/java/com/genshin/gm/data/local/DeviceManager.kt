package com.genshin.gm.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore("genshin_gm_device")

/**
 * 设备指纹采集器
 *
 * 设备 ID 选择优先级：
 *   1. 已持久化的设备 ID（卸载重装前保持稳定）
 *   2. ANDROID_ID（Settings.Secure.ANDROID_ID），原值不加前缀
 *      —— 与原神官方客户端取值口径一致；但 Android 8+ 按应用签名隔离，
 *         所以同一台设备上不同 APK 看到的 ANDROID_ID 不一定相同
 *   3. 随机生成的 UUID
 *
 * 一旦确定，便写入 DataStore 持久化，后续直接复用。
 */
class DeviceManager(private val context: Context) {

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private const val INVALID_ANDROID_ID = "9774d56d682e549c" // 已知有问题的常量
    }

    /**
     * 获取稳定设备 ID（同步），首次调用会落盘。
     * 当前实现采用 runBlocking 读取本地 DataStore，调用频率较低（每次网络请求一次），
     * 并通过缓存避免重复 IO。
     */
    @Volatile
    private var cachedDeviceId: String? = null

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        synchronized(this) {
            cachedDeviceId?.let { return it }

            val existing = runBlocking {
                context.deviceDataStore.data.first()[KEY_DEVICE_ID]
            }
            if (!existing.isNullOrEmpty()) {
                cachedDeviceId = existing
                return existing
            }

            val androidId = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            } catch (_: Exception) {
                null
            }

            // 优先用 ANDROID_ID 原值（不加前缀），尽量接近原神官方上报口径
            val newId = if (!androidId.isNullOrEmpty() && androidId != INVALID_ANDROID_ID) {
                androidId
            } else {
                UUID.randomUUID().toString().replace("-", "")
            }

            runBlocking {
                context.deviceDataStore.edit { it[KEY_DEVICE_ID] = newId }
            }
            cachedDeviceId = newId
            return newId
        }
    }

    fun getDeviceModel(): String = Build.MODEL ?: "unknown"

    fun getDeviceBrand(): String = Build.MANUFACTURER ?: "unknown"

    fun getSystemVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun getAppVersion(): String {
        return try {
            val pkg = context.packageName
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
