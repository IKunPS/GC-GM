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
 * Grasscutter 指令在本类生成；
 * HK4E/MUIP 指令统一交给 Hk4eCommandService。
 */
@Service
public class GMService {

    @Autowired
    private DataLoader dataLoader;

    @Autowired
    private Hk4eCommandService hk4eCommandService;

    private Map<Integer, String> itemsMap;
    private Map<Integer, String> weaponsMap;
    private Map<Integer, String> avatarsMap;
    private Map<Integer, String> questsMap;

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

    public List<GameData> getItems() {
        return dataLoader.loadDataAsList("Item.txt");
    }

    public List<GameData> getWeapons() {
        return dataLoader.loadDataAsList("Weapon.txt");
    }

    public List<GameData> getAvatars() {
        return dataLoader.loadDataAsList("Avatar.txt");
    }

    public List<GameData> getQuests() {
        return dataLoader.loadDataAsList("Quest.txt");
    }

    public String generateGiveCommand(Integer itemId, Integer quantity) {
        if (itemId == null || quantity == null || quantity <= 0) {
            return "错误：参数无效";
        }
        if (isMuipMode()) {
            return hk4eCommandService.generateItemAddCommand(itemId, quantity);
        }
        return String.format("/give %d x%d", itemId, quantity);
    }

    public String generateQuestAddCommand(Integer questId) {
        if (questId == null) {
            return "错误：任务ID无效";
        }
        if (isMuipMode()) {
            return hk4eCommandService.generateQuestAcceptCommand(questId);
        }
        return String.format("/quest add %d", questId);
    }

    public String generateQuestFinishCommand(Integer questId) {
        if (questId == null) {
            return "错误：任务ID无效";
        }
        if (isMuipMode()) {
            return hk4eCommandService.generateQuestFinishCommand(questId);
        }
        return String.format("/quest finish %d", questId);
    }

    private boolean isMuipMode() {
        AppConfig config = ConfigLoader.getConfig();
        return "muip".equalsIgnoreCase(config.getLaunchMode())
                || config.getGrasscutter().isMuipMode();
    }
}
