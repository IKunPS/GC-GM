package com.genshin.gm.data.proto

import com.genshin.gm.data.local.DeviceManager
import com.genshin.gm.proto.*
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Protobuf client for communicating with the Spring Boot backend.
 * All requests go through POST /api/proto using ProtoEnvelope.
 *
 * 当传入 [deviceManager] 时（推荐），每次请求都会自动附带设备指纹
 * （device_id / model / brand / system / app version），供后端落库使用。
 */
class ProtoClient(
    private val baseUrl: String,
    private val deviceManager: DeviceManager? = null,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "GenshinGM-Android/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    private val protoMediaType = "application/x-protobuf".toMediaType()

    private fun buildDeviceInfo(): DeviceInfo? {
        val dm = deviceManager ?: return null
        return DeviceInfo.newBuilder()
            .setDeviceId(dm.getDeviceId())
            .setDeviceModel(dm.getDeviceModel())
            .setDeviceBrand(dm.getDeviceBrand())
            .setSystemVersion(dm.getSystemVersion())
            .setAppVersion(dm.getAppVersion())
            .build()
    }

    private suspend fun sendProto(action: String, payload: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val envelopeBuilder = ProtoEnvelope.newBuilder()
                .setAction(action)
                .setPayload(ByteString.copyFrom(payload))
            buildDeviceInfo()?.let { envelopeBuilder.setDevice(it) }
            val envelope = envelopeBuilder.build()

            val request = Request.Builder()
                .url("$baseUrl/api/proto")
                .post(envelope.toByteArray().toRequestBody(protoMediaType))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.bytes() ?: throw Exception("Empty response")
            val responseEnvelope = ProtoEnvelope.parseFrom(body)
            responseEnvelope.payload.toByteArray()
        }

    // ==================== Auth ====================

    suspend fun register(username: String, password: String): ApiResponse {
        val req = RegisterRequest.newBuilder()
            .setUsername(username).setPassword(password).build()
        return ApiResponse.parseFrom(sendProto("auth.register", req.toByteArray()))
    }

    suspend fun login(username: String, password: String): LoginResponse {
        val req = LoginRequest.newBuilder()
            .setUsername(username).setPassword(password).build()
        return LoginResponse.parseFrom(sendProto("auth.login", req.toByteArray()))
    }

    suspend fun logout(sessionToken: String): ApiResponse {
        val req = LogoutRequest.newBuilder().setSessionToken(sessionToken).build()
        return ApiResponse.parseFrom(sendProto("auth.logout", req.toByteArray()))
    }

    suspend fun getUserInfo(sessionToken: String): UserInfoResponse {
        val req = UserInfoRequest.newBuilder().setSessionToken(sessionToken).build()
        return UserInfoResponse.parseFrom(sendProto("auth.userInfo", req.toByteArray()))
    }

    suspend fun addUid(sessionToken: String, uid: String): ApiResponse {
        val req = AddUidRequest.newBuilder()
            .setSessionToken(sessionToken).setUid(uid).build()
        return ApiResponse.parseFrom(sendProto("auth.addUid", req.toByteArray()))
    }

    suspend fun removeUid(sessionToken: String, uid: String): ApiResponse {
        val req = RemoveUidRequest.newBuilder()
            .setSessionToken(sessionToken).setUid(uid).build()
        return ApiResponse.parseFrom(sendProto("auth.removeUid", req.toByteArray()))
    }

    suspend fun checkUid(sessionToken: String, uid: String): CheckUidResponse {
        val req = CheckUidRequest.newBuilder()
            .setSessionToken(sessionToken).setUid(uid).build()
        return CheckUidResponse.parseFrom(sendProto("auth.checkUid", req.toByteArray()))
    }

    // ==================== Game Data ====================

    suspend fun getItems(): GameDataListResponse =
        GameDataListResponse.parseFrom(sendProto("gm.items", ByteArray(0)))

    suspend fun getWeapons(): GameDataListResponse =
        GameDataListResponse.parseFrom(sendProto("gm.weapons", ByteArray(0)))

    suspend fun getAvatars(): GameDataListResponse =
        GameDataListResponse.parseFrom(sendProto("gm.avatars", ByteArray(0)))

    suspend fun getQuests(): GameDataListResponse =
        GameDataListResponse.parseFrom(sendProto("gm.quests", ByteArray(0)))

    suspend fun generateGiveCommand(itemId: Int, quantity: Int): CommandResponse {
        val req = GiveCommandRequest.newBuilder()
            .setItemId(itemId).setQuantity(quantity).build()
        return CommandResponse.parseFrom(sendProto("gm.giveCommand", req.toByteArray()))
    }

    suspend fun generateQuestAddCommand(questId: Int): CommandResponse {
        val req = QuestCommandRequest.newBuilder().setQuestId(questId).build()
        return CommandResponse.parseFrom(sendProto("gm.questAdd", req.toByteArray()))
    }

    suspend fun generateQuestFinishCommand(questId: Int): CommandResponse {
        val req = QuestCommandRequest.newBuilder().setQuestId(questId).build()
        return CommandResponse.parseFrom(sendProto("gm.questFinish", req.toByteArray()))
    }

    // ==================== Grasscutter ====================

    suspend fun getGcConfig(): GrasscutterConfigResponse =
        GrasscutterConfigResponse.parseFrom(sendProto("gc.config", ByteArray(0)))

    suspend fun ping(serverUrl: String = ""): OpenCommandResult {
        val req = ServerRequest.newBuilder().setServerUrl(serverUrl).build()
        return OpenCommandResult.parseFrom(sendProto("gc.ping", req.toByteArray()))
    }

    suspend fun getOnlinePlayers(serverUrl: String = ""): OpenCommandResult {
        val req = ServerRequest.newBuilder().setServerUrl(serverUrl).build()
        return OpenCommandResult.parseFrom(sendProto("gc.online", req.toByteArray()))
    }

    suspend fun sendGcCode(uid: Int, serverUrl: String = ""): OpenCommandResult {
        val req = SendCodeRequest.newBuilder().setUid(uid).setServerUrl(serverUrl).build()
        return OpenCommandResult.parseFrom(sendProto("gc.sendCode", req.toByteArray()))
    }

    suspend fun verifyGcCode(token: String, code: Int, serverUrl: String = ""): OpenCommandResult {
        val req = VerifyCodeRequest.newBuilder()
            .setToken(token).setCode(code).setServerUrl(serverUrl).build()
        return OpenCommandResult.parseFrom(sendProto("gc.verify", req.toByteArray()))
    }

    // ==================== Player Commands ====================

    suspend fun submitCommand(
        title: String, description: String, command: String,
        category: String, uploaderName: String
    ): SubmitCommandResponse {
        val req = SubmitCommandRequest.newBuilder()
            .setTitle(title).setDescription(description)
            .setCommand(command).setCategory(category)
            .setUploaderName(uploaderName).build()
        return SubmitCommandResponse.parseFrom(sendProto("commands.submit", req.toByteArray()))
    }

    suspend fun getApprovedCommands(
        category: String = "", exclude: String = "", sort: String = "time"
    ): PlayerCommandListResponse {
        val req = GetApprovedRequest.newBuilder()
            .setCategory(category).setExclude(exclude).setSort(sort).build()
        return PlayerCommandListResponse.parseFrom(sendProto("commands.approved", req.toByteArray()))
    }

    suspend fun viewCommand(id: Long): ApiResponse {
        val req = ViewRequest.newBuilder().setId(id).build()
        return ApiResponse.parseFrom(sendProto("commands.view", req.toByteArray()))
    }

    suspend fun likeCommand(id: Long, uid: String): ApiResponse {
        val req = LikeRequest.newBuilder().setId(id).setUid(uid).build()
        return ApiResponse.parseFrom(sendProto("commands.like", req.toByteArray()))
    }

    suspend fun executePresetCommand(id: Long, uid: String, sessionToken: String): ExecuteResponse {
        val req = ExecutePresetRequest.newBuilder()
            .setId(id).setUid(uid).setSessionToken(sessionToken).build()
        return ExecuteResponse.parseFrom(sendProto("commands.execute", req.toByteArray()))
    }

    suspend fun executeCustomCommand(uid: String, command: String, sessionToken: String): ExecuteResponse {
        val req = ExecuteCustomRequest.newBuilder()
            .setUid(uid).setCommand(command).setSessionToken(sessionToken).build()
        return ExecuteResponse.parseFrom(sendProto("commands.customExecute", req.toByteArray()))
    }

    // ==================== Admin ====================

    suspend fun adminGetAll(adminToken: String): PlayerCommandListResponse {
        val req = AdminRequest.newBuilder().setAdminToken(adminToken).build()
        return PlayerCommandListResponse.parseFrom(sendProto("commands.admin.all", req.toByteArray()))
    }

    suspend fun adminGetPending(adminToken: String): PlayerCommandListResponse {
        val req = AdminRequest.newBuilder().setAdminToken(adminToken).build()
        return PlayerCommandListResponse.parseFrom(sendProto("commands.admin.pending", req.toByteArray()))
    }

    suspend fun adminApprove(id: Long, adminToken: String, reviewNote: String = "", category: String = ""): ApiResponse {
        val req = AdminReviewRequest.newBuilder()
            .setId(id).setAdminToken(adminToken)
            .setReviewNote(reviewNote).setCategory(category).build()
        return ApiResponse.parseFrom(sendProto("commands.admin.approve", req.toByteArray()))
    }

    suspend fun adminReject(id: Long, adminToken: String, reviewNote: String = ""): ApiResponse {
        val req = AdminReviewRequest.newBuilder()
            .setId(id).setAdminToken(adminToken).setReviewNote(reviewNote).build()
        return ApiResponse.parseFrom(sendProto("commands.admin.reject", req.toByteArray()))
    }

    suspend fun adminDelete(id: Long, adminToken: String): ApiResponse {
        val req = AdminReviewRequest.newBuilder()
            .setId(id).setAdminToken(adminToken).build()
        return ApiResponse.parseFrom(sendProto("commands.admin.delete", req.toByteArray()))
    }

    // ==================== Verification ====================

    suspend fun sendVerificationCode(uid: String): VerifyResponse {
        val req = VerifySendRequest.newBuilder().setUid(uid).build()
        return VerifyResponse.parseFrom(sendProto("verify.send", req.toByteArray()))
    }

    suspend fun verifyCode(uid: String, code: String): VerifyResponse {
        val req = VerifyCheckRequest.newBuilder().setUid(uid).setCode(code).build()
        return VerifyResponse.parseFrom(sendProto("verify.check", req.toByteArray()))
    }

    suspend fun getVerificationStatus(uid: String): VerifyResponse {
        val req = VerifyStatusRequest.newBuilder().setUid(uid).build()
        return VerifyResponse.parseFrom(sendProto("verify.status", req.toByteArray()))
    }

    // ==================== App Config ====================

    suspend fun getAppConfig(): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/resource/app-config")
            .header("User-Agent", "GenshinGM-Android/1.0")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        val json = org.json.JSONObject(body)
        val map = mutableMapOf<String, String>()
        json.keys().forEach { key -> map[key] = json.optString(key, "") }
        map
    }

    // ==================== Resources ====================

    suspend fun checkResources(localFiles: List<Pair<String, String>>): ResourceCheckResponse =
        withContext(Dispatchers.IO) {
            val reqBuilder = ResourceCheckRequest.newBuilder()
            localFiles.forEach { (name, md5) ->
                reqBuilder.addFiles(
                    ResourceFileInfo.newBuilder()
                        .setFileName(name).setMd5(md5).build()
                )
            }
            val request = Request.Builder()
                .url("$baseUrl/api/resource/check")
                .post(reqBuilder.build().toByteArray().toRequestBody(protoMediaType))
                .header("User-Agent", "GenshinGM-Android/1.0")
                .build()
            val response = client.newCall(request).execute()
            ResourceCheckResponse.parseFrom(response.body?.bytes() ?: ByteArray(0))
        }

    suspend fun downloadResource(downloadPath: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$downloadPath")
                .header("User-Agent", "GenshinGM-Android/1.0")
                .build()
            val response = client.newCall(request).execute()
            response.body?.bytes() ?: ByteArray(0)
        }
}
