package com.haojing.battlepass.server.settlement;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.reward.RewardSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 用途：赛季轮换时按玩家旧赛季最终等级一次性发放结算奖励。
 *
 * <p>调用时机（见 {@code SeasonResetService}）：先读本玩家旧赛季等级、命中档位发奖，
 * 然后才清空赛季数据。幂等由轮换链路既有判据保证——已重置玩家的 {@code seasonId} 已等于
 * 新赛季 ID，下一轮核对时直接跳过，因此同一旧赛季不会重复结算。
 *
 * <p>奖励口径与等级奖励/签到一致：数值类奖励走 {@link RewardSink}，离线玩家也能由数据层发出；
 * 奖励类型仅限既有合法项，不提供属性、buff 或生存增益。
 */
public final class SettlementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final SettlementManager manager;

    private volatile RewardSink rewardSink;

    public SettlementService(SettlementManager manager) {
        this.manager = manager;
    }

    /** 注入奖励出口。 */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /** @return 结算功能是否启用（配置缺失按关闭处理）。 */
    public boolean enabled() {
        return manager != null && manager.enabled();
    }

    /**
     * 为单个玩家发放赛季结算奖励。
     *
     * @param uuid        玩家 UUID
     * @param finalLevel  旧赛季最终等级（1 起；小于 1 视为 1）
     * @param oldSeasonId 旧赛季 ID（仅用于日志与发奖原因记录）
     * @return 成功发放的奖励条数；开关关闭、无匹配档位或无奖励时为 0
     */
    public int grantSettlement(UUID uuid, int finalLevel, String oldSeasonId) {
        SettlementPool pool = manager == null ? null : manager.pool();

        if (uuid == null || pool == null || !pool.enabled) {
            return 0;
        }

        int level = Math.max(1, finalLevel);
        List<Reward> rewards = pool.rewardsFor(level);

        if (rewards.isEmpty()) {
            LOGGER.info("{} 玩家 {} 旧赛季 {} 最终等级 {} 未命中结算档位，不发放结算奖励",
                    ModConstants.LOG_PREFIX, uuid, oldSeasonId, level);
            return 0;
        }

        String reason = "赛季结算 " + oldSeasonId + " 最终等级 " + level;
        RewardSink sink = rewardSink;
        int granted = 0;

        if (sink == null) {
            LOGGER.warn("{} 结算奖励发放出口尚未接入，本次未发放：{}", ModConstants.LOG_PREFIX, reason);
            return 0;
        }

        for (Reward reward : rewards) {
            if (reward == null) {
                continue;
            }

            try {
                if (sink.grant(uuid, reward, reason, XpSource.SETTLEMENT)) {
                    granted++;
                }
            } catch (RuntimeException e) {
                // 单条奖励发放失败不阻断其余条目；记录后继续，避免整个轮换流程被一条异常卡住。
                LOGGER.error("{} 玩家 {} 的结算奖励发放失败（{}）：{}",
                        ModConstants.LOG_PREFIX, uuid, reward, e.toString());
            }
        }

        LOGGER.info("{} 玩家 {} 旧赛季 {} 最终等级 {}，结算奖励发放 {} 条",
                ModConstants.LOG_PREFIX, uuid, oldSeasonId, level, granted);
        return granted;
    }
}
