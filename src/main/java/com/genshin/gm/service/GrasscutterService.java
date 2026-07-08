package com.genshin.gm.service;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.util.SecurityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容旧代码的游戏服务门面。
 *
 * 原项目的控制器大概率已经注入 GrasscutterService，
 * 所以保留这个类名作为统一入口，但实际实现已经拆开：
 * - GrasscutterOpenCommandService: Grasscutter POST JSON / OpenCommand
 * - MuipService: HK4E MUIP GET /api + SHA256签名
 */
@Service
public class GrasscutterService {
    private static final Logger logger = LoggerFactory.getLogger(GrasscutterService.class);

    private final GrasscutterOpenCommandService openCommandService;
    private final MuipService muipService;

    @Autowired
    public GrasscutterService(GrasscutterOpenCommandService openCommandService, MuipService muipService) {
        this.openCommandService = openCommandService;
        this.muipService = muipService;
    }

    public OpenCommandResponse ping(String serverUrl) {
        if (isMuipMode()) {
            return muipService.ping();
        }
        return openCommandService.ping(serverUrl);
    }

    public OpenCommandResponse getOnlinePlayers(String serverUrl) {
        if (isMuipMode()) {
            return unsupportedInMuip("MUIP模式暂未实现在线玩家列表，请使用具体MUIP cmd扩展");
        }
        return openCommandService.getOnlinePlayers(serverUrl);
    }

    public OpenCommandResponse sendCode(String serverUrl, int uid) {
        if (isMuipMode()) {
            return unsupportedInMuip("MUIP模式不支持OpenCommand验证码流程");
        }
        return openCommandService.sendCode(serverUrl, uid);
    }

    public OpenCommandResponse verifyCode(String serverUrl, String token, int code) {
        if (isMuipMode()) {
            return unsupportedInMuip("MUIP模式不支持OpenCommand验证码流程");
        }
        return openCommandService.verifyCode(serverUrl, token, code);
    }

    public OpenCommandResponse executeCommand(String serverUrl, String token, String command) {
        if (isMuipMode()) {
            return muipService.executeCommand(command);
        }
        return openCommandService.executeCommand(serverUrl, token, command);
    }

    public OpenCommandResponse executeConsoleCommand(String serverUrl, String consoleToken, String command,
                                                      String callerIp, String callerUser, String callerUid) {
        if (isMuipMode()) {
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "MUIP_EXECUTE", command);
            OpenCommandResponse result = muipService.executeCommand(command, callerUid);
            String resultStr = (result != null && result.getData() != null) ? result.getData().toString() : "";
            int retcode = result != null ? result.getRetcode() : -1;
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "MUIP_RESULT",
                    "retcode=" + retcode + " | 指令: " + command + " | 结果: " + resultStr);
            return result;
        }
        return openCommandService.executeConsoleCommand(serverUrl, consoleToken, command, callerIp, callerUser, callerUid);
    }

    public OpenCommandResponse getRunMode(String serverUrl, String token) {
        if (isMuipMode()) {
            OpenCommandResponse response = new OpenCommandResponse();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("launchMode", "muip");
            data.put("muipUrl", ConfigLoader.getConfig().getMuip().getApiUrl());
            data.put("muipEnabled", ConfigLoader.getConfig().getMuip().isEnabled());
            data.put("commandCmd", ConfigLoader.getConfig().getMuip().getCommandCmd());
            data.put("commandParamName", "msg");
            data.put("defaultUid", ConfigLoader.getConfig().getMuip().getDefaultUid());
            response.setRetcode(200);
            response.setMessage("MUIP模式");
            response.setData(data);
            return response;
        }
        return openCommandService.getRunMode(serverUrl, token);
    }

    private boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        boolean muipMode = "muip".equalsIgnoreCase(config.getLaunchMode())
                || config.getGrasscutter().isMuipMode()
                || config.getMuip().isEnabled();
        logger.debug("当前游戏服务连接模式: {}", muipMode ? "muip" : "grasscutter");
        return muipMode;
    }

    private OpenCommandResponse unsupportedInMuip(String message) {
        OpenCommandResponse response = new OpenCommandResponse();
        response.setRetcode(501);
        response.setMessage(message);
        return response;
    }
}
