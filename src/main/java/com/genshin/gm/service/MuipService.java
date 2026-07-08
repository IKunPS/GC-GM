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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HK4E MUIP HTTP 客户端。
 *
 * 只负责 MUIP 参数、签名与 HTTP 请求；
 * HK4E 指令文本统一由 Hk4eCommandService 生成/转换。
 */
@Service
public class MuipService {
    private static final Logger logger = LoggerFactory.getLogger(MuipService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Hk4eCommandService hk4eCommandService;

    @Autowired
    public MuipService(RestTemplateBuilder builder, Hk4eCommandService hk4eCommandService) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(muip.getTimeout()))
                .setReadTimeout(Duration.ofMillis(muip.getTimeout()))
                .build();
        this.hk4eCommandService = hk4eCommandService;
    }

    public OpenCommandResponse ping() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", "1101");
        return sendRawMuip(params);
    }

    public OpenCommandResponse executeCommand(String command) {
        return executeCommand(command, null);
    }

    public OpenCommandResponse executeCommand(String command, String uid) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        if (isBlank(muip.getSign())) {
            return error(500, "MUIP sign为空，请在config.json中配置muip.sign");
        }
        return sendRawMuip(buildConsoleCommandParams(command, uid, muip));
    }

    public OpenCommandResponse executeRawQuery(String query) {
        return sendRawMuip(parseRawMuipQuery(query));
    }

    public OpenCommandResponse sendRawMuip(Map<String, String> params) {
        AppConfig.MuipConfig muip = ConfigLoader.getConfig().getMuip();
        try {
            String query = buildViaGenshinStyleQuery(params, muip.getSign());
            String url = muip.getApiUrl() + "?" + query;

            logger.info("=== 发送 HK4E MUIP 请求 ===");
            logger.info("MUIP URL: {}", maskSignInUrl(url));
            logger.info("MUIP Params: {}", maskSensitiveParams(params));

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            String body = response.getBody() == null ? "" : response.getBody().trim();

            logger.info("MUIP HTTP状态码: {}", response.getStatusCode());
            logger.info("MUIP 响应: {}", body);
            return parseMuipResponse(body);
        } catch (Exception e) {
            logger.error("MUIP请求失败", e);
            return error(500, "MUIP请求失败: " + e.getMessage());
        }
    }

    private Map<String, String> buildConsoleCommandParams(String command, String uid, AppConfig.MuipConfig muip) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.startsWith("muip:")) {
            return parseRawMuipQuery(trimmed.substring("muip:".length()));
        }
        if (trimmed.startsWith("cmd=") || trimmed.contains("&cmd=")) {
            return parseRawMuipQuery(trimmed);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", muip.getCommandCmd());
        params.put("uid", resolveUid(uid, muip));
        params.put("msg", hk4eCommandService.normalizeCommand(command == null ? "" : command));
        params.put("region", muip.getRegion());
        params.put("ticket", randomTicketHex());
        return params;
    }

    private String resolveUid(String uid, AppConfig.MuipConfig muip) {
        if (!isBlank(uid)) {
            return uid.trim();
        }
        return String.valueOf(muip.getDefaultUid());
    }

    private String buildViaGenshinStyleQuery(Map<String, String> params, String signKey) {
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            values.add(entry.getKey() + "=" + entry.getValue());
        }
        if (!isBlank(signKey)) {
            ArrayList<String> sortedForSign = new ArrayList<>(values);
            Collections.sort(sortedForSign);
            values.add("sign=" + sha256Hex(String.join("&", sortedForSign) + signKey));
        }
        return buildEncodedQuery(parseKeyValueList(values));
    }

    private Map<String, String> parseKeyValueList(ArrayList<String> values) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String value : values) {
            int idx = value.indexOf('=');
            if (idx < 0) {
                continue;
            }
            params.put(value.substring(0, idx), value.substring(idx + 1));
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
                retcode = "succ".equalsIgnoreCase(msg) ? 0 : 500;
            }

            response.setRetcode(retcode == 0 ? 200 : retcode);
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

    private String randomTicketHex() {
        byte[] ticket = new byte[16];
        RANDOM.nextBytes(ticket);
        StringBuilder hex = new StringBuilder(ticket.length * 2);
        for (byte b : ticket) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) {
                hex.append('0');
            }
            hex.append(h);
        }
        return hex.toString();
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

    private OpenCommandResponse error(int retcode, String message) {
        OpenCommandResponse response = new OpenCommandResponse();
        response.setRetcode(retcode);
        response.setMessage(message);
        return response;
    }
}
