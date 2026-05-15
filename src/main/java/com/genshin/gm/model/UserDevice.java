package com.genshin.gm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 设备记录实体
 * 保存客户端上报的设备指纹，用于风控、用户数据关联与多端同步
 */
@Entity
@Table(
        name = "user_devices",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_username_device",
                columnNames = {"username", "device_id"}
        ),
        indexes = {
                @Index(name = "idx_device_id", columnList = "device_id"),
                @Index(name = "idx_username", columnList = "username")
        }
)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "device_model", length = 64)
    private String deviceModel;

    @Column(name = "device_brand", length = 64)
    private String deviceBrand;

    @Column(name = "system_version", length = 32)
    private String systemVersion;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_action", length = 64)
    private String lastAction;

    @Column(name = "request_count")
    private Long requestCount;

    public UserDevice() {
        LocalDateTime now = LocalDateTime.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.requestCount = 0L;
    }

    public void touch(String action, String ipAddress) {
        this.lastSeenAt = LocalDateTime.now();
        this.lastAction = action;
        this.ipAddress = ipAddress;
        this.requestCount = (this.requestCount == null ? 0L : this.requestCount) + 1;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getDeviceBrand() {
        return deviceBrand;
    }

    public void setDeviceBrand(String deviceBrand) {
        this.deviceBrand = deviceBrand;
    }

    public String getSystemVersion() {
        return systemVersion;
    }

    public void setSystemVersion(String systemVersion) {
        this.systemVersion = systemVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(LocalDateTime firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public Long getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(Long requestCount) {
        this.requestCount = requestCount;
    }
}
