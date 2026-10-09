package com.haojing.battlepass.server.reward;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 用途：发放"只改数据文件"的那几类奖励 —— STAR_COIN / TITLE / EXEMPT_CARD / BATTLEPASS_XP。
 *
 * <p>为什么与 ITEM / COMMAND 分开：这四类只依赖玩家数据文件，**离线玩家也能发放**，
 * 因此不引用任何 Minecraft 类型，可以被单元测试直接覆盖；
 * 而物品要塞进背包、命令要挂到玩家身上执行，只有玩家在线时才可能完成。
 * 把两者分开，就避免了"离线时偷偷吞掉奖励"这种最难发现的问题 ——
 * 分开之后，{@link MinecraftRewardSink} 会对离线玩家的 ITEM/COMMAND 明确告警并返回失败，
 * 由调用方决定怎么处理（商店会退款）。
 *
 * <p>为什么 BATTLEPASS_XP 走一个可注入的 {@link XpSink} 而不是直接引用 BattlePassService：
 * BattlePassService 发放等级奖励时又要回头调用本类（发星币/称号），两者互相引用。
 * 用一个函数式接口断开这条环，构造顺序就变成线性的，不再需要"先造 A 再造 B 再回填 A"的隐式约定。
 */
public final class DataRewardApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 发放战令经验的出口。由 BattlePassService 在构造后注入（见类注释）。 */
    @FunctionalInterface
    public interface XpSink {
        /**
         * @param playerUuid 玩家
         * @param amount     经验值（原始值，倍率与每日上限由 BattlePassService 统一处理）
         * @param source     经验来源
         */
        void grantXp(UUID playerUuid, int amount, XpSource source);
    }

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;

    private volatile XpSink xpSink;

    public DataRewardApplier(PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    /**
     * 注入经验发放出口。
     *
     * <p>必须在服务端启动流程里调用一次；未注入时 BATTLEPASS_XP 类奖励会被拒绝并告警，
     * 而不是静默吞掉。
     *
     * @param sink 经验出口
     */
    public void setXpSink(XpSink sink) {
        this.xpSink = sink;
    }

    /**
     * @param type 奖励类型
     * @return 本类是否负责该类型
     */
    public static boolean handles(RewardType type) {
        return type == RewardType.STAR_COIN || type == RewardType.TITLE
                || type == RewardType.EXEMPT_CARD || type == RewardType.BATTLEPASS_XP;
    }

    /**
     * 发放一条数据类奖励。
     *
     * @param playerUuid 玩家
     * @param reward     奖励
     * @param reason     日志用的缘由
     * @param xpSource   奖励为 BATTLEPASS_XP 时使用的经验来源
     * @return 是否成功发放
     */
    public boolean grant(UUID playerUuid, Reward reward, String reason, XpSource xpSource) {
        if (playerUuid == null || reward == null) {
            return false;
        }

        RewardType type = reward.typeOrNull();

        if (type == null || !handles(type)) {
            LOGGER.warn("{} {}：奖励类型 {} 不属于数据类奖励，已拒绝发放", ModConstants.LOG_PREFIX, reason, reward.type);
            return false;
        }

        String error = reward.validateError();

        if (error != null) {
            LOGGER.warn("{} {}：奖励定义非法（{}），已拒绝发放", ModConstants.LOG_PREFIX, reason, error);
            return false;
        }

        switch (type) {
            case STAR_COIN:
                return grantStarCoin(playerUuid, reward.amount, reason);
            case TITLE:
                return grantTitle(playerUuid, reward, reason);
            case EXEMPT_CARD:
                return grantExemptCard(playerUuid, reward.amount, reason);
            case BATTLEPASS_XP:
                return grantBattlePassXp(playerUuid, reward.amount, reason, xpSource);
            default:
                return false;
        }
    }

    /**
     * 发放一条数据类奖励（经验来源默认按"等级奖励"记录）。
     *
     * @param playerUuid 玩家
     * @param reward     奖励
     * @param reason     日志用的缘由
     * @return 是否成功发放
     */
    public boolean grant(UUID playerUuid, Reward reward, String reason) {
        return grant(playerUuid, reward, reason, XpSource.LEVEL_REWARD);
    }

    private boolean grantStarCoin(UUID playerUuid, int amount, String reason) {
        GlobalData global = dataManager.global(playerUuid);
        global.starCoin += amount;
        dataManager.markGlobalDirty(playerUuid);

        LOGGER.info("{} {}：玩家 {} 获得京币 +{}（余额 {}）",
                ModConstants.LOG_PREFIX, reason, playerUuid, amount, global.starCoin);
        return true;
    }

    private boolean grantTitle(UUID playerUuid, Reward reward, String reason) {
        GlobalData global = dataManager.global(playerUuid);
        boolean changed = global.unlockTitle(reward.titleId);

        if (changed) {
            dataManager.markGlobalDirty(playerUuid);
        }

        LOGGER.info("{} {}：玩家 {} {}称号 {}",
                ModConstants.LOG_PREFIX, reason, playerUuid, changed ? "解锁了" : "已拥有", reward.titleId);

        // 已经拥有同名称号也算"发放成功"：重复发放不该被当成失败，
        // 否则管理员补发奖励时会收到一堆无意义的失败告警。
        return true;
    }

    /**
     * 发放豁免卡。需求文档 §5.9：每人持有上限默认 3。
     *
     * <p>为什么超限时是"截断到上限 + 告警"而不是"整条拒绝"：
     * 等级奖励通常是自动发放的，若因为背包满了就直接丢掉，玩家会永远拿不到那张卡；
     * 截断至少把能装的装进去，并把"少发了几张"明确写进日志。
     */
    private boolean grantExemptCard(UUID playerUuid, int amount, String reason) {
        SeasonConfig config = configManager.config();
        int max = config == null ? 3 : Math.max(0, config.exemptCardMax);
        SeasonData season = dataManager.season(playerUuid);
        int room = Math.max(0, max - season.exemptCards);

        if (room <= 0) {
            LOGGER.warn("{} {}：玩家 {} 的任务卡已达上限 {}，本次 {} 张未发放",
                    ModConstants.LOG_PREFIX, reason, playerUuid, max, amount);
            return false;
        }

        int granted = Math.min(room, amount);
        season.exemptCards += granted;
        dataManager.markSeasonDirty(playerUuid);

        if (granted < amount) {
            LOGGER.warn("{} {}：玩家 {} 的任务卡只能再发 {} 张（上限 {}），本次少发 {} 张",
                    ModConstants.LOG_PREFIX, reason, playerUuid, granted, max, amount - granted);
        } else {
            LOGGER.info("{} {}：玩家 {} 获得任务卡 +{}（现有 {}）",
                    ModConstants.LOG_PREFIX, reason, playerUuid, granted, season.exemptCards);
        }

        return true;
    }

    private boolean grantBattlePassXp(UUID playerUuid, int amount, String reason, XpSource source) {
        XpSink sink = xpSink;

        if (sink == null) {
            LOGGER.warn("{} {}：经验发放出口尚未接入，本次 {} 点战令经验未发放", ModConstants.LOG_PREFIX, reason, amount);
            return false;
        }

        sink.grantXp(playerUuid, amount, source);
        return true;
    }
}
