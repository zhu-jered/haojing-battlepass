package com.haojing.battlepass.server.shop;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：战令商店的商品清单，对应 config/haojing_battlepass/shop.json。
 *
 * <p>为什么校验策略与任务池、等级奖励表完全一致（丢弃非法项 + 逐条告警）：
 * 这三份配置都是"管理员会手改的清单"，处理方式统一之后，
 * 管理员遇到任何一份配置写错时的行为都是可预期的：坏的那一条不生效，其余照常。
 */
public class ShopCatalog {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 商品列表。 */
    public List<ShopItem> items = new ArrayList<>();

    /** 商品 ID → 商品。校验时重建，运行期只读。 */
    private transient Map<String, ShopItem> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 校验并建立索引。非法商品会被丢弃，每条都留 WARN。 */
    public void validate() {
        Map<String, ShopItem> built = new HashMap<>();
        int dropped = 0;

        if (items == null) {
            items = new ArrayList<>();
        }

        for (ShopItem item : items) {
            if (item == null) {
                dropped++;
                continue;
            }

            if (item.id == null || item.id.isBlank()) {
                LOGGER.warn("{} 商店里有一件商品缺少 id，已丢弃", ModConstants.LOG_PREFIX);
                dropped++;
                continue;
            }

            item.id = item.id.trim();
            item.nameKey = item.nameKey == null ? "" : item.nameKey.trim();
            item.descKey = item.descKey == null ? "" : item.descKey.trim();
            item.group = item.group == null ? "" : item.group.trim().toLowerCase(java.util.Locale.ROOT);

            if (built.containsKey(item.id)) {
                LOGGER.warn("{} 商店里商品 id 重复：{}，只保留第一次出现的", ModConstants.LOG_PREFIX, item.id);
                dropped++;
                continue;
            }

            if (item.price < 0) {
                LOGGER.warn("{} 商店商品 {} 的价格为负数（{}），已按 0 处理", ModConstants.LOG_PREFIX, item.id, item.price);
                item.price = 0;
            }

            if (item.limitPerPlayer < 0) {
                LOGGER.warn("{} 商店商品 {} 的限购为负数（{}），已按 0（不限购）处理",
                        ModConstants.LOG_PREFIX, item.id, item.limitPerPlayer);
                item.limitPerPlayer = 0;
            }

            if (item.reward == null) {
                LOGGER.warn("{} 商店商品 {} 没有配奖励，已丢弃", ModConstants.LOG_PREFIX, item.id);
                dropped++;
                continue;
            }

            item.reward.normalize();
            String error = item.reward.validateError();

            if (error != null) {
                LOGGER.warn("{} 商店商品 {} 的奖励非法，该商品已丢弃：{}（{}）",
                        ModConstants.LOG_PREFIX, item.id, error, item.reward);
                dropped++;
                continue;
            }

            // 名称翻译键缺失只告警不丢弃：商品仍可购买，GUI 上会显示 ID，
            // 总好过管理员改文案时手滑导致整件商品消失。
            if (item.nameKey.isEmpty()) {
                LOGGER.warn("{} 商店商品 {} 缺少 nameKey，界面将显示商品 ID", ModConstants.LOG_PREFIX, item.id);
            }

            built.put(item.id, item);
        }

        index = built;

        if (dropped > 0) {
            LOGGER.warn("{} 商店配置校验完成：丢弃商品 {} 件", ModConstants.LOG_PREFIX, dropped);
        }
    }

    /**
     * @param itemId 商品 ID
     * @return 商品；不存在时为 null
     */
    public ShopItem item(String itemId) {
        return itemId == null ? null : index.get(itemId);
    }

    /** @return 上架中的商品列表（按配置顺序）。 */
    public List<ShopItem> enabledItems() {
        List<ShopItem> result = new ArrayList<>();

        for (ShopItem item : index.values()) {
            if (item.enabled) {
                result.add(item);
            }
        }

        return result;
    }

    /** @return 有效的商品数量。 */
    public int size() {
        return index.size();
    }

    /** @return 一件商品里用到的最贵的价格，用于日志。 */
    public int maxPrice() {
        int max = 0;

        for (ShopItem item : index.values()) {
            max = Math.max(max, item.price);
        }

        return max;
    }

    /** @return 商店里出现过的奖励类型名（供自检）。 */
    public java.util.Set<String> usedRewardTypes() {
        java.util.Set<String> types = new java.util.LinkedHashSet<>();

        for (ShopItem item : index.values()) {
            if (item.reward != null) {
                types.add(item.reward.type);
            }
        }

        return types;
    }
}
