package com.haojing.battlepass.server.reward;

import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证 Reward 这一统一奖励描述的自校验与规范化（需求文档 §9）。
 *
 * <p>为什么要在 DTO 上做校验而不是靠发放方各自判断：奖励来自管理员手写的 JSON，
 * 每一处发放点（等级奖励、商店、彩蛋、里程碑）都重新判断一遍格式必然漏。
 * 把"什么样算合法"收在 DTO 里，配置层校验一次就够。
 */
class RewardTest {

    @Test
    void 六种类型的合法奖励都能通过校验() {
        Reward item = new Reward(RewardType.ITEM, 1);
        item.itemId = "minecraft:diamond";
        assertNull(item.validateError());

        Reward command = new Reward(RewardType.COMMAND, 1);
        command.command = "give @s minecraft:diamond 1";
        assertNull(command.validateError());

        Reward xp = new Reward(RewardType.BATTLEPASS_XP, 100);
        assertNull(xp.validateError());

        Reward coin = new Reward(RewardType.STAR_COIN, 10);
        assertNull(coin.validateError());

        Reward title = new Reward(RewardType.TITLE, 1);
        title.titleId = "t_x";
        assertNull(title.validateError());

        Reward card = new Reward(RewardType.EXEMPT_CARD, 1);
        assertNull(card.validateError());
    }

    @Test
    void 缺少关键字段时给出原因() {
        Reward item = new Reward(RewardType.ITEM, 1);
        assertNotNull(item.validateError());
        assertTrue(item.validateError().contains("itemId"));

        Reward command = new Reward(RewardType.COMMAND, 1);
        assertTrue(command.validateError().contains("command"));

        Reward title = new Reward(RewardType.TITLE, 1);
        assertTrue(title.validateError().contains("titleId"));
    }

    @Test
    void 数量必须为正() {
        Reward zero = new Reward(RewardType.STAR_COIN, 0);
        Reward negative = new Reward(RewardType.STAR_COIN, -3);

        assertFalse(zero.isValid());
        assertFalse(negative.isValid());
    }

    @Test
    void 未知类型被拒绝() {
        Reward reward = new Reward();
        reward.type = "GOLD";

        assertNull(reward.typeOrNull());
        assertNotNull(reward.validateError());
        assertFalse(reward.isValid());
    }

    @Test
    void 类型名大小写与空白宽容() {
        Reward reward = new Reward();
        reward.type = "  star_coin ";

        assertEquals(RewardType.STAR_COIN, reward.typeOrNull());
        assertTrue(reward.isValid());
    }

    @Test
    void 规范化会剥掉命令前导斜杠与空白() {
        Reward reward = new Reward(RewardType.COMMAND, 1);
        reward.command = "   ///give @s minecraft:diamond 1  ";
        reward.itemId = "  minecraft:diamond  ";
        reward.titleId = "  t_x  ";

        reward.normalize();

        assertEquals("give @s minecraft:diamond 1", reward.command);
        assertEquals("minecraft:diamond", reward.itemId);
        assertEquals("t_x", reward.titleId);
    }

    @Test
    void 规范化能处理null字段() {
        Reward reward = new Reward();
        reward.type = null;
        reward.itemId = null;
        reward.command = null;
        reward.titleId = null;

        reward.normalize();

        assertEquals("", reward.type);
        assertEquals("", reward.itemId);
        assertEquals("", reward.command);
        assertEquals("", reward.titleId);
        assertFalse(reward.isValid());
    }

    @Test
    void 只有物品与命令需要玩家在线() {
        assertTrue(RewardType.ITEM.requiresOnlinePlayer());
        assertTrue(RewardType.COMMAND.requiresOnlinePlayer());
        assertFalse(RewardType.STAR_COIN.requiresOnlinePlayer());
        assertFalse(RewardType.TITLE.requiresOnlinePlayer());
        assertFalse(RewardType.EXEMPT_CARD.requiresOnlinePlayer());
        assertFalse(RewardType.BATTLEPASS_XP.requiresOnlinePlayer());
    }

    @Test
    void 摘要可读且能区分类型() {
        Reward item = new Reward(RewardType.ITEM, 2);
        item.itemId = "minecraft:diamond";
        Reward coin = new Reward(RewardType.STAR_COIN, 10);
        Reward unknown = new Reward();
        unknown.type = "???";

        assertEquals("Reward(ITEM minecraft:diamond x2)", item.toString());
        assertEquals("Reward(STAR_COIN x10)", coin.toString());
        assertTrue(unknown.toString().contains("未知类型"));
    }
}
