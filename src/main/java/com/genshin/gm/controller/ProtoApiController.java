package com.genshin.gm.controller;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.GameData;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.model.PlayerCommand;
import com.genshin.gm.proto.*;
import com.genshin.gm.service.*;
import com.genshin.gm.util.CommandProcessor;
import com.genshin.gm.util.SecurityLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Protobuf API Controller
 * All protobuf communication goes through POST /api/proto
 * Request/Response: application/x-protobuf
 */
@RestController
@RequestMapping("/api/proto")
@CrossOrigin(originPatterns = "*")
public class ProtoApiController {

    private static final Logger logger = LoggerFactory.getLogger(ProtoApiController.class);
    private static final String PROTO_MEDIA_TYPE = "application/x-protobuf";

    @Autowired
    private UserService userService;

    @Autowired
    private GMService gmService;

    @Autowired
    private GrasscutterService grasscutterService;

    @Autowired
    private PlayerCommandService playerCommandService;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private DeviceService deviceService;

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    @PostMapping(consumes = PROTO_MEDIA_TYPE, produces = PROTO_MEDIA_TYPE)
    public ResponseEntity<byte[]> handleProto(@RequestBody byte[] body, HttpServletRequest request) {
        try {
            ProtoEnvelope envelope = ProtoEnvelope.parseFrom(body);
            String action = envelope.getAction();
            byte[] payload = envelope.getPayload().toByteArray();

            recordDeviceForRequest(envelope, action, payload, request);

            byte[] responsePayload = dispatch(envelope, action, payload, request);

            linkDeviceAfterSuccess(envelope, action, responsePayload);

            ProtoEnvelope response = ProtoEnvelope.newBuilder()
                    .setAction(action)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(responsePayload))
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(PROTO_MEDIA_TYPE))
                    .body(response.toByteArray());

        } catch (InvalidProtocolBufferException e) {
            logger.error("Invalid protobuf request", e);
            ApiResponse errorResp = ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid protobuf format")
                    .build();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.parseMediaType(PROTO_MEDIA_TYPE))
                    .body(errorResp.toByteArray());
        } catch (Exception e) {
            logger.error("Proto API error", e);
            ApiResponse errorResp = ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Server error: " + e.getMessage())
                    .build();
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.parseMediaType(PROTO_MEDIA_TYPE))
                    .body(errorResp.toByteArray());
        }
    }

    private byte[] dispatch(ProtoEnvelope envelope, String action, byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        return switch (action) {
            // Auth
            case "auth.register" -> handleRegister(envelope, payload);
            case "auth.login" -> handleLogin(payload);
            case "auth.logout" -> handleLogout(payload);
            case "auth.userInfo" -> handleUserInfo(payload);
            case "auth.addUid" -> handleAddUid(payload, request);
            case "auth.removeUid" -> handleRemoveUid(payload);
            case "auth.checkUid" -> handleCheckUid(payload);

            // Game Data
            case "gm.items" -> handleGameDataList("Item.txt");
            case "gm.weapons" -> handleGameDataList("Weapon.txt");
            case "gm.avatars" -> handleGameDataList("Avatar.txt");
            case "gm.quests" -> handleGameDataList("Quest.txt");
            case "gm.giveCommand" -> handleGiveCommand(payload);
            case "gm.questAdd" -> handleQuestAddCommand(payload);
            case "gm.questFinish" -> handleQuestFinishCommand(payload);

            // Grasscutter
            case "gc.config" -> handleGcConfig();
            case "gc.ping" -> handleGcPing(payload);
            case "gc.online" -> handleGcOnline(payload);
            case "gc.sendCode" -> handleGcSendCode(payload);
            case "gc.verify" -> handleGcVerify(payload);
            case "gc.command" -> handleGcCommand(payload);
            case "gc.console" -> handleGcConsole(payload, request);
            case "gc.runmode" -> handleGcRunMode(payload);

            // Player Commands
            case "commands.submit" -> handleSubmitCommand(payload, request);
            case "commands.approved" -> handleApprovedCommands(payload);
            case "commands.view" -> handleViewCommand(payload);
            case "commands.like" -> handleLikeCommand(payload);
            case "commands.execute" -> handleExecutePreset(payload, request);
            case "commands.customExecute" -> handleCustomExecute(payload, request);

            // Admin
            case "commands.admin.all" -> handleAdminAll(payload, request);
            case "commands.admin.pending" -> handleAdminPending(payload, request);
            case "commands.admin.approve" -> handleAdminApprove(payload, request);
            case "commands.admin.reject" -> handleAdminReject(payload, request);
            case "commands.admin.delete" -> handleAdminDelete(payload, request);

            // Verification
            case "verify.send" -> handleVerifySend(payload, request);
            case "verify.check" -> handleVerifyCheck(payload, request);
            case "verify.status" -> handleVerifyStatus(payload);

            default -> ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Unknown action: " + action)
                    .build().toByteArray();
        };
    }

    // ==================== Auth Handlers ====================

    private byte[] handleRegister(ProtoEnvelope envelope, byte[] payload) throws InvalidProtocolBufferException {
        RegisterRequest req = RegisterRequest.parseFrom(payload);

        // 强制：注册必须携带 device_id（防止脚本/PC 浏览器绕过设备唯一限制）
        String deviceId = (envelope.hasDevice()) ? envelope.getDevice().getDeviceId() : "";
        if (deviceId == null || deviceId.isEmpty()) {
            return ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("注册请求缺少设备标识，请使用 APK 客户端注册")
                    .build().toByteArray();
        }

        Map<String, Object> result = userService.register(req.getUsername(), req.getPassword(), deviceId);
        return ApiResponse.newBuilder()
                .setSuccess((Boolean) result.getOrDefault("success", false))
                .setMessage((String) result.getOrDefault("message", ""))
                .build().toByteArray();
    }

    private byte[] handleLogin(byte[] payload) throws InvalidProtocolBufferException {
        LoginRequest req = LoginRequest.parseFrom(payload);
        Map<String, Object> result = userService.login(req.getUsername(), req.getPassword());
        LoginResponse.Builder builder = LoginResponse.newBuilder()
                .setSuccess((Boolean) result.getOrDefault("success", false))
                .setMessage((String) result.getOrDefault("message", ""));
        if (result.containsKey("sessionToken")) {
            builder.setSessionToken((String) result.get("sessionToken"));
        }
        if (result.containsKey("username")) {
            builder.setUsername((String) result.get("username"));
        }
        return builder.build().toByteArray();
    }

    private byte[] handleLogout(byte[] payload) throws InvalidProtocolBufferException {
        LogoutRequest req = LogoutRequest.parseFrom(payload);
        userService.logout(req.getSessionToken());
        return ApiResponse.newBuilder()
                .setSuccess(true)
                .setMessage("登出成功")
                .build().toByteArray();
    }

    private byte[] handleUserInfo(byte[] payload) throws InvalidProtocolBufferException {
        UserInfoRequest req = UserInfoRequest.parseFrom(payload);
        Map<String, Object> result = userService.getUserInfo(req.getSessionToken());
        UserInfoResponse.Builder builder = UserInfoResponse.newBuilder()
                .setSuccess((Boolean) result.getOrDefault("success", false))
                .setMessage((String) result.getOrDefault("message", ""));
        if (result.containsKey("username")) {
            builder.setUsername((String) result.get("username"));
        }
        Object uids = result.get("verifiedUids");
        if (uids instanceof java.util.Collection) {
            builder.addAllVerifiedUids((java.util.Collection<String>) uids);
        }
        return builder.build().toByteArray();
    }

    private byte[] handleAddUid(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AddUidRequest req = AddUidRequest.parseFrom(payload);
        String clientIp = getClientIp(request);
        String username = userService.validateSession(req.getSessionToken());

        if (username == null) {
            return ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("未登录或session已过期")
                    .build().toByteArray();
        }

        if (!verificationService.isVerified(req.getUid())) {
            SecurityLogger.logUnauthorizedUidAttempt(
                    clientIp, username, userService.getVerifiedUidsString(username),
                    req.getUid(), null, "尝试绑定未经验证码验证的UID到账户");
            return ApiResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("该UID尚未通过验证码验证，请先在游戏内完成验证")
                    .build().toByteArray();
        }

        boolean success = userService.addVerifiedUid(username, req.getUid());
        if (success) {
            SecurityLogger.logAction(clientIp, username, req.getUid(), "BIND_UID", "成功绑定UID到账户");
        }
        return ApiResponse.newBuilder()
                .setSuccess(success)
                .setMessage(success ? "UID已添加到您的账户" : "添加UID失败")
                .build().toByteArray();
    }

    private byte[] handleRemoveUid(byte[] payload) throws InvalidProtocolBufferException {
        RemoveUidRequest req = RemoveUidRequest.parseFrom(payload);
        String username = userService.validateSession(req.getSessionToken());
        if (username == null) {
            return ApiResponse.newBuilder()
                    .setSuccess(false).setMessage("未登录或session已过期")
                    .build().toByteArray();
        }
        boolean success = userService.removeVerifiedUid(username, req.getUid());
        return ApiResponse.newBuilder()
                .setSuccess(success)
                .setMessage(success ? "UID已移除" : "移除UID失败")
                .build().toByteArray();
    }

    private byte[] handleCheckUid(byte[] payload) throws InvalidProtocolBufferException {
        CheckUidRequest req = CheckUidRequest.parseFrom(payload);
        String username = userService.validateSession(req.getSessionToken());
        if (username == null) {
            return CheckUidResponse.newBuilder()
                    .setSuccess(false).setVerified(false).setMessage("未登录或session已过期")
                    .build().toByteArray();
        }
        boolean verified = userService.isUidVerified(username, req.getUid());
        return CheckUidResponse.newBuilder()
                .setSuccess(true).setVerified(verified)
                .build().toByteArray();
    }

    // ==================== Game Data Handlers ====================

    private byte[] handleGameDataList(String fileName) {
        List<GameData> items = switch (fileName) {
            case "Item.txt" -> gmService.getItems();
            case "Weapon.txt" -> gmService.getWeapons();
            case "Avatar.txt" -> gmService.getAvatars();
            case "Quest.txt" -> gmService.getQuests();
            default -> List.of();
        };
        GameDataListResponse.Builder builder = GameDataListResponse.newBuilder();
        for (GameData item : items) {
            builder.addItems(GameDataItem.newBuilder()
                    .setId(item.getId())
                    .setName(item.getName())
                    .build());
        }
        return builder.build().toByteArray();
    }

    private byte[] handleGiveCommand(byte[] payload) throws InvalidProtocolBufferException {
        GiveCommandRequest req = GiveCommandRequest.parseFrom(payload);
        String cmd = gmService.generateGiveCommand(req.getItemId(), req.getQuantity());
        return CommandResponse.newBuilder().setSuccess(true).setCommand(cmd).build().toByteArray();
    }

    private byte[] handleQuestAddCommand(byte[] payload) throws InvalidProtocolBufferException {
        QuestCommandRequest req = QuestCommandRequest.parseFrom(payload);
        String cmd = gmService.generateQuestAddCommand(req.getQuestId());
        return CommandResponse.newBuilder().setSuccess(true).setCommand(cmd).build().toByteArray();
    }

    private byte[] handleQuestFinishCommand(byte[] payload) throws InvalidProtocolBufferException {
        QuestCommandRequest req = QuestCommandRequest.parseFrom(payload);
        String cmd = gmService.generateQuestFinishCommand(req.getQuestId());
        return CommandResponse.newBuilder().setSuccess(true).setCommand(cmd).build().toByteArray();
    }

    // ==================== Grasscutter Handlers ====================

    private byte[] handleGcConfig() {
        AppConfig.GrasscutterConfig config = ConfigLoader.getConfig().getGrasscutter();
        return GrasscutterConfigResponse.newBuilder()
                .setServerUrl(config.getServerUrl())
                .setApiPath(config.getApiPath())
                .setFullUrl(config.getFullUrl())
                .setHasConsoleToken(!config.getConsoleToken().isEmpty())
                .build().toByteArray();
    }

    private byte[] handleGcPing(byte[] payload) throws InvalidProtocolBufferException {
        ServerRequest req = ServerRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.ping(url);
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcOnline(byte[] payload) throws InvalidProtocolBufferException {
        ServerRequest req = ServerRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.getOnlinePlayers(url);
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcSendCode(byte[] payload) throws InvalidProtocolBufferException {
        SendCodeRequest req = SendCodeRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.sendCode(url, req.getUid());
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcVerify(byte[] payload) throws InvalidProtocolBufferException {
        VerifyCodeRequest req = VerifyCodeRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.verifyCode(url, req.getToken(), req.getCode());
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcCommand(byte[] payload) throws InvalidProtocolBufferException {
        GcCommandRequest req = GcCommandRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.executeCommand(url, req.getToken(), req.getCommand());
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcConsole(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        ConsoleCommandRequest req = ConsoleCommandRequest.parseFrom(payload);
        String configAdminToken = ConfigLoader.getConfig().getGrasscutter().getAdminToken();
        String clientIp = getClientIp(request);

        if (configAdminToken == null || !configAdminToken.equals(req.getAdminToken())) {
            SecurityLogger.logAction(clientIp, null, null, "ADMIN_BAD_TOKEN", "adminToken验证失败");
            return OpenCommandResult.newBuilder()
                    .setRetcode(403).setMessage("管理员认证失败")
                    .build().toByteArray();
        }

        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        String consoleToken = ConfigLoader.getConfig().getGrasscutter().getConsoleToken();
        OpenCommandResponse resp = grasscutterService.executeConsoleCommand(
                url, consoleToken, req.getCommand(), clientIp, "ADMIN_CONSOLE", null);
        return toOpenCommandResult(resp);
    }

    private byte[] handleGcRunMode(byte[] payload) throws InvalidProtocolBufferException {
        RunModeRequest req = RunModeRequest.parseFrom(payload);
        String url = req.getServerUrl().isEmpty()
                ? ConfigLoader.getConfig().getGrasscutter().getFullUrl() : req.getServerUrl();
        OpenCommandResponse resp = grasscutterService.getRunMode(url, req.getToken());
        return toOpenCommandResult(resp);
    }

    private byte[] toOpenCommandResult(OpenCommandResponse resp) {
        String dataStr = "";
        if (resp.getData() != null) {
            if (resp.getData() instanceof String) {
                dataStr = (String) resp.getData();
            } else {
                // Serialize Map/Object to proper JSON instead of Java toString()
                try {
                    dataStr = new ObjectMapper().writeValueAsString(resp.getData());
                } catch (Exception e) {
                    dataStr = resp.getData().toString();
                }
            }
        }
        return OpenCommandResult.newBuilder()
                .setRetcode(resp.getRetcode())
                .setMessage(resp.getMessage() != null ? resp.getMessage() : "")
                .setData(dataStr)
                .build().toByteArray();
    }

    // ==================== Player Command Handlers ====================

    private byte[] handleSubmitCommand(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        SubmitCommandRequest req = SubmitCommandRequest.parseFrom(payload);
        String clientIp = getClientIp(request);
        SecurityLogger.logAction(clientIp, null, null, "SUBMIT_CMD", "提交指令: " + req.getCommand());

        String validationError = CommandProcessor.validateCommand(req.getCommand());
        if (validationError != null) {
            return SubmitCommandResponse.newBuilder()
                    .setSuccess(false).setMessage(validationError)
                    .build().toByteArray();
        }

        PlayerCommand pc = new PlayerCommand();
        pc.setTitle(req.getTitle());
        pc.setDescription(req.getDescription());
        pc.setCommand(req.getCommand());
        pc.setCategory(req.getCategory());
        pc.setUploaderName(req.getUploaderName());

        PlayerCommand saved = playerCommandService.submitCommand(pc);
        return SubmitCommandResponse.newBuilder()
                .setSuccess(true)
                .setMessage("指令提交成功，等待审核")
                .setData(toPlayerCommandProto(saved))
                .build().toByteArray();
    }

    private byte[] handleApprovedCommands(byte[] payload) throws InvalidProtocolBufferException {
        GetApprovedRequest req = GetApprovedRequest.parseFrom(payload);
        List<PlayerCommand> commands;

        if (!req.getCategory().isEmpty()) {
            commands = playerCommandService.getApprovedCommandsByCategory(req.getCategory());
        } else if (!req.getExclude().isEmpty()) {
            commands = playerCommandService.getApprovedCommandsExcludeCategories(
                    List.of(req.getExclude().split(",")));
        } else if ("popular".equals(req.getSort())) {
            commands = playerCommandService.getPopularCommands();
        } else {
            commands = playerCommandService.getApprovedCommands();
        }

        PlayerCommandListResponse.Builder builder = PlayerCommandListResponse.newBuilder();
        for (PlayerCommand cmd : commands) {
            builder.addCommands(toPlayerCommandProto(cmd));
        }
        return builder.build().toByteArray();
    }

    private byte[] handleViewCommand(byte[] payload) throws InvalidProtocolBufferException {
        ViewRequest req = ViewRequest.parseFrom(payload);
        playerCommandService.incrementViews(req.getId());
        return ApiResponse.newBuilder().setSuccess(true).build().toByteArray();
    }

    private byte[] handleLikeCommand(byte[] payload) throws InvalidProtocolBufferException {
        LikeRequest req = LikeRequest.parseFrom(payload);
        boolean success = playerCommandService.likeCommand(req.getId(), req.getUid());
        return ApiResponse.newBuilder()
                .setSuccess(success)
                .setMessage(success ? "点赞成功" : "您已经点过赞了")
                .build().toByteArray();
    }

    private byte[] handleExecutePreset(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        ExecutePresetRequest req = ExecutePresetRequest.parseFrom(payload);
        String clientIp = getClientIp(request);

        Optional<PlayerCommand> optional = playerCommandService.getCommandById(req.getId());
        if (optional.isEmpty()) {
            return ExecuteResponse.newBuilder()
                    .setSuccess(false).setMessage("指令不存在")
                    .build().toByteArray();
        }

        PlayerCommand command = optional.get();
        ExecuteResponse validateResult = validateUidOwnership(req.getSessionToken(), req.getUid(),
                command.getCommand(), request);
        if (validateResult != null) {
            return validateResult.toByteArray();
        }

        playerCommandService.incrementViews(req.getId());
        AppConfig.GrasscutterConfig gcConfig = ConfigLoader.getConfig().getGrasscutter();
        String finalCommand = CommandProcessor.processCommand(command.getCommand(), req.getUid());
        String callerUsername = userService.validateSession(req.getSessionToken());

        SecurityLogger.logAction(clientIp, callerUsername, req.getUid(), "CMD_SEND",
                "发送预设指令到GC: " + finalCommand);

        OpenCommandResponse result = grasscutterService.executeConsoleCommand(
                gcConfig.getFullUrl(), gcConfig.getConsoleToken(), finalCommand,
                clientIp, callerUsername, req.getUid());

        return buildExecuteResponse(result, finalCommand, clientIp, callerUsername, req.getUid());
    }

    private byte[] handleCustomExecute(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        ExecuteCustomRequest req = ExecuteCustomRequest.parseFrom(payload);
        String clientIp = getClientIp(request);
        String command = req.getCommand().trim();

        String dangerousCheck = CommandProcessor.checkDangerousCommand(command);
        if (dangerousCheck != null) {
            SecurityLogger.logAction(clientIp, null, req.getUid(), "DANGEROUS_CMD",
                    "尝试执行危险指令: " + command);
            return ExecuteResponse.newBuilder()
                    .setSuccess(false).setMessage(dangerousCheck).setDangerous(true)
                    .build().toByteArray();
        }

        String validationError = CommandProcessor.validateCommand(command);
        if (validationError != null) {
            return ExecuteResponse.newBuilder()
                    .setSuccess(false).setMessage("指令格式错误: " + validationError)
                    .build().toByteArray();
        }

        ExecuteResponse validateResult = validateUidOwnership(req.getSessionToken(), req.getUid(),
                command, request);
        if (validateResult != null) {
            return validateResult.toByteArray();
        }

        String finalCommand = CommandProcessor.processCommand(command, req.getUid());
        String callerUsername = userService.validateSession(req.getSessionToken());

        SecurityLogger.logAction(clientIp, callerUsername, req.getUid(), "CUSTOM_CMD_SEND",
                "发送自定义指令到GC: " + finalCommand);

        AppConfig.GrasscutterConfig gcConfig = ConfigLoader.getConfig().getGrasscutter();
        OpenCommandResponse result = grasscutterService.executeConsoleCommand(
                gcConfig.getFullUrl(), gcConfig.getConsoleToken(), finalCommand,
                clientIp, callerUsername, req.getUid());

        return buildExecuteResponse(result, finalCommand, clientIp, callerUsername, req.getUid());
    }

    private byte[] buildExecuteResponse(OpenCommandResponse result, String finalCommand,
                                         String clientIp, String username, String uid) {
        if (result != null && result.getRetcode() == 200) {
            String data = result.getData() != null ? result.getData().toString() : "";
            SecurityLogger.logAction(clientIp, username, uid, "CMD_OK", finalCommand);
            return ExecuteResponse.newBuilder()
                    .setSuccess(true).setMessage("指令执行成功").setData(data)
                    .build().toByteArray();
        }
        String errorMsg = result != null ? result.getMessage() : "未知错误";
        SecurityLogger.logAction(clientIp, username, uid, "CMD_FAIL", "错误: " + errorMsg);
        return ExecuteResponse.newBuilder()
                .setSuccess(false).setMessage("执行失败: " + errorMsg)
                .build().toByteArray();
    }

    private ExecuteResponse validateUidOwnership(String sessionToken, String uid,
                                                  String command, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            SecurityLogger.logAction(clientIp, null, uid, "NO_LOGIN", "未登录尝试执行指令");
            return ExecuteResponse.newBuilder()
                    .setSuccess(false).setMessage("请先登录账户并绑定UID后再执行操作").setNeedLogin(true)
                    .build();
        }
        String username = userService.validateSession(sessionToken);
        if (username == null) {
            return ExecuteResponse.newBuilder()
                    .setSuccess(false).setMessage("登录已过期，请重新登录")
                    .build();
        }
        if (userService.isUidVerified(username, uid)) {
            SecurityLogger.logAction(clientIp, username, uid, "AUTH_OK", "鉴权通过");
            return null;
        }
        String boundUids = userService.getVerifiedUidsString(username);
        SecurityLogger.logUnauthorizedUidAttempt(clientIp, username, boundUids, uid, command,
                "已登录用户尝试操作非绑定UID");
        return ExecuteResponse.newBuilder()
                .setSuccess(false).setMessage("该UID未绑定到您的账户，无法执行操作")
                .build();
    }

    // ==================== Admin Handlers ====================

    private byte[] handleAdminAll(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AdminRequest req = AdminRequest.parseFrom(payload);
        String err = checkAdmin(req.getAdminToken(), request);
        if (err != null) {
            return ApiResponse.newBuilder().setSuccess(false).setMessage(err).build().toByteArray();
        }
        List<PlayerCommand> commands = playerCommandService.getAllCommands();
        PlayerCommandListResponse.Builder builder = PlayerCommandListResponse.newBuilder();
        for (PlayerCommand cmd : commands) {
            builder.addCommands(toPlayerCommandProto(cmd));
        }
        return builder.build().toByteArray();
    }

    private byte[] handleAdminPending(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AdminRequest req = AdminRequest.parseFrom(payload);
        String err = checkAdmin(req.getAdminToken(), request);
        if (err != null) {
            return ApiResponse.newBuilder().setSuccess(false).setMessage(err).build().toByteArray();
        }
        List<PlayerCommand> commands = playerCommandService.getPendingCommands();
        PlayerCommandListResponse.Builder builder = PlayerCommandListResponse.newBuilder();
        for (PlayerCommand cmd : commands) {
            builder.addCommands(toPlayerCommandProto(cmd));
        }
        return builder.build().toByteArray();
    }

    private byte[] handleAdminApprove(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AdminReviewRequest req = AdminReviewRequest.parseFrom(payload);
        String err = checkAdmin(req.getAdminToken(), request);
        if (err != null) {
            return ApiResponse.newBuilder().setSuccess(false).setMessage(err).build().toByteArray();
        }
        PlayerCommand updated = playerCommandService.approveCommand(
                req.getId(), req.getReviewNote(), req.getCategory().isEmpty() ? null : req.getCategory());
        SecurityLogger.logAction(getClientIp(request), "ADMIN", null, "ADMIN_APPROVE",
                "审核通过指令: " + req.getId());
        return SubmitCommandResponse.newBuilder()
                .setSuccess(updated != null).setMessage(updated != null ? "审核通过" : "指令不存在")
                .build().toByteArray();
    }

    private byte[] handleAdminReject(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AdminReviewRequest req = AdminReviewRequest.parseFrom(payload);
        String err = checkAdmin(req.getAdminToken(), request);
        if (err != null) {
            return ApiResponse.newBuilder().setSuccess(false).setMessage(err).build().toByteArray();
        }
        PlayerCommand updated = playerCommandService.rejectCommand(
                req.getId(), req.getReviewNote(), req.getCategory().isEmpty() ? null : req.getCategory());
        SecurityLogger.logAction(getClientIp(request), "ADMIN", null, "ADMIN_REJECT",
                "拒绝指令: " + req.getId());
        return SubmitCommandResponse.newBuilder()
                .setSuccess(updated != null).setMessage(updated != null ? "已拒绝" : "指令不存在")
                .build().toByteArray();
    }

    private byte[] handleAdminDelete(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        AdminReviewRequest req = AdminReviewRequest.parseFrom(payload);
        String err = checkAdmin(req.getAdminToken(), request);
        if (err != null) {
            return ApiResponse.newBuilder().setSuccess(false).setMessage(err).build().toByteArray();
        }
        playerCommandService.deleteCommand(req.getId());
        SecurityLogger.logAction(getClientIp(request), "ADMIN", null, "ADMIN_DELETE",
                "删除指令: " + req.getId());
        return ApiResponse.newBuilder().setSuccess(true).setMessage("删除成功").build().toByteArray();
    }

    private String checkAdmin(String adminToken, HttpServletRequest request) {
        String configAdminToken = ConfigLoader.getConfig().getGrasscutter().getAdminToken();
        if (configAdminToken == null || configAdminToken.isEmpty()
                || "CHANGE_ME_TO_A_SECURE_RANDOM_STRING".equals(configAdminToken)) {
            return "管理员Token未配置";
        }
        if (adminToken == null || !configAdminToken.equals(adminToken.trim())) {
            SecurityLogger.logAction(getClientIp(request), null, null, "ADMIN_BAD_TOKEN",
                    "adminToken验证失败");
            return "管理员认证失败";
        }
        return null;
    }

    // ==================== Verification Handlers ====================

    private byte[] handleVerifySend(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        VerifySendRequest req = VerifySendRequest.parseFrom(payload);
        String clientIp = getClientIp(request);
        SecurityLogger.logAction(clientIp, null, req.getUid(), "SEND_CODE", "请求发送验证码");
        Map<String, Object> result = verificationService.sendVerificationCode(req.getUid());
        VerifyResponse.Builder builder = VerifyResponse.newBuilder()
                .setSuccess((Boolean) result.getOrDefault("success", false))
                .setMessage((String) result.getOrDefault("message", ""));
        if (result.containsKey("token")) {
            builder.setToken((String) result.get("token"));
        }
        return builder.build().toByteArray();
    }

    private byte[] handleVerifyCheck(byte[] payload, HttpServletRequest request)
            throws InvalidProtocolBufferException {
        VerifyCheckRequest req = VerifyCheckRequest.parseFrom(payload);
        String clientIp = getClientIp(request);
        Map<String, Object> result = verificationService.verifyCode(req.getUid(), req.getCode());
        Boolean success = (Boolean) result.get("success");
        if (Boolean.TRUE.equals(success)) {
            SecurityLogger.logAction(clientIp, null, req.getUid(), "VERIFY_SUCCESS", "验证码验证成功");
        } else {
            SecurityLogger.logAction(clientIp, null, req.getUid(), "VERIFY_FAIL",
                    "验证码验证失败: " + result.get("message"));
        }
        return VerifyResponse.newBuilder()
                .setSuccess(Boolean.TRUE.equals(success))
                .setMessage((String) result.getOrDefault("message", ""))
                .build().toByteArray();
    }

    private byte[] handleVerifyStatus(byte[] payload) throws InvalidProtocolBufferException {
        VerifyStatusRequest req = VerifyStatusRequest.parseFrom(payload);
        Map<String, Object> status = verificationService.getVerificationStatus(req.getUid());
        return VerifyResponse.newBuilder()
                .setVerified((Boolean) status.getOrDefault("verified", false))
                .setMessage((String) status.getOrDefault("message", ""))
                .build().toByteArray();
    }

    // ==================== Device Tracking ====================

    /**
     * 每次 proto 请求落库一次设备指纹。
     * - 通过 envelope.device 取设备信息
     * - 尝试从 payload 解出 sessionToken，关联到已登录用户名
     * - 失败不影响主流程
     */
    private void recordDeviceForRequest(ProtoEnvelope envelope, String action,
                                         byte[] payload, HttpServletRequest request) {
        try {
            if (!envelope.hasDevice()) {
                return;
            }
            DeviceInfo device = envelope.getDevice();
            if (device.getDeviceId().isEmpty()) {
                return;
            }

            // 在 dispatch 之前，不能信任 payload 中的 username（密码尚未验证）
            // 所以登录场景一律先按匿名落库，dispatch 成功后再 linkDeviceAfterSuccess
            String username = resolveUsernameFromPayload(action, payload);
            String ip = getClientIp(request);
            deviceService.recordDevice(device, username, ip, action);
        } catch (Exception e) {
            logger.debug("recordDeviceForRequest skipped: {}", e.getMessage());
        }
    }

    /**
     * dispatch 之后处理：登录成功后把匿名设备记录合并到登录的用户名下
     */
    private void linkDeviceAfterSuccess(ProtoEnvelope envelope, String action, byte[] responsePayload) {
        try {
            if (!"auth.login".equals(action) || !envelope.hasDevice()) {
                return;
            }
            String deviceId = envelope.getDevice().getDeviceId();
            if (deviceId.isEmpty()) {
                return;
            }
            LoginResponse resp = LoginResponse.parseFrom(responsePayload);
            if (resp.getSuccess() && !resp.getUsername().isEmpty()) {
                deviceService.linkAnonymousDeviceToUser(deviceId, resp.getUsername());
            }
        } catch (Exception e) {
            logger.debug("linkDeviceAfterSuccess skipped: {}", e.getMessage());
        }
    }

    /**
     * 不同 action 的 payload 字段不同，从中尽力解出 sessionToken 对应的用户名
     * - 对于 auth.register / auth.login，返回 null（按匿名落库）
     */
    private String resolveUsernameFromPayload(String action, byte[] payload) {
        try {
            String sessionToken = null;
            switch (action) {
                case "auth.logout":
                    sessionToken = LogoutRequest.parseFrom(payload).getSessionToken();
                    break;
                case "auth.userInfo":
                    sessionToken = UserInfoRequest.parseFrom(payload).getSessionToken();
                    break;
                case "auth.addUid":
                    sessionToken = AddUidRequest.parseFrom(payload).getSessionToken();
                    break;
                case "auth.removeUid":
                    sessionToken = RemoveUidRequest.parseFrom(payload).getSessionToken();
                    break;
                case "auth.checkUid":
                    sessionToken = CheckUidRequest.parseFrom(payload).getSessionToken();
                    break;
                case "commands.execute":
                    sessionToken = ExecutePresetRequest.parseFrom(payload).getSessionToken();
                    break;
                case "commands.customExecute":
                    sessionToken = ExecuteCustomRequest.parseFrom(payload).getSessionToken();
                    break;
                default:
                    return null;
            }
            if (sessionToken == null || sessionToken.isEmpty()) {
                return null;
            }
            return userService.validateSession(sessionToken);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Helper ====================

    private PlayerCommandProto toPlayerCommandProto(PlayerCommand cmd) {
        PlayerCommandProto.Builder builder = PlayerCommandProto.newBuilder()
                .setId(cmd.getId())
                .setLikes(cmd.getLikes())
                .setViews(cmd.getViews());

        if (cmd.getTitle() != null) builder.setTitle(cmd.getTitle());
        if (cmd.getDescription() != null) builder.setDescription(cmd.getDescription());
        if (cmd.getCommand() != null) builder.setCommand(cmd.getCommand());
        if (cmd.getCategory() != null) builder.setCategory(cmd.getCategory());
        if (cmd.getUploaderName() != null) builder.setUploaderName(cmd.getUploaderName());
        if (cmd.getUploadTime() != null) builder.setUploadTime(cmd.getUploadTime().toString());
        if (cmd.getReviewStatus() != null) builder.setReviewStatus(cmd.getReviewStatus());
        if (cmd.getReviewNote() != null) builder.setReviewNote(cmd.getReviewNote());
        if (cmd.getReviewTime() != null) builder.setReviewTime(cmd.getReviewTime().toString());
        if (cmd.getLikedUids() != null) builder.addAllLikedUids(cmd.getLikedUids());

        return builder.build();
    }
}
