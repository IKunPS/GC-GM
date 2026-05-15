package com.genshin.gm.service;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.OpenCommandResponse;
import com.genshin.gm.model.User;
import com.genshin.gm.model.UserDevice;
import com.genshin.gm.repository.UserDeviceRepository;
import com.genshin.gm.repository.UserRepository;
import com.genshin.gm.util.SecurityLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户服务
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDeviceRepository userDeviceRepository;

    @Autowired
    private GrasscutterService grasscutterService;

    // 存储用户session: sessionToken -> username
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    // 存储session过期时间: sessionToken -> expiryTime
    private final Map<String, LocalDateTime> sessionExpiry = new ConcurrentHashMap<>();

    // Session有效期（小时）
    private static final int SESSION_VALIDITY_HOURS = 24;

    /**
     * 用户注册
     *
     * @param username 用户名
     * @param password 密码
     * @param deviceId 客户端上报的设备 ID（可空：仅 PC 浏览器旧路径会传 null，
     *                 proto 路径必传，缺失会被上层拒绝）
     */
    public Map<String, Object> register(String username, String password, String deviceId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 验证输入
            if (username == null || username.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "用户名不能为空");
                return result;
            }

            if (password == null || password.length() < 6) {
                result.put("success", false);
                result.put("message", "密码至少6个字符");
                return result;
            }

            username = username.trim();

            // 检查用户名是否已存在
            if (userRepository.existsByUsername(username)) {
                result.put("success", false);
                result.put("message", "用户名已存在");
                return result;
            }

            // 一设备一账号：检查 device_id 是否已被其他用户使用
            if (deviceId != null && !deviceId.isEmpty()) {
                final String currentUsername = username;
                boolean deviceTaken = userDeviceRepository.findByDeviceId(deviceId).stream()
                        .anyMatch(d -> d.getUsername() != null
                                && !"__anonymous__".equals(d.getUsername())
                                && !currentUsername.equals(d.getUsername()));
                if (deviceTaken) {
                    result.put("success", false);
                    result.put("message", "此设备已注册过账号，每台设备只能注册一个账号");
                    return result;
                }
            }

            // 加密密码
            String hashedPassword = hashPassword(password);

            // 创建用户
            User user = new User(username, hashedPassword);
            userRepository.save(user);

            // 立即把当前设备绑定到该用户（避免再次注册可绕过设备唯一限制）
            if (deviceId != null && !deviceId.isEmpty()) {
                bindDeviceToUserAtRegister(username, deviceId);
            }

            logger.info("用户注册成功: {} (deviceId={})", username, deviceId);

            // 异步通知 Grasscutter 创建对应账号，不阻塞注册响应
            sendAccountCreateToGc(username);

            result.put("success", true);
            result.put("message", "注册成功");
            result.put("username", username);

        } catch (Exception e) {
            logger.error("注册失败", e);
            result.put("success", false);
            result.put("message", "注册失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 兼容旧调用：无 device_id 的入口
     */
    public Map<String, Object> register(String username, String password) {
        return register(username, password, null);
    }

    /**
     * 注册成功瞬间把当前设备绑定到该用户：
     * - 同设备此前任何匿名记录都改成此用户名
     * - 若不存在则直接新建一条
     */
    private void bindDeviceToUserAtRegister(String username, String deviceId) {
        try {
            List<UserDevice> existing = userDeviceRepository.findByDeviceId(deviceId);
            UserDevice toKeep = null;
            for (UserDevice d : existing) {
                if (username.equals(d.getUsername())) {
                    toKeep = d;
                    break;
                }
            }
            if (toKeep == null) {
                // 优先复用匿名记录
                for (UserDevice d : existing) {
                    if ("__anonymous__".equals(d.getUsername())) {
                        d.setUsername(username);
                        userDeviceRepository.save(d);
                        toKeep = d;
                        break;
                    }
                }
            }
            if (toKeep == null) {
                UserDevice d = new UserDevice();
                d.setUsername(username);
                d.setDeviceId(deviceId);
                d.touch("auth.register", null);
                userDeviceRepository.save(d);
            }
        } catch (Exception e) {
            logger.warn("注册时绑定设备失败 username={} deviceId={}: {}", username, deviceId, e.getMessage());
        }
    }

    /**
     * 注册成功后通知 Grasscutter 创建账号：
     *   account create <username>
     * fire-and-forget：用独立 daemon 线程发送，避免阻塞注册响应；失败不影响注册主流程。
     * 不使用 @Async：同类自调用 Spring AOP 不生效，反而会变成同步阻塞。
     */
    public void sendAccountCreateToGc(String username) {
        Thread t = new Thread(() -> {
            try {
                AppConfig.GrasscutterConfig gc = ConfigLoader.getConfig().getGrasscutter();
                if (gc == null || gc.getConsoleToken() == null || gc.getConsoleToken().isEmpty()) {
                    logger.warn("Grasscutter consoleToken 未配置，跳过 account create");
                    return;
                }
                String command = "account create " + username;
                OpenCommandResponse resp = grasscutterService.executeConsoleCommand(
                        gc.getFullUrl(), gc.getConsoleToken(), command,
                        null, "system", null);
                int retcode = resp != null ? resp.getRetcode() : -1;
                String msg = resp != null && resp.getMessage() != null ? resp.getMessage() : "";
                logger.info("GC account create 完成: username={}, retcode={}, msg={}", username, retcode, msg);
                SecurityLogger.logAction(null, "system", null, "GC_ACCOUNT_CREATE",
                        "username=" + username + " retcode=" + retcode + " msg=" + msg);
            } catch (Exception e) {
                logger.error("GC account create 异常 username={}", username, e);
            }
        }, "gc-account-create-" + username);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 用户登录
     */
    public Map<String, Object> login(String username, String password) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (username == null || username.trim().isEmpty() || password == null) {
                result.put("success", false);
                result.put("message", "用户名或密码不能为空");
                return result;
            }

            username = username.trim();

            // 查找用户
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (!userOpt.isPresent()) {
                result.put("success", false);
                result.put("message", "用户名或密码错误");
                return result;
            }

            User user = userOpt.get();

            // 验证密码
            String hashedPassword = hashPassword(password);
            if (!hashedPassword.equals(user.getPassword())) {
                result.put("success", false);
                result.put("message", "用户名或密码错误");
                return result;
            }

            // 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            // 创建session
            String sessionToken = generateSessionToken();
            sessions.put(sessionToken, username);
            sessionExpiry.put(sessionToken, LocalDateTime.now().plusHours(SESSION_VALIDITY_HOURS));

            logger.info("用户登录成功: {}", username);

            result.put("success", true);
            result.put("message", "登录成功");
            result.put("sessionToken", sessionToken);
            result.put("username", username);
            result.put("verifiedUids", user.getVerifiedUids());

        } catch (Exception e) {
            logger.error("登录失败", e);
            result.put("success", false);
            result.put("message", "登录失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 验证session
     */
    public String validateSession(String sessionToken) {
        if (sessionToken == null || !sessions.containsKey(sessionToken)) {
            return null;
        }

        // 检查是否过期
        LocalDateTime expiry = sessionExpiry.get(sessionToken);
        if (expiry == null || LocalDateTime.now().isAfter(expiry)) {
            sessions.remove(sessionToken);
            sessionExpiry.remove(sessionToken);
            return null;
        }

        return sessions.get(sessionToken);
    }

    /**
     * 登出
     */
    public void logout(String sessionToken) {
        if (sessionToken != null) {
            sessions.remove(sessionToken);
            sessionExpiry.remove(sessionToken);
        }
    }

    /**
     * 获取用户信息
     */
    public Map<String, Object> getUserInfo(String sessionToken) {
        Map<String, Object> result = new HashMap<>();

        String username = validateSession(sessionToken);
        if (username == null) {
            result.put("success", false);
            result.put("message", "未登录或session已过期");
            return result;
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (!userOpt.isPresent()) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }

        User user = userOpt.get();
        result.put("success", true);
        result.put("username", user.getUsername());
        result.put("verifiedUids", user.getVerifiedUids());
        result.put("createdAt", user.getCreatedAt().toString());

        return result;
    }

    /**
     * 添加已验证的UID
     */
    public boolean addVerifiedUid(String username, String uid) {
        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (!userOpt.isPresent()) {
                return false;
            }

            User user = userOpt.get();
            user.addVerifiedUid(uid);
            userRepository.save(user);

            logger.info("用户 {} 添加已验证UID: {}", username, uid);
            return true;

        } catch (Exception e) {
            logger.error("添加已验证UID失败", e);
            return false;
        }
    }

    /**
     * 获取用户绑定的UID列表（字符串形式，用于日志）
     */
    public String getVerifiedUidsString(String username) {
        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (!userOpt.isPresent()) {
                return "用户不存在";
            }
            Set<String> uids = userOpt.get().getVerifiedUids();
            if (uids == null || uids.isEmpty()) {
                return "无绑定UID";
            }
            return String.join(", ", uids);
        } catch (Exception e) {
            return "获取失败";
        }
    }

    /**
     * 检查用户是否已验证某个UID
     */
    public boolean isUidVerified(String username, String uid) {
        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (!userOpt.isPresent()) {
                return false;
            }

            return userOpt.get().isUidVerified(uid);

        } catch (Exception e) {
            logger.error("检查UID验证状态失败", e);
            return false;
        }
    }

    /**
     * 移除已验证的UID
     */
    public boolean removeVerifiedUid(String username, String uid) {
        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (!userOpt.isPresent()) {
                return false;
            }

            User user = userOpt.get();
            user.removeVerifiedUid(uid);
            userRepository.save(user);

            logger.info("用户 {} 移除已验证UID: {}", username, uid);
            return true;

        } catch (Exception e) {
            logger.error("移除已验证UID失败", e);
            return false;
        }
    }

    /**
     * MD5加密密码
     */
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }

    /**
     * 生成session token
     */
    private String generateSessionToken() {
        return UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
    }
}
