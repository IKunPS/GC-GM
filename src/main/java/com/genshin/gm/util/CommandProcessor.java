package com.genshin.gm.util;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;

import java.util.*;

/**
 * 指令处理工具类。
 *
 * grasscutter 模式：智能处理 @UID，适配 OpenCommand 控制台。
 * muip 模式：保持 ViaGenshin/GIO 控制台文本，UID 由 MUIP 参数 uid 单独传递。
 */
public class CommandProcessor {

    // 可以指定目标UID的指令列表（在远程执行时需要添加@UID）
    // 注意：统一将@UID放在指令名后面，这是OpenCommand控制台模式下最可靠的位置
    private static final Set<String> UID_TARGETABLE_COMMANDS = new HashSet<>(Arrays.asList(
            // 物品给予类
            "give", "giveall", "giveart", "givechar",

            // 角色管理类
            "avatar", "char",
            "setfetterlevel", "setfetter",
            "setconstellation", "setconst",
            "settalent", "talent",

            // 武器类
            "weapon",

            // 传送类
            "tp", "teleport",
            "tpall",

            // 任务类
            "quest",

            // 玩家属性/状态类
            "heal",
            "setstats", "stats",
            "setworldlevel", "setwl",
            "energy",
            "prop", "setprop",

            // 战斗相关
            "killall", "killcharacter",
            "spawn",

            // 队伍管理
            "team",

            // 邮件
            "mail",

            // 圣遗物构建
            "build",

            // 清理
            "clear",

            // 场景/副本
            "enterdungeon", "dungeon",
            "weather",

            // 其他
            "kick",
            "ban",
            "unban"
    ));

    // 不需要UID的指令列表（仅服务器级别的管理指令）
    private static final Set<String> NO_UID_COMMANDS = new HashSet<>(Arrays.asList(
            // 服务器管理类
            "reload",
            "stop",
            "account",
            "announce", "announcement",
            "broadcast",
            "sendmessage", "say",

            // 信息查询类
            "help", "h",
            "list",
            "status"
    ));

    // 危险指令列表（禁止玩家使用的管理员指令）
    private static final Set<String> DANGEROUS_COMMANDS = new HashSet<>(Arrays.asList(
            // 权限管理（极度危险 - 可以给予自己所有权限）
            "permission",

            // 账户管理
            "account",

            // 服务器控制
            "stop",
            "reload",

            // 封禁相关
            "kick",
            "ban",
            "unban",

            // 广播（防止滥用）
            "broadcast",
            "announce", "announcement"
    ));

    /**
     * 处理指令。
     *
     * grasscutter 模式：智能添加 @UID。
     * muip 模式：按 ViaGenshin console.go 逻辑，命令文本进入 msg，UID 不拼到文本里。
     *
     * @param command 原始指令
     * @param uid 玩家UID
     * @return 处理后的指令
     */
    public static String processCommand(String command, String uid) {
        if (isMuipMode()) {
            return processMuipCommand(command);
        }
        return processGrasscutterCommand(command, uid);
    }

    /**
     * MUIP/GIO 指令处理：不添加 @UID。
     *
     * 为了兼容 Android 端已有快捷指令，把常见 Grasscutter 展示格式转换为 GIO 文本格式：
     * - /give 201 x100 -> give 201 100
     * - /heal -> heal
     * - give @UID 201 99 -> give 201 99
     */
    public static String processMuipCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return command;
        }
        String result = command.trim();
        if (result.startsWith("/")) {
            result = result.substring(1).trim();
        }
        result = result.replaceAll("@UID", "")
                .replaceAll("@\\d+", "")
                .replaceAll("@\\s+", "")
                .replaceAll("@", "")
                .replaceAll("(?i)\\bx(\\d+)\\b", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        return result;
    }

    /**
     * Grasscutter/OpenCommand 指令处理：智能添加UID。
     */
    public static String processGrasscutterCommand(String command, String uid) {
        if (command == null || command.trim().isEmpty()) {
            return command;
        }

        if (uid == null || uid.trim().isEmpty()) {
            throw new IllegalArgumentException("UID不能为空");
        }

        command = command.trim();
        uid = uid.trim();

        // 分割指令获取指令名
        String[] parts = command.split("\\s+");
        if (parts.length == 0) {
            return command;
        }

        String cmdName = parts[0].toLowerCase();

        // 移除指令名开头的斜杠（支持 /tp 和 tp 两种格式）
        if (cmdName.startsWith("/")) {
            cmdName = cmdName.substring(1);
        }

        // 检查是否是不需要UID的指令
        if (NO_UID_COMMANDS.contains(cmdName)) {
            return command;
        }

        // 第一步：移除命令中所有的 @ 符号和相关内容
        // 匹配模式：@UID, @数字, @ （包括后面的空格）
        String cleanCommand = command.replaceAll("@UID", "")
                .replaceAll("@\\d+", "")
                .replaceAll("@\\s+", "")
                .replaceAll("@", "")
                .replaceAll("\\s+", " ")
                .trim();

        // 第二步：统一将 @UID 插入到命令名后面
        // 例如: "prop fly off" → "prop @10261 fly off"
        //       "give 101 x1"  → "give @10261 101 x1"
        //       "tp 1000 500"  → "tp @10261 1000 500"
        String[] cmdParts = cleanCommand.split("\\s+", 2);
        if (cmdParts.length == 1) {
            return cmdParts[0] + " @" + uid;
        } else {
            return cmdParts[0] + " @" + uid + " " + cmdParts[1];
        }
    }

    /**
     * 检查指令是否需要UID
     * @param command 指令
     * @return true如果需要UID
     */
    public static boolean needsUid(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) {
            return false;
        }

        String cmdName = parts[0].toLowerCase();
        if (cmdName.startsWith("/")) {
            cmdName = cmdName.substring(1);
        }
        return UID_TARGETABLE_COMMANDS.contains(cmdName);
    }

    /**
     * 检查指令是否为危险指令（禁止玩家使用）
     * @param command 指令
     * @return 如果是危险指令返回错误信息，否则返回null
     */
    public static String checkDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return null;
        }

        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        String cmdName = parts[0].toLowerCase();
        // 移除斜杠前缀
        if (cmdName.startsWith("/")) {
            cmdName = cmdName.substring(1);
        }

        if (DANGEROUS_COMMANDS.contains(cmdName)) {
            return "该指令已被系统禁止：" + cmdName + " 属于管理员级指令，可能存在安全风险";
        }

        return null; // 不是危险指令
    }

    /**
     * 验证指令格式（兼容 Grasscutter 展示格式和 ViaGenshin/GIO 文本格式）
     * @param command 指令
     * @return 错误信息，如果格式正确则返回null
     */
    public static String validateCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "指令不能为空";
        }

        command = command.trim();

        // 基本格式检查
        if (command.length() > 500) {
            return "指令过长（最多500字符）";
        }

        // 检查是否包含危险字符
        if (command.contains(";") || command.contains("&&") || command.contains("|")) {
            return "指令包含不允许的字符";
        }

        // 移除开头的斜杠进行检查
        String cmdForValidation = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = cmdForValidation.split("\\s+");

        if (parts.length == 0) {
            return "指令格式错误";
        }

        String cmdName = parts[0].toLowerCase();

        // 验证具体指令格式
        String validationError = validateSpecificCommand(cmdName, parts);
        if (validationError != null) {
            return validationError;
        }

        return null; // 验证通过
    }

    /**
     * 验证特定指令的参数格式
     */
    private static String validateSpecificCommand(String cmdName, String[] parts) {
        switch (cmdName) {
            case "clear":
                // clear 需要参数: all, wp, art, mat
                if (parts.length < 2) {
                    return "clear指令缺少参数，用法: clear <all|wp|art|mat> [lv<level>] [r<refinement>] [<quality>*]";
                }
                String clearType = parts[1].toLowerCase();
                if (!clearType.equals("all") && !clearType.equals("wp") &&
                        !clearType.equals("art") && !clearType.equals("mat")) {
                    return "clear指令参数错误，必须是 all, wp, art 或 mat 之一";
                }
                break;

            case "give":
            case "giveall":
                // give 需要至少一个参数（物品ID、角色ID或其他参数）
                if (parts.length < 2) {
                    return "give指令缺少参数，用法: give <itemId|avatarId|params> [modifiers...]";
                }
                // 放宽验证：允许任意字母数字组合，支持各种格式
                // 不再严格检查参数格式，交给服务器处理
                break;

            case "spawn":
                // spawn 需要实体ID
                if (parts.length < 2) {
                    return "spawn指令缺少实体ID，用法: spawn <entityId> [amount] [level]";
                }
                if (!parts[1].matches("\\d+")) {
                    return "spawn指令的实体ID必须是数字";
                }
                // 验证可选的数量和等级参数
                if (parts.length > 2 && !parts[2].matches("\\d+") && !parts[2].startsWith("x")) {
                    return "spawn指令的数量参数格式错误";
                }
                break;

            case "prop":
            case "setprop":
                // prop 需要属性名和值
                if (parts.length < 2) {
                    return "prop指令缺少参数，用法: prop <property> <value> 或 prop <godmode|nostamina|unlimitedenergy> <on|off>";
                }
                break;

            case "account":
                // account 需要 create/delete 动作
                if (parts.length < 3) {
                    return "account指令缺少参数，用法: account <create|delete> <username> [UID]";
                }
                String action = parts[1].toLowerCase();
                if (!action.equals("create") && !action.equals("delete")) {
                    return "account指令的动作必须是 create 或 delete";
                }
                break;

            case "permission":
                // permission 需要 add/remove 动作
                if (parts.length < 3) {
                    return "permission指令缺少参数，用法: permission <add|remove> <permission>";
                }
                String permAction = parts[1].toLowerCase();
                if (!permAction.equals("add") && !permAction.equals("remove")) {
                    return "permission指令的动作必须是 add 或 remove";
                }
                break;

            case "teleport":
            case "tp":
                // teleport 需要坐标或场景
                if (parts.length < 2) {
                    return "teleport指令缺少参数，用法: tp <x> <y> <z> [sceneId] 或 tp @UID";
                }
                // 如果不是@UID格式，则需要至少3个坐标参数
                if (!parts[1].startsWith("@") && parts.length < 4) {
                    return "teleport指令需要3个坐标参数 (x, y, z)";
                }
                break;

            case "team":
                // team 需要动作参数
                if (parts.length < 2) {
                    return "team指令缺少参数，用法: team <add|remove|set> [avatarId,...]";
                }
                String teamAction = parts[1].toLowerCase();
                if (!teamAction.equals("add") && !teamAction.equals("remove") && !teamAction.equals("set")) {
                    return "team指令的动作必须是 add, remove 或 set";
                }
                break;

            case "enterdungeon":
            case "dungeon":
                // enterdungeon 需要副本ID
                if (parts.length < 2) {
                    return "enterdungeon指令缺少副本ID，用法: enterdungeon <dungeonId>";
                }
                if (!parts[1].matches("\\d+")) {
                    return "enterdungeon指令的副本ID必须是数字";
                }
                break;

            case "setstats":
            case "stats":
                // setstats 需要属性和值
                if (parts.length < 3) {
                    return "setstats指令缺少参数，用法: setstats <stat> <value>";
                }
                break;

            case "setfetterlevel":
            case "setfetter":
                // setfetterlevel 需要等级
                if (parts.length < 2) {
                    return "setfetterlevel指令缺少等级参数，用法: setfetterlevel <level>";
                }
                if (!parts[1].matches("\\d+")) {
                    return "setfetterlevel指令的等级必须是数字";
                }
                break;

            case "weather":
                // weather 可以有参数也可以没有
                if (parts.length > 1 && !parts[1].matches("\\d+")) {
                    return "weather指令的天气ID必须是数字，用法: weather [weatherId] [climateId]";
                }
                break;

            case "build":
                // build 需要构建名称
                if (parts.length < 2) {
                    return "build指令缺少构建名称，用法: build <buildName>";
                }
                // 验证构建名称格式（只允许字母、数字）
                if (!parts[1].matches("[a-zA-Z0-9]+")) {
                    return "build指令的构建名称格式错误，只允许字母和数字";
                }
                break;

            // 其他指令暂不做严格验证，允许通过
            default:
                break;
        }

        return null; // 验证通过
    }

    private static boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        return "muip".equalsIgnoreCase(config.getLaunchMode())
                || config.getGrasscutter().isMuipMode();
    }
}
