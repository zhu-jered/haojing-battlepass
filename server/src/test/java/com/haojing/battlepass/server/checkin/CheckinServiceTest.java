package com.haojing.battlepass.server.checkin;

import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.support.ServerTestEnv;
import com.haojing.battlepass.server.time.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证每日签到（首次领取、同日幂等、连续连击、断签重置、里程碑去重、并发原子）。
 *
 * <p>默认配置即内置 {@code default_checkin.json}：7 天循环 + 7/14/30 天里程碑，
 * 因此断言直接对着第 1、2、7 天的具体奖励写，不依赖测试夹具另行预置签到文件。
 */
class CheckinServiceTest {

    /** 与服务端同一口径算出"昨天"业务日键，用于模拟"昨天签过"的连续场景。 */
    private static String yesterdayKey(ServerTestEnv env) {
        ZonedDateTime now = TimeUtil.now();
        return TimeUtil.businessDateKey(now.minusDays(1), env.configs.config().dailyRefresh());
    }

    @Test
    void 首次签到成功并发放第一档奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            CheckinService.CheckinResult result = env.checkinService.checkIn(id);

            assertEquals(CheckinService.Outcome.OK, result.outcome());
            assertEquals(1, result.streak());
            assertTrue(env.checkinService.isClaimedToday(id));
            assertTrue(env.sink.sawText("STAR_COIN x5"), "第 1 天发 5 京币");

            SeasonData season = env.data.season(id);
            assertEquals(1, season.signInStreak);
            assertEquals(1, season.seasonSignInCount);
        }
    }

    @Test
    void 同日重复签到被拒绝且不重复发奖(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(CheckinService.Outcome.OK, env.checkinService.checkIn(id).outcome());
            CheckinService.CheckinResult again = env.checkinService.checkIn(id);

            assertEquals(CheckinService.Outcome.ALREADY_CLAIMED, again.outcome());
            assertEquals(1, env.sink.countOf(RewardType.STAR_COIN), "第二次不应再发奖");
        }
    }

    @Test
    void 连续第二天签到连击加一发放第二档(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            // 模拟"昨天签过、昨天已是第 1 天"，今天再来应延续连击到 2。
            season.lastSignInDate = yesterdayKey(env);
            season.signInStreak = 1;

            CheckinService.CheckinResult result = env.checkinService.checkIn(id);

            assertEquals(CheckinService.Outcome.OK, result.outcome());
            assertEquals(2, result.streak());
            assertTrue(env.sink.sawText("BATTLEPASS_XP x30"), "第 2 天发 30 经验");
            assertEquals(2, season.signInStreak);
            assertEquals(1, season.seasonSignInCount, "本赛季累计只算今天这一次");
        }
    }

    @Test
    void 断签后连击重置为一(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            // 上次签到是很久以前：既不是昨天，连击应从 1 重新开始。
            season.lastSignInDate = "2020-01-01";
            season.signInStreak = 12;

            CheckinService.CheckinResult result = env.checkinService.checkIn(id);

            assertEquals(CheckinService.Outcome.OK, result.outcome());
            assertEquals(1, result.streak(), "断签后连击重置为 1");
            assertEquals(1, season.signInStreak);
        }
    }

    @Test
    void 连续第七天同时发放每日档与里程碑且去重(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            // 模拟已连续 6 天、昨天签过，今天是第 7 天。
            season.lastSignInDate = yesterdayKey(env);
            season.signInStreak = 6;

            CheckinService.CheckinResult result = env.checkinService.checkIn(id);

            assertEquals(CheckinService.Outcome.OK, result.outcome());
            assertEquals(7, result.streak());

            // 第 7 天每日档：20 京币 + 1 张任务卡；7 天里程碑：100 经验 + 10 京币。
            assertEquals(1, env.sink.countOf(RewardType.EXEMPT_CARD));
            assertTrue(env.sink.sawText("STAR_COIN x20"));
            assertTrue(env.sink.sawText("BATTLEPASS_XP x100"));
            assertTrue(env.sink.sawText("STAR_COIN x10"));
            assertTrue(season.claimedStreakMilestones.contains("7"), "里程碑去重键应已占用");
        }
    }

    @Test
    void 并发连点只成功一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            int threads = 8;
            AtomicInteger success = new AtomicInteger();
            var workers = new java.util.ArrayList<Thread>();

            for (int i = 0; i < threads; i++) {
                Thread thread = new Thread(() -> {
                    if (env.checkinService.checkIn(id).outcome() == CheckinService.Outcome.OK) {
                        success.incrementAndGet();
                    }
                });
                workers.add(thread);
                thread.start();
            }

            for (Thread thread : workers) {
                try {
                    thread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            assertEquals(1, success.get(), "同一业务日并发连点只应成功一次");
            assertEquals(1, env.sink.countFor(id), "只应发放一次奖励");
        }
    }

    @Test
    void 配置关闭时签到被拒绝且不占位(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.checkinManager.pool().enabled = false;
            UUID id = UUID.randomUUID();

            assertEquals(CheckinService.Outcome.DISABLED, env.checkinService.checkIn(id).outcome());
            assertFalse(env.checkinService.isClaimedToday(id));
            assertEquals(0, env.sink.countFor(id), "关闭状态下不应发奖");
        }
    }
}
