package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证等级奖励表的校验与查询（需求文档 §9「奖励管理（1~30 级、双分支）」）。
 *
 * <p>重点是"坏配置只坏自己"：一条写错的奖励不能连累整张表，
 * 也不能被静默接受（静默接受最坏会发出一个不存在的物品）。
 */
class LevelRewardTableTest {

    private Reward starCoin(int amount) {
        return new Reward(RewardType.STAR_COIN, amount);
    }

    private Reward title(String titleId) {
        Reward reward = new Reward(RewardType.TITLE, 1);
        reward.titleId = titleId;
        return reward;
    }

    private LevelRewardEntry entry(int level) {
        LevelRewardEntry entry = new LevelRewardEntry();
        entry.level = level;
        return entry;
    }

    @Test
    void 通用与分支奖励分别可查() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry entry = entry(4);
        entry.common.add(starCoin(10));
        entry.hunt.add(starCoin(7));
        entry.build.add(starCoin(9));
        table.levels.add(entry);

        table.validate(30);

        assertEquals(1, table.commonRewards(4).size());
        assertEquals(7, table.branchRewards(4, Branch.HUNT).get(0).amount);
        assertEquals(9, table.branchRewards(4, Branch.BUILD).get(0).amount);
        assertEquals(2, table.rewardsFor(4, Branch.HUNT).size(), "rewardsFor 应包含通用 + 分支");
        assertTrue(table.hasBranchReward(4, Branch.HUNT));
        assertFalse(table.hasBranchReward(4, Branch.NONE));
        assertTrue(table.branchRewards(4, Branch.NONE).isEmpty(), "未选分支不应拿到分支奖励");
    }

    @Test
    void 超出等级上限的条目被丢弃() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry entry = entry(31);
        entry.common.add(starCoin(10));
        table.levels.add(entry);

        table.validate(30);

        assertEquals(0, table.configuredLevelCount());
        assertTrue(table.commonRewards(31).isEmpty());
    }

    @Test
    void 重复等级只保留第一次() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry first = entry(5);
        first.common.add(starCoin(5));
        LevelRewardEntry second = entry(5);
        second.common.add(starCoin(500));
        table.levels.add(first);
        table.levels.add(second);

        table.validate(30);

        assertEquals(1, table.configuredLevelCount());
        assertEquals(5, table.commonRewards(5).get(0).amount);
    }

    @Test
    void 禁用的条目被跳过但内容保留() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry enabled = entry(2);
        enabled.common.add(starCoin(10));
        LevelRewardEntry disabled = entry(3);
        disabled.enabled = false;
        disabled.common.add(starCoin(999));
        table.levels.add(enabled);
        table.levels.add(disabled);

        table.validate(30);

        assertEquals(1, table.configuredLevelCount());
        assertTrue(table.commonRewards(3).isEmpty(), "禁用的等级不应发放");
        assertEquals(999, disabled.common.get(0).amount, "禁用不等于删除，配置内容应原样保留");
    }

    @Test
    void 非法奖励被丢弃而合法奖励照常生效() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry entry = entry(7);
        entry.common.add(starCoin(10));

        Reward broken = new Reward(RewardType.ITEM, 1);
        broken.itemId = "";
        entry.common.add(broken);

        Reward unknown = new Reward();
        unknown.type = "NOT_A_TYPE";
        entry.common.add(unknown);

        table.levels.add(entry);
        table.validate(30);

        assertEquals(1, table.commonRewards(7).size(), "只有合法的那条应留下");
        assertEquals(10, table.commonRewards(7).get(0).amount);
    }

    @Test
    void 三条列表全空的条目被丢弃() {
        LevelRewardTable table = new LevelRewardTable();
        table.levels.add(entry(8));

        table.validate(30);

        assertEquals(0, table.configuredLevelCount());
    }

    @Test
    void 只有分支奖励的条目仍然有效() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry entry = entry(12);
        entry.hunt.add(starCoin(3));
        table.levels.add(entry);

        table.validate(30);

        assertEquals(1, table.configuredLevelCount());
        assertTrue(table.commonRewards(12).isEmpty());
        assertEquals(1, table.branchRewards(12, Branch.HUNT).size());
    }

    @Test
    void 奖励在入库前被规范化() {
        LevelRewardTable table = new LevelRewardTable();
        LevelRewardEntry entry = entry(9);
        Reward messy = new Reward(RewardType.COMMAND, 1);
        messy.command = "  /give @s minecraft:diamond 1  ";
        entry.common.add(messy);
        table.levels.add(entry);

        table.validate(30);

        List<Reward> stored = table.commonRewards(9);
        assertEquals(1, stored.size());
        assertEquals("give @s minecraft:diamond 1", stored.get(0).command, "前导斜杠与空白应被剥掉");
    }

    @Test
    void 配置的等级列表升序返回() {
        LevelRewardTable table = new LevelRewardTable();

        for (int level : new int[] {20, 5, 12}) {
            LevelRewardEntry e = entry(level);
            e.common.add(starCoin(1));
            table.levels.add(e);
        }

        table.validate(30);

        assertEquals(List.of(5, 12, 20), table.configuredLevels());
        assertEquals(1, table.usedRewardTypes().size(), "只用到 STAR_COIN 一种类型");
        assertTrue(table.usedRewardTypes().contains("STAR_COIN"));
    }

    @Test
    void 含null元素的列表不会崩() {
        LevelRewardTable table = new LevelRewardTable();
        table.levels.add(null);
        LevelRewardEntry entry = entry(2);
        entry.common = null;
        entry.hunt = null;
        entry.build = null;
        table.levels.add(entry);

        table.validate(30);

        assertEquals(0, table.configuredLevelCount(), "空列表与 null 元素都应按空处理");
        assertTrue(table.commonRewards(2).isEmpty());
        assertTrue(table.branchRewards(2, Branch.HUNT).isEmpty());
    }
}
