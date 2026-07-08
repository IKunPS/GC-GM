package com.genshin.gm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandRequest;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.util.SecurityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 割草机服务 - 处理与Grasscutter服务器 / HK4E MUIP服务器的通信
 */
@Service
public class GrasscutterService {
    private static final Logger logger = LoggerFactory.getLogger(GrasscutterService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public GrasscutterService(RestTemplateBuilder builder) {
        AppConfig config = ConfigLoader.getConfig();
        int timeout = Math.max(config.getGrasscutter().getTimeout(), config.getMuip().getTimeout());
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(timeout))
                .setReadTimeout(Duration.ofMillis(timeout))
                .build();
    }

    /**
     * 测试与Grasscutter/OpenCommand或HK4E MUIP服务器的连接
     */
    public OpenCommandResponse ping(String serverUrl) {
        try {
            if (isMuipMode()) {
                return checkMuipStatus();
            }
            OpenCommandRequest request = new OpenCommandRequest("ping");
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("Ping失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("连接失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 获取在线玩家列表
     */
    public OpenCommandResponse getOnlinePlayers(String serverUrl) {
        try {
            if (isMuipMode()) {
                OpenCommandResponse response = new OpenCommandResponse();
                response.setRetcode(501);
                response.setMessage("MUIP模式暂未实现在线玩家列表，请继续使用OpenCommand或配置对应MUIP cmd");
                return response;
            }
            OpenCommandRequest request = new OpenCommandRequest("online");
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("获取在线玩家失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("获取失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 发送验证码到指定玩家
     */
    public OpenCommandResponse sendCode(String serverUrl, int uid) {
        try {
            if (isMuipMode()) {
                OpenCommandResponse response = new OpenCommandResponse();
                response.setRetcode(501);
                response.setMessage("MUIP模式不支持OpenCommand验证码流程");
                return response;
            }
            OpenCommandRequest request = new OpenCommandRequest("sendCode", uid);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("发送验证码失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("发送失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 验证验证码
     */
    public OpenCommandResponse verifyCode(String serverUrl, String token, int code) {
        try {
            if (isMuipMode()) {
                OpenCommandResponse response = new OpenCommandResponse();
                response.setRetcode(501);
                response.setMessage("MUIP模式不支持OpenCommand验证码流程");
                return response;
            }
            OpenCommandRequest request = new OpenCommandRequest("verify", code);
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("验证失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("验证失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 执行命令（玩家模式）
     */
    public OpenCommandResponse executeCommand(String serverUrl, String token, String command) {
        try {
            if (isMuipMode()) {
                return executeMuipCommand(command, "", "", "");
            }
            OpenCommandRequest request = new OpenCommandRequest("command", command);
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("执行命令失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("执行失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 执行命令（控制台模式）
     * 每条发送到Grasscutter/HK4E MUIP的指令都会写入 all.txt
     *
     * @param serverUrl    Grasscutter服务器URL，MUIP模式下忽略此参数并读取config.json中的muip配置
     * @param consoleToken 控制台token，MUIP模式下忽略此参数
     * @param command      要执行的命令；MUIP模式支持普通GM指令，也支持直接输入 cmd=xxx&k=v 或 muip:cmd=xxx&k=v
     * @param callerIp     调用者IP
     * @param callerUser   调用者账号
     * @param callerUid    调用者UID
     */
    public OpenCommandResponse executeConsoleCommand(String serverUrl, String consoleToken, String command,
                                                      String callerIp, String callerUser, String callerUid) {
        try {
            SecurityLogger.logAction(callerIp, callerUser, callerUid,
                    isMuipMode() ? "MUIP_EXECUTE" : "GC_EXECUTE", command);

            OpenCommandResponse result;
            if (isMuipMode()) {
                result = executeMuipCommand(command, callerIp, callerUser, callerUid);
            } else {
                OpenCommandRequest request = new OpenCommandRequest("command", command);
                request.setToken(consoleToken);
                result = sendRequest(serverUrl, request);
            }

            String resultStr = (result != null && result.getData() != null) ? result.getData().toString() : "";
            int retcode = result != null ? result.getRetcode() : -1;
            SecurityLogger.logAction(callerIp, callerUser, callerUid,
                    isMuipMode() ? "MUIP_RESULT" : "GC_RESULT",
                    "retcode=" + retcode + " | 指令: " + command + " | 结果: " + resultStr);

            return result;
        } catch (Exception e) {
            logger.error("执行控制台命令失败", e);
            SecurityLogger.logAction(callerIp, callerUser, callerUid,
                    isMuipMode() ? "MUIP_ERROR" : "GC_ERROR",
                    "指令执行异常: " + command + " | 错误: " + e.getMessage());
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("执行失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 获取运行模式
     */
    public OpenCommandResponse getRunMode(String serverUrl, String token) {
        try {
            if (isMuipMode()) {
                OpenCommandResponse response = new OpenCommandResponse();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("apiMode", "muip");
                data.put("muipUrl", ConfigLoader.getConfig().getMuip().getApiUrl());
                data.put("muipEnabled", ConfigLoader.getConfig().getMuip().isEnabled());
                response.setRetcode(200);
                response.setMessage("MUIP模式");
                response.setData(data);
                return response;
            }
            OpenCommandRequest request = new OpenCommandRequest("runmode");
            request.setToken(token);
            return sendRequest(serverUrl, request);
        } catch (Exception e) {
            logger.error("获取运行模式失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("获取失败: " + e.getMessage());
            return response;
        }
    }

    private boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        return config.getGrasscutter().isMuipMode() || config.getMuip().isEnabled();
    }

    private OpenCommandResponse checkMuipStatus() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", "1101");
        return sendMuipRequest(params);
    }

    private OpenCommandResponse executeMuipCommand(String command, String callerIp, String callerUser, String callerUid) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        if (isBlank(muip.getSign())) {
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("MUIP sign为空，请在config.json中配置muip.sign");
            return response;
        }

        Map<String, String> params = buildMuipCommandParams(command, muip);
        logger.info("=== 发送 MUIP 请求 ===");
        logger.info("目标URL: {}", muip.getApiUrl());
        logger.info("调用者: ip={} user={} uid={}", callerIp, callerUser, callerUid);
        logger.info("MUIP参数: {}", maskSensitiveParams(params));
        return sendMuipRequest(params);
    }

    private Map<String, String> buildMuipCommandParams(String command, AppConfig.MuipConfig muip) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.startsWith("muip:")) {
            return parseRawMuipQuery(trimmed.substring("muip:".length()));
        }
        if (trimmed.startsWith("cmd=") || trimmed.contains("&cmd=")) {
            return parseRawMuipQuery(trimmed);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", muip.getCommandCmd());
        params.put(muip.getCommandParamName(), command == null ? "" : command);
        if (muip.isAppendRegion() && !isBlank(muip.getRegion())) {
            params.put("region", muip.getRegion());
        }
        if (muip.isAppendTicket()) {
            params.put("ticket", "GC-GM@" + Instant.now().getEpochSecond());
        }
        return params;
    }

    private Map<String, String> parseRawMuipQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            key = urlDecode(key.trim());
            value = urlDecode(value.trim());
            if (!key.isEmpty()) {
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * 按 CokeSR/Hk4e-SDK 的 calMuipSign.py 逻辑实现：
     * 1. 过滤空值参数
     * 2. key=value 字符串排序
     * 3. 拼接 &
     * 4. 末尾拼接 muip.sign
     * 5. SHA-256 hex
     */
    private OpenCommandResponse sendMuipRequest(Map<String, String> params) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        try {
            String sign = calculateMuipSign(params, muip.getSign());
            String query = buildEncodedQuery(params);
            String url = muip.getApiUrl() + "?" + query + "&sign=" + sign;

            logger.info("MUIP请求URL: {}", maskSignInUrl(url));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            String body = response.getBody() == null ? "" : response.getBody().trim();
            logger.info("MUIP HTTP状态码: {}", response.getStatusCode());
            logger.info("MUIP响应: {}", body);
            return parseMuipResponse(body);
        } catch (Exception e) {
            logger.error("MUIP请求失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("MUIP请求失败: " + e.getMessage());
            return response;
        }
    }

    private OpenCommandResponse parseMuipResponse(String body) {
        OpenCommandResponse response = new OpenCommandResponse();
        if (body == null || body.isBlank()) {
            response.setRetcode(500);
            response.setMessage("MUIP响应为空");
            return response;
        }

        try {
            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object retcodeObj = map.get("retcode");
            Object msgObj = map.containsKey("msg") ? map.get("msg") : map.get("message");
            String msg = msgObj == null ? "" : String.valueOf(msgObj);

            int retcode;
            if (retcodeObj instanceof Number) {
                retcode = ((Number) retcodeObj).intValue();
            } else if (retcodeObj != null) {
                retcode = Integer.parseInt(String.valueOf(retcodeObj));
            } else {
                retcode = "succ".equalsIgnoreCase(msg) ? 200 : 500;
            }

            response.setRetcode(retcode);
            response.setMessage(msg.isBlank() ? "MUIP返回成功" : msg);
            response.setData(map);
            return response;
        } catch (Exception e) {
            response.setRetcode(200);
            response.setMessage("MUIP返回非JSON响应");
            response.setData(body);
            return response;
        }
    }

    private String calculateMuipSign(Map<String, String> params, String signKey) {
        ArrayList<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(parts);
        String readySignQuery = String.join("&", parts);
        return sha256Hex(readySignQuery + signKey);
    }

    private String buildEncodedQuery(Map<String, String> params) {
        ArrayList<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = urlEncode(entry.getKey());
            String value = entry.getValue() == null ? "" : urlEncode(entry.getValue());
            parts.add(key + "=" + value);
        }
        return String.join("&", parts);
    }

    private String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256计算失败", e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Map<String, String> maskSensitiveParams(Map<String, String> params) {
        Map<String, String> masked = new LinkedHashMap<>(params);
        if (masked.containsKey("sign")) {
            masked.put("sign", "***");
        }
        return masked;
    }

    private String maskSignInUrl(String url) {
        return url == null ? null : url.replaceAll("([?&]sign=)[^&]+", "$1***");
    }

    /**
     * 发送请求到Grasscutter服务器
     */
    private OpenCommandResponse sendRequest(String serverUrl, OpenCommandRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<OpenCommandRequest> entity = new HttpEntity<>(request, headers);

            logger.info("=== 发送 OpenCommand 请求 ===");
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
                logger.info("=== 收到 OpenCommand 响应 ===");
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
            logger.error("=== OpenCommand 请求失败 ===");
            logger.error("目标URL: {}", serverUrl);
            logger.error("Action: {}", request.getAction());
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());
            logger.error("详细堆栈:", e);
            throw e;
        }
    }
}
