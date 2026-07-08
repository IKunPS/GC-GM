package com.genshin.gm.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 应用配置类
 */
public class AppConfig {
    /**
     * 游戏服务连接模式：
     * - grasscutter: 连接 Grasscutter OpenCommand
     * - muip: 连接 HK4E MUIP
     */
    private String launchMode = "grasscutter";
    private FrontendConfig frontend;
    private GrasscutterConfig grasscutter;
    private MuipConfig muip;
    private MySQLConfig mysql;
    private AppDownloadConfig app;

    public String getLaunchMode() { return launchMode; }
    public void setLaunchMode(String launchMode) { this.launchMode = launchMode; }
    public FrontendConfig getFrontend() { return frontend; }
    public void setFrontend(FrontendConfig frontend) { this.frontend = frontend; }
    public GrasscutterConfig getGrasscutter() { return grasscutter; }
    public void setGrasscutter(GrasscutterConfig grasscutter) { this.grasscutter = grasscutter; }
    public MuipConfig getMuip() { return muip != null ? muip : new MuipConfig(); }
    public void setMuip(MuipConfig muip) { this.muip = muip; }
    public MySQLConfig getMysql() { return mysql; }
    public void setMysql(MySQLConfig mysql) { this.mysql = mysql; }
    public AppDownloadConfig getApp() { return app != null ? app : new AppDownloadConfig(); }
    public void setApp(AppDownloadConfig app) { this.app = app; }

    public static class FrontendConfig {
        private String host = "localhost";
        private int port = 8080;
        private boolean autoOpen = true;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isAutoOpen() { return autoOpen; }
        public void setAutoOpen(boolean autoOpen) { this.autoOpen = autoOpen; }

        @JsonIgnore
        public String getUrl() { return "http://" + host + ":" + port; }
    }

    public static class GrasscutterConfig {
        /**
         * 兼容旧配置。新配置优先使用 AppConfig.launchMode。
         */
        private String apiMode = "opencommand";
        private String serverUrl = "http://127.0.0.1:443";
        private String apiPath = "/opencommand/api";
        private String consoleToken = "";
        private String adminToken = "";
        private int timeout = 10000;

        public String getApiMode() { return apiMode; }
        public void setApiMode(String apiMode) { this.apiMode = apiMode; }
        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
        public String getApiPath() { return apiPath; }
        public void setApiPath(String apiPath) { this.apiPath = apiPath; }
        public String getConsoleToken() { return consoleToken; }
        public void setConsoleToken(String consoleToken) { this.consoleToken = consoleToken; }
        public String getAdminToken() { return adminToken; }
        public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        @JsonIgnore
        public String getFullUrl() { return serverUrl + apiPath; }

        @JsonIgnore
        public boolean isMuipMode() { return "muip".equalsIgnoreCase(apiMode); }
    }

    public static class MuipConfig {
        private boolean enabled = false;
        private boolean ssl = false;
        private String address = "127.0.0.1";
        private int port = 21041;
        private String region = "cn_gf01";
        private String sign = "";
        private int timeout = 10000;
        /**
         * MUIP 执行 GM 指令的 cmd，不同 HK4E/GIO 端可能不同。
         */
        private String commandCmd = "1116";
        /**
         * GM 指令文本参数名，不同 HK4E/GIO 端可能是 command/cmdline/msg 等。
         */
        private String commandParamName = "command";
        private boolean appendRegion = true;
        private boolean appendTicket = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getSign() { return sign; }
        public void setSign(String sign) { this.sign = sign; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
        public String getCommandCmd() { return commandCmd; }
        public void setCommandCmd(String commandCmd) { this.commandCmd = commandCmd; }
        public String getCommandParamName() { return commandParamName; }
        public void setCommandParamName(String commandParamName) { this.commandParamName = commandParamName; }
        public boolean isAppendRegion() { return appendRegion; }
        public void setAppendRegion(boolean appendRegion) { this.appendRegion = appendRegion; }
        public boolean isAppendTicket() { return appendTicket; }
        public void setAppendTicket(boolean appendTicket) { this.appendTicket = appendTicket; }

        @JsonIgnore
        public String getBaseUrl() { return (ssl ? "https://" : "http://") + address + ":" + port; }

        @JsonIgnore
        public String getApiUrl() { return getBaseUrl() + "/api"; }
    }

    public static class AppDownloadConfig {
        private String downloadUrl = "/api/resource/download/app-release.apk";
        private String version = "1.0.0";
        private String minAndroid = "Android 8.0+ (API 26)";

        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getMinAndroid() { return minAndroid; }
        public void setMinAndroid(String minAndroid) { this.minAndroid = minAndroid; }
    }

    public static class MySQLConfig {
        private String host = "localhost";
        private int port = 3306;
        private String database = "genshin_gm";
        private String username = "root";
        private String password = "";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        @JsonIgnore
        public String getJdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&useUnicode=true";
        }
    }
}
