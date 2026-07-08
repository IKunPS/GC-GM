package com.genshin.gm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
 * HK4E MUIP 服务。
 *
 * 逻辑参考 CokeSR/Hk4e-SDK:
 * - GET http(s)://address:port/api?参数&sign=sha256(排序后的非空参数 + signKey)
 * - 默认使用 cmd=1101 检查 MUIP 连通性/签名
 * - 普通 GM 指令会包装为可配置 commandCmd + commandParamName
 */
@Service
public class MuipService {
    private static final Logger logger = LoggerFactory.getLogger(MuipService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public MuipService(RestTemplateBuilder builder) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(muip.getTimeout()))
                .setReadTimeout(Duration.ofMillis(muip.getTimeout()))
                .build();
    }

    public OpenCommandResponse ping() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", "1101");
        return sendRawMuip(params);
    }

    public OpenCommandResponse executeCommand(String command) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        if (isBlank(muip.getSign())) {
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("MUIP sign为空，请在config.json中配置muip.sign");
            return response;
        }
        return sendRawMuip(buildCommandParams(command, muip));
    }

    /**
     * 直接发送 MUIP 参数。
     * 例如：cmd=1101 或 cmd=1005&uid=xxx&title=xxx
     */
    public OpenCommandResponse executeRawQuery(String query) {
        return sendRawMuip(parseRawMuipQuery(query));
    }

    public OpenCommandResponse sendRawMuip(Map<String, String> params) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        try {
            String sign = calculateMuipSign(params, muip.getSign());
            String query = buildEncodedQuery(params);
            String url = muip.getApiUrl() + "?" + query + "&sign=" + sign;

            logger.info("=== 发送 HK4E MUIP 请求 ===");
            logger.info("MUIP URL: {}", maskSignInUrl(url));
            logger.info("MUIP Params: {}", params);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            String body = response.getBody() == null ? "" : response.getBody().trim();

            logger.info("MUIP HTTP状态码: {}", response.getStatusCode());
            logger.info("MUIP 响应: {}", body);
            return parseMuipResponse(body);
        } catch (Exception e) {
            logger.error("MUIP请求失败", e);
            OpenCommandResponse response = new OpenCommandResponse();
            response.setRetcode(500);
            response.setMessage("MUIP请求失败: " + e.getMessage());
            return response;
        }
    }

    private Map<String, String> buildCommandParams(String command, AppConfig.MuipConfig muip) {
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
        for (String pair : query.split("&")) {
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

    private String calculateMuipSign(Map<String, String> params, String signKey) {
        ArrayList<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(parts);
        return sha256Hex(String.join("&", parts) + signKey);
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

    private String maskSignInUrl(String url) {
        return url == null ? null : url.replaceAll("([?&]sign=)[^&]+", "$1***");
    }
}
