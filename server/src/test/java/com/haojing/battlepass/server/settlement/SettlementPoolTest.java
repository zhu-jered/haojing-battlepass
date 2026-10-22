package com.haojing.battlepass.server.settlement;

import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖赛季结算档位的区间匹配与配置校验口径（需求外新增 D-39）。
 *
 * <p>为什么档位匹配要单独测：轮换时按旧赛季最终等级一次性发奖，边界等级（4/5、9/10、29/30）
 * 一旦归错档，玩家拿到的奖励档位就会错；重叠区间"先出现优先"是管理员排版口径，
 * 也必须由测试固定下来，避免后续改成"最大档优先"时悄悄改变发奖结果。
 */
class SettlementPoolTest {

    private static SettlementTier tier(int minLevel, int maxLevel, List<Reward> rewards) {
        SettlementTier tier = new SettlementTier();
        tier.minLevel = minLevel;
        tier.maxLevel = maxLevel;
        tier.rewards = rewards;
        return tier;
    }

    private static Reward coin(int amount) {
        return new Reward(RewardType.STAR_COIN, amount);
    }

    @Test
    void 区间边界等级命中正确档位() {
        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(
                tier(1, 4, List.of(coin(5))),
                tier(5, 9, List.of(coin(15))),
                tier(10, 30, List.of(coin(30)))));
        pool.validate();

        assertEquals(5, pool.rewardsFor(1).get(0).amount);
        assertEquals(5, pool.rewardsFor(4).get(0).amount, "4 属于低档上界");
        assertEquals(15, pool.rewardsFor(5).get(0).amount, "5 属于中档下界");
        assertEquals(15, pool.rewardsFor(9).get(0).amount, "9 属于中档上界");
        assertEquals(30, pool.rewardsFor(10).get(0).amount, "10 属于高档下界");
        assertEquals(30, pool.rewardsFor(30).get(0).amount, "30 属于高档上界");
    }

    @Test
    void 区间重叠时以配置中先出现的档位为准() {
        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(
                tier(1, 10, List.of(coin(1))),
                tier(5, 20, List.of(coin(99)))));
        pool.validate();

        // level=8 同时落在两档，应取先配置的低档（管理员负责排版顺序，服务端不擅自重排）。
        assertEquals(1, pool.rewardsFor(8).get(0).amount);
    }

    @Test
    void 无匹配档位时返回空列表不跨档补发() {
        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(tier(1, 4, List.of(coin(5)))));
        pool.validate();

        assertTrue(pool.rewardsFor(0).isEmpty(), "等级小于 1 不发奖");
        assertTrue(pool.rewardsFor(31).isEmpty(), "超出最高档位不跨到下一档");
    }

    @Test
    void 非法区间档位被丢弃() {
        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(
                tier(0, 4, List.of(coin(5))),
                tier(9, 5, List.of(coin(15))),
                tier(10, 30, List.of(coin(30)))));
        pool.validate();

        assertEquals(1, pool.tiers().size(), "minLevel<1 与区间写反的两档都应被丢弃");
        assertEquals(30, pool.rewardsFor(10).get(0).amount);
        assertTrue(pool.rewardsFor(4).isEmpty(), "低档被丢弃后等级 4 无档可匹配");
    }

    @Test
    void 档位内非法奖励被逐条丢弃合法奖励保留() {
        Reward badType = new Reward();
        badType.type = "NOT_A_REWARD";
        badType.amount = 1;

        Reward zeroAmount = new Reward(RewardType.STAR_COIN, 0);

        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(tier(1, 30,
                new ArrayList<>(List.of(coin(5), badType, zeroAmount)))));
        pool.validate();

        List<Reward> rewards = pool.rewardsFor(1);
        assertEquals(1, rewards.size(), "非法类型与非正数数量两条应被丢弃");
        assertEquals(RewardType.STAR_COIN, rewards.get(0).typeOrNull());
    }

    @Test
    void 返回副本外部修改不影响配置() {
        SettlementPool pool = new SettlementPool();
        pool.tiers = new ArrayList<>(List.of(tier(1, 30, List.of(coin(5)))));
        pool.validate();

        List<Reward> snapshot = pool.rewardsFor(1);
        snapshot.get(0).amount = 999;

        assertEquals(5, pool.rewardsFor(1).get(0).amount,
                "返回副本之后，外部改不到内存里的原始奖励");
    }
}
