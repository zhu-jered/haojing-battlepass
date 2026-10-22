package com.haojing.battlepass.server.settlement;

import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：赛季结算奖励的一个等级档位（settlement.json 中 {@code tiers} 的一项）。
 *
 * <p>口径：赛季轮换时按玩家**最终等级**落在哪个区间一次性发放该档奖励；只发数值/装饰类
 * 奖励（既有 {@link com.haojing.battlepass.common.data.RewardType} 合法项），不提供属性或 buff。
 */
public class SettlementTier {

    /** 档位包含的最低等级（含）。 */
    public int minLevel = 1;

    /** 档位包含的最高等级（含）。 */
    public int maxLevel = 30;

    /** 该档位发放的奖励列表。 */
    public List<Reward> rewards = new ArrayList<>();

    /** 规范化空集合，避免 Gson 反序列化出 null 后下游 NPE。 */
    public void normalize() {
        if (rewards == null) {
            rewards = new ArrayList<>();
        }
    }

    /**
     * 判断某等级是否落在本档位区间内。
     *
     * @param level 玩家最终等级（1 起）
     * @return 是否命中
     */
    public boolean matches(int level) {
        return level >= minLevel && level <= maxLevel;
    }
}
