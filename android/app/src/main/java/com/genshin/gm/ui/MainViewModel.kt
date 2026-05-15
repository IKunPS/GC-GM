package com.genshin.gm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.genshin.gm.data.local.DeviceManager
import com.genshin.gm.data.local.SessionManager
import com.genshin.gm.data.proto.ProtoClient
import com.genshin.gm.data.repository.ResourceManager
import com.genshin.gm.proto.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val sessionToken: String = "",
    val activeUid: String = "",
    val verifiedUids: List<String> = emptyList(),
    val serverUrl: String = DEFAULT_SERVER_URL,
    val isLoading: Boolean = false,
    val message: String = "",
    val items: List<GameDataItem> = emptyList(),
    val weapons: List<GameDataItem> = emptyList(),
    val avatars: List<GameDataItem> = emptyList(),
    val quests: List<GameDataItem> = emptyList(),
    val approvedCommands: List<PlayerCommandProto> = emptyList(),
    val pendingCommands: List<PlayerCommandProto> = emptyList(),
    val resourceSyncStatus: String = "",
    val generatedCommand: String = "",
    val executeResult: String = "",
    val backgroundImagePath: String? = null,
    val onlinePlayerCount: Int = 0,
    val isInitialized: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val updateVersion: String = "",
    val updateDownloadUrl: String = "",
) {
    companion object {
        const val DEFAULT_SERVER_URL = "http://210.16.175.19:8080"
    }
}

private const val DEFAULT_SERVER_URL = UiState.DEFAULT_SERVER_URL

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionManager = SessionManager(app)
    private val deviceManager = DeviceManager(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Eagerly initialize with default URL to prevent NPE
    private var protoClient: ProtoClient = ProtoClient(DEFAULT_SERVER_URL, deviceManager)
    private var resourceManager: ResourceManager = ResourceManager(app, DEFAULT_SERVER_URL)

    init {
        viewModelScope.launch {
            val url = sessionManager.getServerUrl()
            val token = sessionManager.getSessionToken()
            val username = sessionManager.getUsername()
            val uid = sessionManager.getActiveUid()

            _state.update { it.copy(serverUrl = url) }

            // Re-initialize if stored URL differs from default
            if (url != DEFAULT_SERVER_URL) {
                initClient(url)
            }

            // Sync resources (non-blocking if server unreachable)
            syncResourcesInternal()

            // Load background image
            loadBackground()

            if (token != null && username != null) {
                _state.update {
                    it.copy(
                        isLoggedIn = true,
                        sessionToken = token,
                        username = username,
                        activeUid = uid ?: ""
                    )
                }
                try {
                    refreshUserInfo()
                } catch (_: Exception) {}
            }

            // Check for app updates
            try { checkAppUpdate() } catch (_: Exception) {}

            // Fetch online player count independently
            try { fetchOnlineCount() } catch (_: Exception) {}

            // Auto-load game data after init
            loadGameDataInternal()

            _state.update { it.copy(isInitialized = true) }
        }
    }

    private fun initClient(url: String) {
        protoClient = ProtoClient(url, deviceManager)
        resourceManager = ResourceManager(getApplication(), url)
    }

    private fun loadBackground() {
        val bgDir = File(getApplication<Application>().filesDir, "data/bg")
        val bgFiles = bgDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".jpeg")) }
            ?.sortedBy { it.name }
            ?: return

        // Prefer the second image (blue bg) if available, otherwise use the first
        val bgFile = if (bgFiles.size >= 2) bgFiles[1] else bgFiles.firstOrNull()
        if (bgFile != null) {
            _state.update { it.copy(backgroundImagePath = bgFile.absolutePath) }
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            sessionManager.saveServerUrl(url)
            _state.update { it.copy(serverUrl = url) }
            initClient(url)
        }
    }

    // ==================== Auth ====================

    fun register(username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = "") }
            try {
                val resp = protoClient.register(username, password)
                _state.update { it.copy(isLoading = false, message = resp.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, message = "注册失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = "") }
            try {
                val resp = protoClient.login(username, password)
                if (resp.success) {
                    sessionManager.saveLogin(resp.sessionToken, resp.username)
                    _state.update {
                        it.copy(
                            isLoading = false, isLoggedIn = true,
                            sessionToken = resp.sessionToken,
                            username = resp.username,
                            message = "登录成功"
                        )
                    }
                    refreshUserInfo()
                } else {
                    _state.update { it.copy(isLoading = false, message = resp.message.ifEmpty { "登录失败" }) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, message = "登录失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                protoClient.logout(_state.value.sessionToken)
            } catch (_: Exception) {}
            sessionManager.clearLogin()
            _state.update {
                it.copy(
                    isLoggedIn = false, sessionToken = "", username = "",
                    activeUid = "", verifiedUids = emptyList(), message = "已登出"
                )
            }
        }
    }

    private suspend fun refreshUserInfo() {
        try {
            val resp = protoClient.getUserInfo(_state.value.sessionToken)
            if (resp.success) {
                _state.update { it.copy(verifiedUids = resp.verifiedUidsList) }
            }
        } catch (_: Exception) {}
    }

    fun setActiveUid(uid: String) {
        viewModelScope.launch {
            sessionManager.setActiveUid(uid)
            _state.update { it.copy(activeUid = uid) }
        }
    }

    // ==================== Verification & UID Binding ====================

    fun sendVerificationCode(uid: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = "") }
            try {
                val resp = protoClient.sendVerificationCode(uid)
                _state.update { it.copy(isLoading = false, message = resp.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, message = "发送失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun verifyCode(uid: String, code: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = "") }
            try {
                val resp = protoClient.verifyCode(uid, code)
                _state.update { it.copy(isLoading = false, message = resp.message) }
                if (resp.success) {
                    bindUid(uid)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, message = "验证失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun bindUid(uid: String) {
        viewModelScope.launch {
            try {
                val resp = protoClient.addUid(_state.value.sessionToken, uid)
                _state.update { it.copy(message = resp.message) }
                if (resp.success) refreshUserInfo()
            } catch (e: Exception) {
                _state.update { it.copy(message = "绑定失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun unbindUid(uid: String) {
        viewModelScope.launch {
            try {
                val resp = protoClient.removeUid(_state.value.sessionToken, uid)
                _state.update { it.copy(message = resp.message) }
                if (resp.success) {
                    refreshUserInfo()
                    if (_state.value.activeUid == uid) {
                        setActiveUid("")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "解绑失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    // ==================== Game Data ====================

    private suspend fun loadGameDataInternal() {
        _state.update { it.copy(isLoading = true) }
        try {
            // Try local cache first
            val localItems = resourceManager.parseGameData("Item.txt")
            val localWeapons = resourceManager.parseGameData("Weapon.txt")
            val localAvatars = resourceManager.parseGameData("Avatar.txt")
            val localQuests = resourceManager.parseGameData("Quest.txt")

            if (localItems.isNotEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        items = localItems.map { (id, name) ->
                            GameDataItem.newBuilder().setId(id).setName(name).build()
                        },
                        weapons = localWeapons.map { (id, name) ->
                            GameDataItem.newBuilder().setId(id).setName(name).build()
                        },
                        avatars = localAvatars.map { (id, name) ->
                            GameDataItem.newBuilder().setId(id).setName(name).build()
                        },
                        quests = localQuests.map { (id, name) ->
                            GameDataItem.newBuilder().setId(id).setName(name).build()
                        },
                    )
                }
            } else {
                // Fallback to server proto API
                val items = protoClient.getItems()
                val weapons = protoClient.getWeapons()
                val avatars = protoClient.getAvatars()
                val quests = protoClient.getQuests()
                _state.update {
                    it.copy(
                        isLoading = false,
                        items = items.itemsList,
                        weapons = weapons.itemsList,
                        avatars = avatars.itemsList,
                        quests = quests.itemsList,
                    )
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, message = "加载数据失败: ${e.message ?: "无法连接服务器"}") }
        }
    }

    fun loadGameData() {
        viewModelScope.launch {
            loadGameDataInternal()
        }
    }

    fun generateGiveCommand(itemId: Int, quantity: Int) {
        viewModelScope.launch {
            try {
                val resp = protoClient.generateGiveCommand(itemId, quantity)
                _state.update { it.copy(generatedCommand = resp.command) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "生成指令失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun generateQuestCommand(questId: Int, isFinish: Boolean) {
        viewModelScope.launch {
            try {
                val resp = if (isFinish) protoClient.generateQuestFinishCommand(questId)
                else protoClient.generateQuestAddCommand(questId)
                _state.update { it.copy(generatedCommand = resp.command) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "生成指令失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    // ==================== Command Execution ====================

    fun executeCustomCommand(command: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, executeResult = "") }
            try {
                val resp = protoClient.executeCustomCommand(
                    _state.value.activeUid, command, _state.value.sessionToken
                )
                _state.update {
                    it.copy(
                        isLoading = false,
                        executeResult = if (resp.success) "成功: ${resp.data}" else "失败: ${resp.message}",
                        message = if (resp.success) "指令执行成功" else resp.message
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, executeResult = "执行失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun executePresetCommand(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, executeResult = "") }
            try {
                val resp = protoClient.executePresetCommand(
                    id, _state.value.activeUid, _state.value.sessionToken
                )
                _state.update {
                    it.copy(
                        isLoading = false,
                        executeResult = if (resp.success) "成功: ${resp.data}" else "失败: ${resp.message}",
                        message = if (resp.success) "指令执行成功" else resp.message
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, executeResult = "执行失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    // ==================== Player Commands ====================

    fun loadApprovedCommands(category: String = "", sort: String = "time") {
        viewModelScope.launch {
            try {
                val resp = protoClient.getApprovedCommands(category = category, sort = sort)
                _state.update { it.copy(approvedCommands = resp.commandsList) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "加载指令失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun submitCommand(title: String, description: String, command: String,
                      category: String, uploaderName: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = "") }
            try {
                val resp = protoClient.submitCommand(title, description, command, category, uploaderName)
                _state.update { it.copy(isLoading = false, message = resp.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, message = "提交失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun likeCommand(id: Long) {
        viewModelScope.launch {
            try {
                val resp = protoClient.likeCommand(id, _state.value.activeUid)
                _state.update { it.copy(message = resp.message) }
                loadApprovedCommands()
            } catch (e: Exception) {
                _state.update { it.copy(message = "点赞失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    // ==================== Admin ====================

    fun loadPendingCommands(adminToken: String) {
        viewModelScope.launch {
            try {
                val resp = protoClient.adminGetPending(adminToken)
                _state.update { it.copy(pendingCommands = resp.commandsList) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "加载失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun approveCommand(id: Long, adminToken: String, reviewNote: String = "") {
        viewModelScope.launch {
            try {
                protoClient.adminApprove(id, adminToken, reviewNote)
                _state.update { it.copy(message = "审核通过") }
                loadPendingCommands(adminToken)
            } catch (e: Exception) {
                _state.update { it.copy(message = "操作失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    fun rejectCommand(id: Long, adminToken: String, reviewNote: String = "") {
        viewModelScope.launch {
            try {
                protoClient.adminReject(id, adminToken, reviewNote)
                _state.update { it.copy(message = "已拒绝") }
                loadPendingCommands(adminToken)
            } catch (e: Exception) {
                _state.update { it.copy(message = "操作失败: ${e.message ?: "网络连接失败"}") }
            }
        }
    }

    // ==================== Resources ====================

    private suspend fun syncResourcesInternal() {
        _state.update { it.copy(resourceSyncStatus = "正在同步资源...") }
        try {
            val updated = resourceManager.syncResources()
            _state.update {
                it.copy(
                    resourceSyncStatus = if (updated.isEmpty()) "资源已是最新"
                    else "已更新 ${updated.size} 个文件"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(resourceSyncStatus = "同步失败: ${e.message ?: "无法连接服务器"}") }
        }
        // Fetch online player count
        fetchOnlineCount()
    }

    private suspend fun fetchOnlineCount() {
        try {
            val resp = protoClient.getOnlinePlayers()
            // Accept retcode 0 or 200 (server uses both)
            if ((resp.retcode == 0 || resp.retcode == 200) && resp.data.isNotEmpty()) {
                val count = try {
                    val json = org.json.JSONObject(resp.data)
                    json.optInt("count", 0)
                } catch (_: Exception) {
                    // Try parsing as plain number
                    resp.data.trim().toIntOrNull() ?: 0
                }
                _state.update { it.copy(onlinePlayerCount = count) }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "fetchOnlineCount failed: ${e.message}")
        }
    }

    fun syncResources() {
        viewModelScope.launch {
            syncResourcesInternal()
            loadBackground()
        }
    }

    // ==================== App Update ====================

    private suspend fun checkAppUpdate() {
        val config = protoClient.getAppConfig()
        val serverVersion = config["version"] ?: return
        val localVersion = getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: return
        if (serverVersion != localVersion) {
            val downloadUrl = config["downloadUrl"] ?: ""
            _state.update {
                it.copy(
                    showUpdateDialog = true,
                    updateVersion = serverVersion,
                    updateDownloadUrl = downloadUrl
                )
            }
        }
    }

    fun dismissUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = "") }
    }
}
