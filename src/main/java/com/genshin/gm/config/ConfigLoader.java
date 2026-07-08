package com.genshin.gm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 配置加载器 - 从外部config.json读取配置
 * 如果config.json不存在，则自动生成默认配置文件
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String CONFIG_FILE = "config.json";
    private static AppConfig appConfig;

    /**
     * 生成默认配置文件
     */
    private static AppConfig generateDefaultConfig(File configFile) {
        AppConfig config = new AppConfig();
        config.setLaunchMode("grasscutter");
        config.setFrontend(new AppConfig.FrontendConfig());
        config.setGrasscutter(new AppConfig.GrasscutterConfig());
        config.setMuip(new AppConfig.MuipConfig());
        config.setMysql(new AppConfig.MySQLConfig());
        config.setApp(new AppConfig.AppDownloadConfig());

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            objectMapper.writeValue(configFile, config);
            logger.info("已生成默认配置文件: {}，请根据实际环境修改后重启应用", configFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("生成默认配置文件失败", e);
        }

        return config;
    }

    /**
     * 加载配置文件
     */
    public static AppConfig loadConfig() {
        if (appConfig != null) {
            return appConfig;
        }

        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            logger.warn("配置文件 {} 不存在，正在生成默认配置文件...", CONFIG_FILE);
            appConfig = generateDefaultConfig(configFile);
            return appConfig;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            appConfig = objectMapper.readValue(configFile, AppConfig.class);

            // 如果配置文件中缺少某些配置段，补充并回写
            boolean needRewrite = false;

            if (appConfig.getLaunchMode() == null || appConfig.getLaunchMode().isBlank()) {
                appConfig.setLaunchMode("grasscutter");
                needRewrite = true;
            }
            if (appConfig.getFrontend() == null) {
                appConfig.setFrontend(new AppConfig.FrontendConfig());
                needRewrite = true;
            }
            if (appConfig.getGrasscutter() == null) {
                appConfig.setGrasscutter(new AppConfig.GrasscutterConfig());
                needRewrite = true;
            }
            if (appConfig.getMuip() == null) {
                appConfig.setMuip(new AppConfig.MuipConfig());
                needRewrite = true;
            }
            if (appConfig.getMysql() == null) {
                appConfig.setMysql(new AppConfig.MySQLConfig());
                needRewrite = true;
            }
            if (appConfig.getApp() == null) {
                appConfig.setApp(new AppConfig.AppDownloadConfig());
                needRewrite = true;
            }

            if (needRewrite) {
                logger.warn("配置文件缺少部分配置，已补充默认值并回写到 {}", CONFIG_FILE);
                ObjectMapper writer = new ObjectMapper();
                writer.enable(SerializationFeature.INDENT_OUTPUT);
                writer.writeValue(configFile, appConfig);
            }

            logger.info("成功加载配置文件: {}", CONFIG_FILE);
            logger.info("启动连接模式: {}", appConfig.getLaunchMode());
            logger.info("前端地址: {}:{}", appConfig.getFrontend().getHost(), appConfig.getFrontend().getPort());
            logger.info("Grasscutter API: {}", appConfig.getGrasscutter().getFullUrl());
            logger.info("Grasscutter 超时: {}ms", appConfig.getGrasscutter().getTimeout());
            logger.info("MUIP API: {} enabled={}", appConfig.getMuip().getApiUrl(), appConfig.getMuip().isEnabled());
            logger.info("MySQL连接: {}:{}/{}",
                    appConfig.getMysql().getHost(),
                    appConfig.getMysql().getPort(),
                    appConfig.getMysql().getDatabase());

            return appConfig;
        } catch (IOException e) {
            logger.error("加载配置文件失败，正在重新生成默认配置文件...", e);
            appConfig = generateDefaultConfig(configFile);
            return appConfig;
        }
    }

    /**
     * 获取配置实例
     */
    public static AppConfig getConfig() {
        if (appConfig == null) {
            return loadConfig();
        }
        return appConfig;
    }
}
