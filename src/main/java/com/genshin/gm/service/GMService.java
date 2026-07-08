package com.genshin.gm.service;

import com.genshin.gm.config.AppConfig;
import com.genshin.gm.config.ConfigLoader;
import com.genshin.gm.model.GameData;
import com.genshin.gm.util.DataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * GM 指令生成服务。
 *
 * grasscutter 模式：生成 Grasscutter/OpenCommand 格式，例如 /give 201 x1。
 * muip 模式：生成 HK4E/ViaGenshin 控制台文本格式，例如 item add 201 1。
 *            UID 不写进指令文本，由 MUIP 参数 uid 单独传递。
 */
@Service
public class GMService {

    @Autowired
    private DataLoader dataLoader;

    private Map<Integer, String> itemsMap;
    private Map<Integer, String> weaponsMap;
    private Map<Integer, String> avatarsMap;
    private Map<Integer, String> questsMap;

    /**
     * 初始化：加载所有数据
     */
    @PostConstruct
    public void init() {
        dataLoader.validateDataDirectory();
        itemsMap = dataLoader.loadDataAsMap("Item.txt");
        weaponsMap = dataLoader.loadDataAsMap("Weapon.txt");
        avatarsMap = dataLoader.loadDataAsMap("Avatar.txt");
        questsMap = dataLoader.loadDataAsMap("Quest.txt");

        System.out.println("数据加载完成:");
        System.out.println("物品数量: " + itemsMap.size());
        System.out.println("武器数量: " + weaponsMap.size());
        System.out.println("角色数量: " + avatarsMap.size());
        System.out.println("任务数量: " + questsMap.size());
    }

    /**
     * 获取物品列表
     */
    public List<GameData> getItems() {
        return dataLoader.loadDataAsList("Item.txt");
    }

    /**
     * 获取武器列表
     */
    public List<GameData> getWeapons() {
        return dataLoader.loadDataAsList("Weapon.txt");
    }

    /**
     * 获取角色列表
     */
    public List<GameData> getAvatars() {
        return dataLoader.loadDataAsList("Avatar.txt");
    }

    /**
     * 获取任务列表
     */
    public List<GameData> getQuests() {
        return dataLoader.loadDataAsList("Quest.txt");
    }

    /**
     * 生成物品给予指令
     * @param itemId 物品ID
     * @param quantity 数量
     * @return GM 指令
     */
    public String generateGiveCommand(Integer itemId, Integer quantity) {
        if (itemId == null || quantity == null || quantity <= 0) {
            return "错误：参数无效";
        }
        if (isMuipMode()) {
            return String.format("item add %d %d", itemId, quantity);
        }
        return String.format("/give %d x%d", itemId, quantity);
    }

    /**
     * 生成任务添加/接取指令
     * @param questId 任务ID
     * @return GM 指令
     */
    public String generateQuestAddCommand(Integer questId) {
        if (questId == null) {
            return "错误：任务ID无效";
        }
        if (isMuipMode()) {
            return String.format("quest accept %d", questId);
        }
        return String.format("/quest add %d", questId);
    }

    /**
     * 生成任务完成指令
     * @param questId 任务ID
     * @return GM 指令
     */
    public String generateQuestFinishCommand(Integer questId) {
        if (questId == null) {
            return "错误：任务ID无效";
        }
        if (isMuipMode()) {
            return String.format("quest finish %d", questId);
        }
        return String.format("/quest finish %d", questId);
    }

    private boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        return "muip".equalsIgnoreCase(config.getLaunchMode())
                || config.getGrasscutter().isMuipMode();
    }
}
