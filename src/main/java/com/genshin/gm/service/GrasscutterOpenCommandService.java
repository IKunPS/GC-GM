package com.genshin.gm.service;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandRequest;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.util.SecurityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Grasscutter OpenCommand 客户端。
 *
 * 这里只负责 Grasscutter / OpenCommand 的 POST JSON 协议。
 */
@Service
public class GrasscutterOpenCommandService {
    private static final Logger logger = LoggerFactory.getLogger(GrasscutterOpenCommandService.class);
    private final RestTemplate restTemplate;

    @Autowired
    public GrasscutterOpenCommandService(RestTemplateBuilder builder) {
        AppConfig.GrasscutterConfig config = ConfigLoader.getConfig().getGrasscutter();
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(config.getTimeout()))
                .setReadTimeout(Duration.ofMillis(config.getTimeout()))
                .build();
    }

    public OpenCommandResponse ping(String serverUrl) {
        try {
            return sendRequest(serverUrl, new OpenCommandRequest("ping"));
        } catch (Exception e) {
            logger.error("Ping失败", e);
            return error(500, "连接失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse getOnlinePlayers(String serverUrl) {
        try {
            return sendRequest(serverUrl, new OpenCommandRequest("online"));
        } catch (Exception e) {
            logger.error("获取在线玩家失败", e);
            return error(500, "获取失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse sendCode(String serverUrl, int uid) {
        try {
            return sendRequest(serverUrl, new OpenCommandRequest("sendCode", uid));
        } catch (Exception e) {
            logger.error("发送验证码失败", e);
            return error(500, "发送失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse verifyCode(String serverUrl, String token, int code) {
        try {
            OpenCommandRequest request = new OpenCommandRequest("verify", code);
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("验证失败", e);
            return error(500, "验证失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse executeCommand(String serverUrl, String token, String command) {
        try {
            OpenCommandRequest request = new OpenCommandRequest("command", command);
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("执行命令失败", e);
            return error(500, "执行失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse executeConsoleCommand(String serverUrl, String consoleToken, String command,
                                                      String callerIp, String callerUser, String callerUid) {
        try {
            OpenCommandRequest request = new OpenCommandRequest("command", command);
            request.setToken(consoleToken);

            SecurityLogger.logAction(callerIp, callerUser, callerUid, "GC_EXECUTE", command);
            OpenCommandResponse result = sendRequest(serverUrl, request);

            String resultStr = (result != null && result.getData() != null) ? result.getData().toString() : "";
            int retcode = result != null ? result.getRetcode() : -1;
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "GC_RESULT",
                    "retcode=" + retcode + " | 指令: " + command + " | 结果: " + resultStr);

            return result;
        } catch (Exception e) {
            logger.error("执行控制台命令失败", e);
            SecurityLogger.logAction(callerIp, callerUser, callerUid, "GC_ERROR",
                    "指令执行异常: " + command + " | 错误: " + e.getMessage());
            return error(500, "执行失败: " + e.getMessage());
        }
    }

    public OpenCommandResponse getRunMode(String serverUrl, String token) {
        try {
            OpenCommandRequest request = new OpenCommandRequest("runmode");
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("获取运行模式失败", e);
            return error(500, "获取失败: " + e.getMessage());
        }
    }

    private OpenCommandResponse sendRequest(String serverUrl, OpenCommandRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<OpenCommandRequest> entity = new HttpEntity<>(request, headers);

            logger.info("=== 发送 Grasscutter OpenCommand 请求 ===");
            logger.info("目标URL: {}", serverUrl);
            logger.info("Action: {}", request.getAction());
            logger.info("Token: {}", request.getToken() != null && !request.getToken().isEmpty() ? "已设置" : "未设置");
            logger.info("Data: {}", request.getData());

            ResponseEntity<OpenCommandResponse> response = restTemplate.exchange(
                    serverUrl,
                    HttpMethod.POST,
                    entity,
                    OpenCommandResponse.class
            );

            OpenCommandResponse result = response.getBody();
            if (result != null) {
                logger.info("=== 收到 Grasscutter OpenCommand 响应 ===");
                logger.info("HTTP状态码: {}", response.getStatusCode());
                logger.info("Retcode: {}", result.getRetcode());
                logger.info("Message: {}", result.getMessage());
                logger.info("Data: {}", result.getData());
                logger.info("isSuccess: {}", result.isSuccess());
            } else {
                logger.warn("响应体为空，HTTP状态码: {}", response.getStatusCode());
            }

            return result;
        } catch (RestClientException e) {
            logger.error("=== Grasscutter OpenCommand 请求失败 ===");
            logger.error("目标URL: {}", serverUrl);
            logger.error("Action: {}", request.getAction());
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());
            logger.error("详细堆栈:", e);
            throw e;
        }
    }

    private OpenCommandResponse error(int retcode, String message) {
        OpenCommandResponse response = new OpenCommandResponse();
        response.setRetcode(retcode);
        response.setMessage(message);
        return response;
    }
}
