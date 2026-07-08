package com.genshin.gm.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HK4E/GIO 专用 GM 指令生成与转换服务。
 *
 * 该类只负责 HK4E 指令文本，不负责 MUIP HTTP、签名、UID、region、ticket。
 * MUIPService 负责把本类生成的指令作为 msg 参数发送给 HK4E MUIP。
 */
@Service
public class Hk4eCommandService {

    private final Map<String, String> aliases = new LinkedHashMap<>();

    public Hk4eCommandService() {
        // 用户提供的 HK4E 指令表
        aliases.put("get-all-items", "item add all 999");
        aliases.put("clear-bag", "item clear");
        aliases.put("home-max-level", "home level 10");
        aliases.put("break-6", "break 6");
        aliases.put("character-max-level", "level 10");
        aliases.put("normal-attack-max", "skill 1 10");
        aliases.put("skill-e-max", "skill 2 10");
        aliases.put("skill-q-max", "skill 3 10");
        aliases.put("kill-self", "kill self");
        aliases.put("kill-all-monsters", "kill monster all");
        aliases.put("avatar-invincible-on", "wudi global avatar on");
        aliases.put("avatar-invincible-off", "wudi global avatar off");
        aliases.put("monster-invincible-on", "wudi global monster on");
        aliases.put("monster-invincible-off", "wudi global monster off");
        aliases.put("unlock-all-constellation", "talent unlock all");
        aliases.put("unlock-world-points", "point 3 all");
        aliases.put("unlock-enkanomiya-points", "point 5 all");
        aliases.put("unlock-chasm-points", "point 6 all");
        aliases.put("infinite-energy-on", "energy infinite on");
        aliases.put("infinite-energy-off", "energy infinite off");
        aliases.put("infinite-stamina-on", "stamina infinite on");
        aliases.put("infinite-stamina-off", "stamina infinite off");
        aliases.put("unlock-multiplayer", "quest accept 30904");
        aliases.put("unlock-wish", "quest accept 35801");
        aliases.put("unlock-fly", "quest accept 35603");
    }

    /**
     * 生成 HK4E 物品发放指令。
     */
    public String generateItemAddCommand(int itemId, int quantity) {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId必须大于0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity必须大于0");
        }
        return String.format("item add %d %d", itemId, quantity);
    }

    /**
     * 生成 HK4E 任务接取指令。
     */
    public String generateQuestAcceptCommand(int questId) {
        if (questId <= 0) {
            throw new IllegalArgumentException("questId必须大于0");
        }
        return String.format("quest accept %d", questId);
    }

    /**
     * 生成 HK4E 任务完成指令。
     */
    public String generateQuestFinishCommand(int questId) {
        if (questId <= 0) {
            throw new IllegalArgumentException("questId必须大于0");
        }
        return String.format("quest finish %d", questId);
    }

    /**
     * 按别名生成用户提供的 HK4E 固定指令。
     */
    public String generatePresetCommand(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            throw new IllegalArgumentException("alias不能为空");
        }
        String command = aliases.get(alias.trim().toLowerCase(Locale.ROOT));
        if (command == null) {
            throw new IllegalArgumentException("未知HK4E快捷指令: " + alias);
        }
        return command;
    }

    /**
     * 将现有 Grasscutter 风格文本转换成 HK4E/GIO 文本。
     *
     * 这里只转换已经确认的规则，不猜测未知命令。
     */
    public String normalizeCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return command;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }

        normalized = normalized
                .replaceAll("@UID", "")
                .replaceAll("@\\d+", "")
                .replaceAll("@\\s+", "")
                .replaceAll("@", "")
                .replaceAll("\\s+", " ")
                .trim();

        String lower = normalized.toLowerCase(Locale.ROOT);

        // Grasscutter: give 201 x100 -> HK4E: item add 201 100
        if (lower.startsWith("give ")) {
            String[] parts = normalized.split("\\s+");
            if (parts.length >= 3 && parts[1].matches("\\d+") && parts[2].matches("(?i)x?\\d+")) {
                String quantity = parts[2].replaceFirst("(?i)^x", "");
                return "item add " + parts[1] + " " + quantity;
            }
        }

        // Grasscutter: giveall -> HK4E: item add all 999
        if (lower.equals("giveall") || lower.equals("give all")) {
            return "item add all 999";
        }

        // Grasscutter: clear all -> HK4E: item clear
        if (lower.equals("clear all") || lower.equals("clear")) {
            return "item clear";
        }

        // Grasscutter: quest add <id> -> HK4E: quest accept <id>
        if (lower.startsWith("quest add ")) {
            return "quest accept " + normalized.substring("quest add ".length()).trim();
        }

        // 已经是 HK4E 格式时原样返回
        return normalized;
    }
}
