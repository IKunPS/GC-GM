package com.genshin.gm.service;

import com.genshin.gm.model.UserDevice;
import com.genshin.gm.proto.DeviceInfo;
import com.genshin.gm.repository.UserDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 设备指纹服务
 * 每次客户端请求都会调用 recordDevice 落库（无则插入，有则更新最后访问时间与计数）
 */
@Service
public class DeviceService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);

    @Autowired
    private UserDeviceRepository userDeviceRepository;

    /**
     * 记录或更新设备
     *
     * @param info     proto envelope 携带的设备信息
     * @param username 当前登录用户名（未登录可为 null，使用 "__anonymous__" 占位以满足唯一索引）
     * @param ip       客户端 IP
     * @param action   本次请求的 action 名称
     * @return 持久化后的 UserDevice
     */
    @Transactional
    public UserDevice recordDevice(DeviceInfo info, String username, String ip, String action) {
        if (info == null) {
            return null;
        }
        String deviceId = trimOrNull(info.getDeviceId());
        if (deviceId == null) {
            return null;
        }

        String resolvedUsername = (username == null || username.isEmpty()) ? "__anonymous__" : username;

        try {
            Optional<UserDevice> existing =
                    userDeviceRepository.findByUsernameAndDeviceId(resolvedUsername, deviceId);

            UserDevice device = existing.orElseGet(UserDevice::new);
            device.setUsername(resolvedUsername);
            device.setDeviceId(deviceId);
            // 元数据可能在不同版本变化，每次写入最新值
            if (notBlank(info.getDeviceModel())) device.setDeviceModel(info.getDeviceModel());
            if (notBlank(info.getDeviceBrand())) device.setDeviceBrand(info.getDeviceBrand());
            if (notBlank(info.getSystemVersion())) device.setSystemVersion(info.getSystemVersion());
            if (notBlank(info.getAppVersion())) device.setAppVersion(info.getAppVersion());
            device.touch(action, ip);

            return userDeviceRepository.save(device);
        } catch (Exception e) {
            // 设备记录失败不能影响主流程
            logger.warn("记录设备失败 username={} deviceId={}: {}", resolvedUsername, deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * 当用户从匿名登录变为已登录时，把匿名记录合并到该用户名下
     */
    @Transactional
    public void linkAnonymousDeviceToUser(String deviceId, String username) {
        if (deviceId == null || deviceId.isEmpty() || username == null || username.isEmpty()) {
            return;
        }
        try {
            Optional<UserDevice> anon =
                    userDeviceRepository.findByUsernameAndDeviceId("__anonymous__", deviceId);
            if (anon.isEmpty()) {
                return;
            }
            Optional<UserDevice> existing =
                    userDeviceRepository.findByUsernameAndDeviceId(username, deviceId);
            if (existing.isPresent()) {
                // 已有同设备的实名记录，匿名记录删除即可
                userDeviceRepository.delete(anon.get());
            } else {
                // 直接改名复用记录
                UserDevice device = anon.get();
                device.setUsername(username);
                userDeviceRepository.save(device);
            }
        } catch (Exception e) {
            logger.warn("合并匿名设备记录失败 deviceId={} username={}: {}",
                    deviceId, username, e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
