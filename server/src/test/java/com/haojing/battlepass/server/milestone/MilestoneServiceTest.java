package com.haojing.battlepass.server.milestone;

import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证全服里程碑（需求文档 §10：统计口径、达成判定、奖励发放对象）与
 * §13 要求的"自增计数器"。重点覆盖"达成只发一次"与两种口径、两种发放对象。
 */
class MilestoneServiceTest {

    @Test
    void 计数器累加并可按指标读取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 3);
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 4);

            assertEquals(7L, env.milestoneService.value(MilestoneMetric.BLOCKS_BROKEN));
            assertEquals(0L, env.milestoneService.value(MilestoneMetric.MOBS_KILLED));
            env.milestoneService.add(null, 5);
            env.milestoneService.add(MilestoneMetric.MOBS_KILLED, 0);
            assertEquals(0L, env.milestoneService.value(MilestoneMetric.MOBS_KILLED));
        }
    }

    @Test
    void 达到阈值即达成并给参与者发奖(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID online = UUID.randomUUID();
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 10);

            int reached = env.milestoneService.check(List.of(online));

            assertEquals(1, reached);
            assertTrue(env.milestoneService.reachedMilestones().contains("m_total"));
            assertEquals(1, env.sink.countFor(online), "§10：参与者口径只发给在线参与者");
            assertTrue(env.sink.sawText("STAR_COIN x5"));
            assertEquals(List.of("m_total|10|1"), env.milestoneAnnouncements);
        }
    }

    @Test
    void 未达阈值不发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 9);

            assertEquals(0, env.milestoneService.check(List.of(UUID.randomUUID())));
            assertTrue(env.milestoneService.reachedMilestones().isEmpty());
            assertTrue(env.sink.granted.isEmpty());
        }
    }

    @Test
    void 达成后不会重复发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID online = UUID.randomUUID();
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 100);

            assertEquals(1, env.milestoneService.check(List.of(online)));
            assertEquals(0, env.milestoneService.check(List.of(online)), "达成是一次性事件");

            assertEquals(1, env.sink.countFor(online), "奖励只发一次");
        }
    }

    @Test
    void 人均口径按玩家总数折算(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 先造出 2 名有存档的玩家，作为人均口径的分母
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            env.data.season(first).level = 2;
            env.data.season(second).level = 4;
            env.data.markSeasonDirty(first);
            env.data.markSeasonDirty(second);
            env.data.flushDirty();

            // 阈值是人均 2；总共 4 次击杀 / 2 名玩家 = 人均 2 → 达成
            env.milestoneService.add(MilestoneMetric.MOBS_KILLED, 4);

            assertEquals(1, env.milestoneService.check(List.of(first)));
            assertTrue(env.milestoneService.reachedMilestones().contains("m_percapita"));
        }
    }

    @Test
    void 全服发放对象会给离线玩家数据类奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID offline = UUID.randomUUID();
            UUID online = UUID.randomUUID();
            env.data.season(offline).level = 1;
            env.data.season(online).level = 1;
            env.data.markSeasonDirty(offline);
            env.data.markSeasonDirty(online);
            env.data.flushDirty();

            // 把 m_total 改成"全服"发放
            env.milestoneManager.table().milestone("m_total").recipients =
                    MilestoneDefinition.Recipients.ALL.name();

            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 10);
            env.milestoneService.check(List.of(online));

            assertEquals(1, env.sink.countFor(offline), "§10：全服口径应覆盖离线玩家（数据类奖励）");
            assertEquals(1, env.sink.countFor(online));
            assertTrue(env.sink.sawText("STAR_COIN x5"));
        }
    }

    @Test
    void 计数器会持久化并在重启后恢复(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.milestoneService.add(MilestoneMetric.FISH_CAUGHT, 42);
            env.milestoneService.persistNow();

            // 模拟重启：用同一份全服状态新建一个服务
            MilestoneService restarted = new MilestoneService(env.data, env.milestoneManager, env.states);
            restarted.load();

            assertEquals(42L, restarted.value(MilestoneMetric.FISH_CAUGHT));
            restarted.close();
        }
    }

    @Test
    void 已达成记录会持久化(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.milestoneService.add(MilestoneMetric.BLOCKS_BROKEN, 10);
            env.milestoneService.check(List.of(UUID.randomUUID()));

            MilestoneService restarted = new MilestoneService(env.data, env.milestoneManager, env.states);
            restarted.load();

            assertTrue(restarted.reachedMilestones().contains("m_total"), "达成记录必须持久化，否则重启后会重复发奖");
            restarted.close();
        }
    }

    @Test
    void 无法识别的历史指标会被忽略(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.states.metrics().put("OLD_METRIC_FROM_ANOTHER_VERSION", 99L);
            env.states.saveMetrics(env.states.metrics(), env.states.reachedMilestones());

            MilestoneService service = new MilestoneService(env.data, env.milestoneManager, env.states);
            service.load();

            assertEquals(0L, service.value(MilestoneMetric.BLOCKS_BROKEN), "认不出的指标不应让恢复失败");
            service.close();
        }
    }

    @Test
    void 里程碑表会丢弃非法条目(@TempDir Path tmp) throws IOException {
        MilestoneTable table = new MilestoneTable();

        MilestoneDefinition ok = new MilestoneDefinition();
        ok.id = "ok";
        ok.metric = "BLOCKS_BROKEN";
        ok.threshold = 5;
        table.milestones.add(ok);

        MilestoneDefinition badMetric = new MilestoneDefinition();
        badMetric.id = "bad_metric";
        badMetric.metric = "NOT_A_METRIC";
        table.milestones.add(badMetric);

        MilestoneDefinition duplicate = new MilestoneDefinition();
        duplicate.id = "ok";
        duplicate.metric = "MOBS_KILLED";
        table.milestones.add(duplicate);

        MilestoneDefinition disabled = new MilestoneDefinition();
        disabled.id = "disabled";
        disabled.metric = "MOBS_KILLED";
        disabled.enabled = false;
        table.milestones.add(disabled);

        MilestoneDefinition noId = new MilestoneDefinition();
        noId.metric = "TRADES";
        table.milestones.add(noId);

        MilestoneDefinition badReward = new MilestoneDefinition();
        badReward.id = "bad_reward";
        badReward.metric = "TRADES";
        badReward.rewards.add(new com.haojing.battlepass.common.data.Reward(RewardType.ITEM, 1));
        table.milestones.add(badReward);

        table.validate();

        assertEquals(2, table.size());
        assertTrue(table.milestone("ok").rewards.isEmpty());
        assertTrue(table.milestone("bad_reward").rewards.isEmpty(), "缺 itemId 的物品奖励应被丢弃");
        assertFalse(table.all().stream().anyMatch(milestone -> milestone.id.equals("disabled")));
    }

    @Test
    void 没有可用里程碑时不产生副作用(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.milestoneManager.table().milestones.clear();
            env.milestoneManager.table().validate();

            assertEquals(0, env.milestoneService.check(List.of(UUID.randomUUID())));
            assertTrue(env.sink.granted.isEmpty());
        }
    }
}
