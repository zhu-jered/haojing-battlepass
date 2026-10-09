package com.haojing.battlepass.server.shop;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.config.FileChangeDetector;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 用途：战令商店 —— 商品配置的加载/热重载 + 星币兑换（需求文档 §8、§9、§4）。
 *
 * <p>为什么兑换要先扣星币再发奖、发奖失败就退款：商店与等级奖励不同 ——
 * 等级奖励是白送的，发失败最多是漏发；商店是玩家花星币买的，
 * 收钱不给货是绝对不能接受的。因此这里把"扣款成功但发放失败"当作一次失败的交易并原路退款，
 * 宁可让玩家重新点一次，也不能出现扣了 100 星币什么都没拿到的记录。
 *
 * <p>为什么本类不引用 Minecraft 类型：购买规则（限购、余额、幂等）全都需要单元测试，
 * 而发放动作通过 {@link RewardSink} 出去（物品与命令在实现里处理）。
 */
public final class ShopManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 内置默认商店清单的资源路径。 */
    private static final String DEFAULT_RESOURCE = "/haojing_battlepass/default_shop.json";

    /** 购买结果。 */
    public enum PurchaseOutcome {
        /** 购买成功。 */
        OK,
        /** 没有这件商品（可能已下架或 ID 写错）。 */
        NOT_FOUND,
        /** 商品已下架。 */
        DISABLED,
        /** 星币不足。 */
        NOT_ENOUGH_COINS,
        /** 已达限购次数。 */
        LIMIT_REACHED,
        /** 奖励发放失败，已退款（最常见的原因是玩家离线时买的是物品类奖励）。 */
        GRANT_FAILED
    }

    /**
     * 购买结果明细。
     *
     * @param outcome      结果
     * @param itemId       商品 ID
     * @param coinsSpent   实际扣掉的星币
     * @param balanceAfter 交易后的星币余额
     */
    public record PurchaseResult(PurchaseOutcome outcome, String itemId, int coinsSpent, int balanceAfter) {
    }

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final PlayerDataManager dataManager;
    private final FileChangeDetector changeDetector = new FileChangeDetector("商店配置");

    private volatile ShopCatalog catalog;
    private volatile RewardSink rewardSink;

    public ShopManager(StoragePaths paths, JsonStore jsonStore, PlayerDataManager dataManager) {
        this.paths = paths;
        this.jsonStore = jsonStore;
        this.dataManager = dataManager;
    }

    /** @return 当前生效的商店清单；未加载时为 null。 */
    public ShopCatalog catalog() {
        return catalog;
    }

    /** @return 商店配置文件路径。 */
    public Path configFile() {
        return paths.shopConfigFile();
    }

    /**
     * 注入奖励发放出口。原因与 {@code BattlePassService#setRewardSink} 相同：
     * 出口实现需要在发放时回头读玩家数据，构造参数会形成环。
     *
     * @param sink 奖励出口
     */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /** 从磁盘加载商店配置，必要时先生成默认清单。 */
    public ShopCatalog load() {
        Path file = paths.shopConfigFile();
        copyBundledDefaultIfMissing(file);

        ShopCatalog loaded = jsonStore.read(file, ShopCatalog.class, () -> {
            LOGGER.error("{} 商店配置缺失或损坏，已退回空商店 —— 在补齐配置前玩家买不到任何东西：{}",
                    ModConstants.LOG_PREFIX, file);
            return new ShopCatalog();
        });

        loaded.validate();
        catalog = loaded;
        writeQuietly(loaded);
        changeDetector.reset(file);

        LOGGER.info("{} 战令商店已加载：{} 件商品（上架 {} 件，最高定价 {} 京币）",
                ModConstants.LOG_PREFIX, loaded.size(), loaded.enabledItems().size(), loaded.maxPrice());

        return loaded;
    }

    /** 把当前内存中的商店配置写回磁盘。供管理员 GUI 修改后调用。 */
    public void save() {
        ShopCatalog current = catalog;

        if (current == null) {
            LOGGER.warn("{} 商店配置尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        current.validate();
        writeQuietly(current);
    }

    /**
     * 热重载商店配置（需求文档 §2：管理员在 GUI 修改的所有配置支持热重载）。
     *
     * @return 重载后的清单
     */
    public ShopCatalog reload() {
        LOGGER.info("{} 正在热重载商店配置：{}", ModConstants.LOG_PREFIX, configFile());
        return load();
    }

    /**
     * 若商店配置被外部改动过，则自动热重载。
     *
     * @return 本次是否真的发生了重载
     */
    public boolean reloadIfChanged() {
        Path file = paths.shopConfigFile();

        if (!changeDetector.hasChanged(file)) {
            return false;
        }

        reload();
        changeDetector.reset(file);
        return true;
    }

    /**
     * @param playerUuid 玩家
     * @param itemId     商品 ID
     * @return 该玩家已购买次数
     */
    public int purchasedCount(UUID playerUuid, String itemId) {
        return dataManager.global(playerUuid).purchaseCount(itemId);
    }

    /**
     * 用星币兑换一件商品。
     *
     * @param playerUuid 玩家
     * @param itemId     商品 ID
     * @return 交易结果
     */
    public PurchaseResult purchase(UUID playerUuid, String itemId) {
        ShopCatalog current = catalog;
        RewardSink sink = rewardSink;

        if (playerUuid == null || itemId == null || current == null) {
            return new PurchaseResult(PurchaseOutcome.NOT_FOUND, itemId, 0, 0);
        }

        ShopItem item = current.item(itemId);

        if (item == null) {
            return new PurchaseResult(PurchaseOutcome.NOT_FOUND, itemId, 0, balanceOf(playerUuid));
        }

        if (!item.enabled) {
            return new PurchaseResult(PurchaseOutcome.DISABLED, itemId, 0, balanceOf(playerUuid));
        }

        GlobalData global = dataManager.global(playerUuid);

        // 检查 + 扣款在同一把锁里完成：同一玩家连点两次也不会把同一件限购商品买成两份。
        synchronized (global) {
            if (item.limited() && global.purchaseCount(itemId) >= item.limitPerPlayer) {
                return new PurchaseResult(PurchaseOutcome.LIMIT_REACHED, itemId, 0, global.starCoin);
            }

            if (global.starCoin < item.price) {
                return new PurchaseResult(PurchaseOutcome.NOT_ENOUGH_COINS, itemId, 0, global.starCoin);
            }

            global.starCoin -= item.price;
        }

        dataManager.markGlobalDirty(playerUuid);

        if (sink == null) {
            refund(global, item.price, playerUuid);
            LOGGER.error("{} 玩家 {} 购买 {} 失败：奖励发放出口尚未接入，已退款", ModConstants.LOG_PREFIX, playerUuid, itemId);
            return new PurchaseResult(PurchaseOutcome.GRANT_FAILED, itemId, 0, global.starCoin);
        }

        boolean granted = sink.grant(playerUuid, item.reward, "商店商品 " + itemId, XpSource.SHOP);

        if (!granted) {
            refund(global, item.price, playerUuid);
            LOGGER.error("{} 玩家 {} 购买 {} 失败（奖励发放失败），已退还 {} 京币",
                    ModConstants.LOG_PREFIX, playerUuid, itemId, item.price);
            return new PurchaseResult(PurchaseOutcome.GRANT_FAILED, itemId, 0, global.starCoin);
        }

        synchronized (global) {
            global.recordPurchase(itemId);
        }

        dataManager.markGlobalDirty(playerUuid);

        LOGGER.info("{} 玩家 {} 在商店购买了 {}（花费 {} 京币，余额 {}）",
                ModConstants.LOG_PREFIX, playerUuid, itemId, item.price, global.starCoin);

        return new PurchaseResult(PurchaseOutcome.OK, itemId, item.price, global.starCoin);
    }

    private void refund(GlobalData global, int amount, UUID playerUuid) {
        synchronized (global) {
            global.starCoin += amount;
        }

        dataManager.markGlobalDirty(playerUuid);
    }

    private int balanceOf(UUID playerUuid) {
        return dataManager.global(playerUuid).starCoin;
    }

    private void copyBundledDefaultIfMissing(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            try (InputStream in = ShopManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    LOGGER.error("{} 内置默认商店资源不存在（{}），无法生成默认配置",
                            ModConstants.LOG_PREFIX, DEFAULT_RESOURCE);
                    return;
                }

                Files.copy(in, file);
                LOGGER.info("{} 已从内置默认商店生成配置文件，可直接编辑：{}", ModConstants.LOG_PREFIX, file);
            }
        } catch (IOException e) {
            LOGGER.error("{} 生成默认商店配置失败：{}", ModConstants.LOG_PREFIX, file, e);
        }
    }

    private void writeQuietly(ShopCatalog value) {
        try {
            jsonStore.write(paths.shopConfigFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入商店配置失败（内存中的清单仍然生效）：{}",
                    ModConstants.LOG_PREFIX, paths.shopConfigFile(), e);
        }
    }
}
