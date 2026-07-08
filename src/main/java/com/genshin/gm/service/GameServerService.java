package com.genshin.gm.service;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.util.SecurityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏服务统一入口。
 *
 * 根据 config.json 的 launchMode 决定连接模式：
 * - grasscutter: 连接 Grasscutter OpenCommand
 * - muip: 连接 HK4E MUIP
 *
 * 业务层优先使用这个服务，不直接关心底层协议。
 */
@Primary
@Service
public class GameServerService {
    private static final Logger logger = LoggerFactory.getLogger(GameServerService.class);

    private final GrasscutterService grasscutterService;
    private final MuipService muipService;

    @Autowired
    public GameServerService(GrasscutterService grasscutterService, MuipService muipService) {
        this.grasscutterService = grasscutterService;
        this.muipService = muipService;
    }

    public OpenCommandResponse ping(String serverUrl) {
        if (isMuipMode()) {
            return muipService.ping();
        }
        return grasscutterService.ping(serverUrl);
    }

    public OpenCommandResponse getOnlinePlayers(String serverUrl) {
        if (isMuipMode()) {
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(501);
            response.setMessage("MUIP模式暂未实现在线玩家列表，请使用具体MUIP cmd扩展");
            return response;
        }
        return grasscutterService.getOnlinePlayers(serverUrl);
    }

    public OpenCommandResponse sendCode(String serverUrl, int uid) {
        if (isMuipMode()) {
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(501);
            response.setMessage("MUIP模式不支持OpenCommand验证码流程");
            return response;
        }
        return grasscutterService.sendCode(serverUrl, uid);
    }

    public OpenCommandResponse verifyCode(String serverUrl, String token, int code) {
        if (isMuipMode()) {
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(501);
            response.setMessage("MUIP模式不支持OpenCommand验证码流程");
            return response;
        }
        return grasscutterService.verifyCode(serverUrl, token, code);
    }

    public OpenCommandResponse executeCommand(String serverUrl, String token, String command) {
        if (isMuipMode()) {
            return muipService.executeCommand(command);
        }
        return grasscutterService.executeCommand(serverUrl, token, command);
    }

    public OpenCommandResponse executeConsoleCommand(String serverUrl, String consoleToken, String command,
                                                      String callerIp, String callerUser, String callerUid) {
        if (isMuipMode()) {
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "MUIP_EXECUTE", command);
            OpenCommandResponse result = muipService.executeCommand(command);
            String resultStr = (result != null && result.getData() != null) ? result.getData().toString() : "";
            int retcode = result != null ? result.getRetcode() : -1;
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "MUIP_RESULT",
                    "retcode=" + retcode + " | 指令: " + command + " | 结果: " + resultStr);
            return result;
        }
        return grasscutterService.executeConsoleCommand(serverUrl, consoleToken, command, callerIp, callerUser, callerUid);
    }

    public OpenCommandResponse getRunMode(String serverUrl, String token) {
        if (isMuipMode()) {
            OpenCommandResponse response = new OpenCommandResponse();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("launchMode", "muip");
            data.put("muipUrl", ConfigLoader.getConfig().getMuip().getApiUrl());
            data.put("muipEnabled", ConfigLoader.getConfig().getMuip().isEnabled());
            data.put("commandCmd", ConfigLoader.getConfig().getMuip().getCommandCmd());
            data.put("commandParamName", ConfigLoader.getConfig().getMuip().getCommandParamName());
            response.setRetcode(200);
            response.setMessage("MUIP模式");
            response.setData(data);
            return response;
        }
        return grasscutterService.getRunMode(serverUrl, token);
    }

    private boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        boolean muipMode = "muip".equalsIgnoreCase(config.getLaunchMode())
                || config.getGrasscutter().isMuipMode()
                || config.getMuip().isEnabled();
        logger.debug("当前游戏服务模式: {}", muipMode ? "muip" : "grasscutter");
        return muipMode;
    }
}
