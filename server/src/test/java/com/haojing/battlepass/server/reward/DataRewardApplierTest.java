package com.haojing.battlepass.server.reward;

import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证"只改数据文件"的四类奖励发放（需求文档 §9：ITEM / COMMAND / BATTLEPASS_XP /
 * STAR_COIN / TITLE，本类覆盖其中四类；ITEM 与 COMMAND 属于 Minecraft 侧，
 * 见 {@link CommandWhitelistTest} 对命令白名单的覆盖）。
 *
 * <p>为什么这类测试很重要：离线玩家也能拿到这四类奖励，而这四类里有三类是"永久资产"
 * （星币、称号、战令经验），发错或重复发都会直接破坏经济与进度。
 */
class DataRewardApplierTest {

    private Reward starCoin(int amount) {
        return new Reward(RewardType.STAR_COIN, amount);
    }

    private Reward title(String titleId) {
        Reward reward = new Reward(RewardType.TITLE, 1);
        reward.titleId = titleId;
        return reward;
    }

    private Reward card(int amount) {
        return new Reward(RewardType.EXEMPT_CARD, amount);
    }

    @Test
    void 星币累加到永久数据(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            assertTrue(applier.grant(id, starCoin(30), "测试"));
            assertTrue(applier.grant(id, starCoin(12), "测试"));

            assertEquals(42, env.data.global(id).starCoin);
        }
    }

    @Test
    void 称号解锁是幂等的(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            assertTrue(applier.grant(id, title("t_one"), "测试"));
            assertTrue(applier.grant(id, title("t_one"), "测试"), "重复发放同一称号不算失败");

            assertEquals(1, env.data.global(id).unlockedTitles.size());
            assertTrue(env.data.global(id).unlockedTitles.contains("t_one"));
        }
    }

    @Test
    void 豁免卡受持有上限约束(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.configs.config().exemptCardMax = 3;
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            assertTrue(applier.grant(id, card(2), "测试"));
            assertEquals(2, env.data.season(id).exemptCards);

            // 第 3 张能进，第 4、5 张会被截断
            assertTrue(applier.grant(id, card(2), "测试"));

            assertEquals(3, env.data.season(id).exemptCards, "§5.9：每人持有上限默认 3");
        }
    }

    @Test
    void 豁免卡已满时拒绝发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.exemptCards = 3;
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            assertFalse(applier.grant(id, card(1), "测试"), "已满时应返回失败，让调用方知道没发出去");
            assertEquals(3, season.exemptCards);
        }
    }

    @Test
    void 战令经验通过注入的出口发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            List<String> calls = new ArrayList<>();
            applier.setXpSink((uuid, amount, source) -> calls.add(uuid + ":" + amount + ":" + source.name()));

            Reward xp = new Reward(RewardType.BATTLEPASS_XP, 120);
            assertTrue(applier.grant(id, xp, "测试", XpSource.SHOP));

            assertEquals(List.of(id + ":120:SHOP"), calls);
        }
    }

    @Test
    void 未注入经验出口时拒绝而不是静默吞掉(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            Reward xp = new Reward(RewardType.BATTLEPASS_XP, 120);

            assertFalse(applier.grant(id, xp, "测试"), "没有出口就不该假装发放成功");
        }
    }

    @Test
    void 非法奖励定义会被拒绝(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            Reward negative = new Reward(RewardType.STAR_COIN, -5);
            Reward unknown = new Reward();
            unknown.type = "NOT_A_TYPE";
            Reward noTitle = new Reward(RewardType.TITLE, 1);

            assertFalse(applier.grant(id, negative, "测试"));
            assertFalse(applier.grant(id, unknown, "测试"));
            assertFalse(applier.grant(id, noTitle, "测试"), "TITLE 缺少 titleId 应被拒绝");
            assertFalse(applier.grant(id, null, "测试"));

            assertEquals(0, env.data.global(id).starCoin);
        }
    }

    @Test
    void 物品与命令类型不属于本类() {
        assertFalse(DataRewardApplier.handles(RewardType.ITEM));
        assertFalse(DataRewardApplier.handles(RewardType.COMMAND));
        assertTrue(DataRewardApplier.handles(RewardType.STAR_COIN));
        assertTrue(DataRewardApplier.handles(RewardType.TITLE));
        assertTrue(DataRewardApplier.handles(RewardType.EXEMPT_CARD));
        assertTrue(DataRewardApplier.handles(RewardType.BATTLEPASS_XP));
    }

    @Test
    void 发放会标记对应文件为脏(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            DataRewardApplier applier = new DataRewardApplier(env.data, env.configs);

            applier.grant(id, starCoin(1), "测试");
            applier.grant(id, card(1), "测试");

            env.data.flushDirty();

            String globalJson = java.nio.file.Files.readString(env.paths.globalFile(id));
            String seasonJson = java.nio.file.Files.readString(env.paths.seasonFile(id));

            assertTrue(globalJson.contains("starCoin"), "星币应落在永久数据文件里");
            assertTrue(seasonJson.contains("exemptCards"), "豁免卡应落在赛季数据文件里");
            assertFalse(globalJson.contains("exemptCards"), "豁免卡不应出现在永久数据里");
        }
    }

    @Test
    void 永久数据的辅助方法可读可写(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            GlobalData global = env.data.global(id);

            assertEquals(0, global.purchaseCount("x"));
            global.recordPurchase("x");
            global.recordPurchase("x");
            assertEquals(2, global.purchaseCount("x"));
            assertEquals(0, global.purchaseCount(null));
        }
    }
}
