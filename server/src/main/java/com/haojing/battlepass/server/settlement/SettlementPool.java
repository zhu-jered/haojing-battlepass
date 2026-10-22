package com.haojing.battlepass.server.settlement;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：赛季结算奖励配置清单（config/haojing_battlepass/settlement.json）。
 *
 * <p>结构：一个总开关 + 若干"等级区间 → 奖励列表"档位。赛季轮换时，服务端先读玩家旧赛季
 * 最终等级，匹配首个命中的档位，把该档奖励一次性发给玩家，随后再清空赛季数据。
 *
 * <p>校验策略与其它配置清单一致：丢弃非法档位/非法奖励并逐条告警；奖励合法性复用
 * {@link Reward#validateError()}，不在本类重复实现规则。区间重叠时以配置中先出现的档位为准，
 * 管理员负责保证档位顺序与衔接。
 */
public class SettlementPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 赛季结算功能总开关。关闭时轮换流程照常进行，只是不发任何结算奖励。 */
    public boolean enabled = true;

    /** 等级档位列表。 */
    public List<SettlementTier> tiers = new ArrayList<>();

    /** 校验配置、过滤非法档位与奖励并规范化空集合。 */
    public void validate() {
        if (tiers == null) {
            tiers = new ArrayList<>();
        }

        List<SettlementTier> validTiers = new ArrayList<>();

        for (SettlementTier tier : tiers) {
            if (tier == null) {
                continue;
            }

            tier.normalize();

            if (tier.minLevel < 1 || tier.maxLevel < tier.minLevel) {
                LOGGER.warn("{} 结算档位区间非法（{}-{}），已丢弃",
                        ModConstants.LOG_PREFIX, tier.minLevel, tier.maxLevel);
                continue;
            }

            List<Reward> validRewards = new ArrayList<>();

            for (Reward reward : tier.rewards) {
                if (reward == null) {
                    continue;
                }

                reward.normalize();
                String error = reward.validateError();

                if (error != null) {
                    LOGGER.warn("{} 结算档位 {}-{} 的奖励非法，已丢弃：{}",
                            ModConstants.LOG_PREFIX, tier.minLevel, tier.maxLevel, error);
                    continue;
                }

                validRewards.add(reward);
            }

            tier.rewards = validRewards;
            validTiers.add(tier);
        }

        tiers = validTiers;
    }

    /**
     * 匹配某个最终等级对应的档位奖励。
     *
     * <p>为什么要逐条深拷贝而不是只复制列表：服务端发奖链路（{@link SettlementService}）
     * 拿到列表后可能就地改数量/类型，浅拷贝会把内存里的配置对象一并改脏；返回独立副本后，
     * 调用方怎么改都不会回写到本池，下一名玩家结算仍拿到配置原始数值。
     *
     * @param level 旧赛季最终等级（1 起）
     * @return 首个命中档位的奖励副本；无命中档位时返回空列表（不跨档补发）
     */
    public List<Reward> rewardsFor(int level) {
        if (tiers == null || level < 1) {
            return new ArrayList<>();
        }

        for (SettlementTier tier : tiers) {
            if (tier != null && tier.matches(level)) {
                List<Reward> copy = new ArrayList<>();
                for (Reward reward : tier.rewards) {
                    if (reward == null) {
                        continue;
                    }
                    Reward snapshot = new Reward();
                    snapshot.type = reward.type;
                    snapshot.amount = reward.amount;
                    snapshot.itemId = reward.itemId;
                    snapshot.command = reward.command;
                    snapshot.titleId = reward.titleId;
                    copy.add(snapshot);
                }
                return copy;
            }
        }

        return new ArrayList<>();
    }

    /** @return 全部档位（顺序即配置顺序，也是重叠时的优先顺序）。 */
    public List<SettlementTier> tiers() {
        return tiers == null ? new ArrayList<>() : new ArrayList<>(tiers);
    }
}
