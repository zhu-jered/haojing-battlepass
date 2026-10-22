package com.haojing.battlepass.server.season;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证赛季结束时的归档与重置（需求文档 §4、§16「赛季结束后星币与收藏册保留，
 * 等级/经验/任务重置」、§12「归档 data/history/season_&lt;id&gt;.json」）。
 *
 * <p>为什么既有"在线玩家走内存"又有"离线玩家走文件"的用例：这两条路径的代码是分开的
 * （§5.3 要求统一刷新、§12 禁止主线程同步 IO），只测其中一条会漏掉另一条最常见的回归 ——
 * 离线玩家明明该被重置，却因为后台流程没跑到而停在旧赛季。
 */
class SeasonResetServiceTest {

    @Test
    void 重置赛季数据会清掉等级经验任务分支与豁免卡() throws IOException {
        SeasonData season = new SeasonData("u1", "S1");
        season.level = 17;
        season.xp = 40;
        season.setBranch(Branch.HUNT);
        season.dailyXpEarned = 300;
        season.dailyRerollUsed = 1;
        season.exemptCards = 2;
        season.previousDailyTaskIds.add("old");
        season.claimedLevelRewards.add("5");
        season.dailyTasks.put("explore", new TaskProgress("t1"));
        season.weeklyTasks.put("w1", new TaskProgress("w1"));
        season.lastDailyRefreshDate = "2026-10-01";

        SeasonResetService.resetSeasonData(season, "S2");

        assertEquals("S2", season.seasonId);
        assertEquals(1, season.level, "§4：等级重置");
        assertEquals(0, season.xp, "§4：经验重置");
        assertEquals(Branch.NONE, season.branch(), "§4：分支重置（需重新选择）");
        assertEquals(0, season.dailyXpEarned);
        assertEquals(0, season.dailyRerollUsed);
        assertEquals(0, season.exemptCards, "豁免卡不在 §4 的保留清单里，随赛季清零");
        assertTrue(season.previousDailyTaskIds.isEmpty());
        assertTrue(season.claimedLevelRewards.isEmpty(), "等级奖励记录必须清空，否则新赛季不再发奖");
        assertTrue(season.dailyTasks.isEmpty(), "§4：任务重置");
        assertTrue(season.weeklyTasks.isEmpty());
        assertEquals("", season.lastDailyRefreshDate);
    }

    @Test
    void 滚动空跑时不会清掉刷新标记(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 造一个"今天已经刷过"的状态
            env.states.markDailyRefreshed("2026-10-08");
            env.states.markWeeklyRefreshed("2026-10-05");

            assertTrue(env.seasonReset.checkAndRoll(List.of()), "首次核对应启动一次滚动流程");
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertEquals("S1", env.states.state().lastSeasonId, "核对完成后应记下已核对的赛季");
            assertEquals("2026-10-08", env.states.state().lastDailyRefreshDate,
                    "没有任何玩家被重置时，不该平白作废当天任务进度");
            assertFalse(Files.exists(env.paths.historyFile("S1")), "没有玩家需要归档时不应生成空归档文件");
        }
    }

    @Test
    void 已核对过的赛季走快速路径(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.states.markSeasonRolled("S1", "", false);

            assertFalse(env.seasonReset.checkAndRoll(List.of()), "赛季 ID 与已核对一致时应直接跳过");
        }
    }

    @Test
    void 在线玩家会被归档并重置(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.level = 12;
            season.xp = 30;
            season.setBranch(Branch.BUILD);
            season.exemptCards = 1;
            season.dailyTasks.put("explore", new TaskProgress("t_exp_1"));
            env.data.global(id).starCoin = 240;
            env.data.global(id).unlockTitle("t_keep_me");
            env.data.rememberPlayerName(id, "玩家甲");
            env.states.markDailyRefreshed("2026-10-08");

            // 管理员把 season.json 的赛季 ID 推进到 S2（内存里改等价于改配置后热重载）
            env.configs.config().seasonId = "S2";

            assertTrue(env.seasonReset.checkAndRoll(List.of(id)));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertEquals(1, season.level, "在线玩家应在主线程被立即重置");
            assertEquals(0, season.exemptCards);
            assertTrue(season.dailyTasks.isEmpty());

            // §4：星币与收藏册（永久数据）必须原样保留
            assertEquals(240, env.data.global(id).starCoin, "§4：星币不随赛季重置");
            assertTrue(env.data.global(id).unlockedTitles.contains("t_keep_me"), "§4：称号库/收藏册保留");

            assertEquals("S2", env.states.state().lastSeasonId);
            assertEquals("S1", env.states.state().pendingSeasonRolledFrom, "应留下待播报的结算公告");
            assertEquals("", env.states.state().lastDailyRefreshDate, "有人被重置时必须清刷新标记，让任务重抽");

            Path archiveFile = env.paths.historyFile("S1");
            assertTrue(Files.isRegularFile(archiveFile), "§4：应归档到 data/history/season_S1.json");

            SeasonArchive archive = env.jsonStore.read(archiveFile, SeasonArchive.class, SeasonArchive::new);
            assertEquals("S1", archive.seasonId);
            assertEquals(1, archive.playerCount());
            assertEquals(12, archive.players.get(0).level);
            assertEquals("玩家甲", archive.players.get(0).playerName);
        }
    }

    @Test
    void 离线玩家的存档文件会被重置(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData offline = new SeasonData(id.toString(), "S1");
            offline.level = 9;
            offline.exemptCards = 2;
            env.jsonStore.write(env.paths.seasonFile(id), offline);

            env.configs.config().seasonId = "S2";

            assertTrue(env.seasonReset.checkAndRoll(List.of()), "没有在线玩家时也应处理离线存档");
            assertTrue(env.seasonReset.awaitIdle(8000));

            SeasonData reloaded = env.jsonStore.read(env.paths.seasonFile(id), SeasonData.class, SeasonData::new);
            assertEquals("S2", reloaded.seasonId, "离线存档必须被改成新赛季，否则下次开服还会被再重置一次");
            assertEquals(1, reloaded.level);
            assertEquals(0, reloaded.exemptCards);

            SeasonArchive archive = env.jsonStore.read(env.paths.historyFile("S1"), SeasonArchive.class, SeasonArchive::new);
            assertEquals(1, archive.playerCount());
            assertEquals(9, archive.players.get(0).level);
        }
    }

    @Test
    void 待播公告播报一次后清除(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 4;
            env.configs.config().seasonId = "S2";
            env.seasonReset.checkAndRoll(List.of(id));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertTrue(env.seasonReset.announcePendingIfAny());
            assertEquals(List.of("S1->S2"), env.announcements);
            assertEquals("", env.states.state().pendingSeasonRolledFrom, "播报后应清掉标记");

            assertFalse(env.seasonReset.announcePendingIfAny(), "没有待播公告时应返回 false");
            assertEquals(1, env.announcements.size(), "不应重复播报");
        }
    }

    @Test
    void 已在新赛季的玩家不会被重复重置(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID fresh = UUID.randomUUID();
            SeasonData season = env.data.season(fresh);
            season.level = 6;
            season.seasonId = "S2";

            env.configs.config().seasonId = "S2";
            env.seasonReset.checkAndRoll(List.of(fresh));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertEquals(6, env.data.season(fresh).level, "已经属于新赛季的玩家不该被清档");
            assertFalse(env.seasonReset.announcePendingIfAny(), "没有人被重置时不应有结算公告");
        }
    }

    @Test
    void 归档文件会被合并且不丢已有玩家(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 先手工放一份"上一次已归档"的内容
            SeasonArchive existing = new SeasonArchive();
            existing.seasonId = "S1";
            SeasonData other = new SeasonData(UUID.randomUUID().toString(), "S1");
            other.level = 3;
            existing.merge(List.of(SeasonArchive.ArchivePlayer.of(other, "早就归档的人")));
            env.jsonStore.write(env.paths.historyFile("S1"), existing);

            UUID id = UUID.randomUUID();
            env.data.season(id).level = 11;
            env.configs.config().seasonId = "S2";
            env.seasonReset.checkAndRoll(List.of(id));
            assertTrue(env.seasonReset.awaitIdle(8000));

            SeasonArchive archive = env.jsonStore.read(env.paths.historyFile("S1"), SeasonArchive.class, SeasonArchive::new);
            assertEquals(2, archive.playerCount(), "合并归档不应覆盖掉上一次的内容");
        }
    }

    @Test
    void 归档文件名会被消毒(@TempDir Path tmp) {
        assertEquals("S2", SeasonResetService.sanitize("S2"));
        assertEquals("S2_beta", SeasonResetService.sanitize("S2/beta"));
        assertEquals(".._.._x", SeasonResetService.sanitize("../../x"));
        assertEquals("unknown", SeasonResetService.sanitize(""));
        assertEquals("unknown", SeasonResetService.sanitize(null));
    }

    @Test
    void 重置后玩家赛季数据仍标记为待落盘(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 5;
            env.configs.config().seasonId = "S2";

            env.seasonReset.checkAndRoll(List.of(id));
            assertTrue(env.seasonReset.awaitIdle(8000));

            // 在线玩家的重置只改内存，必须靠脏标记落盘 —— 否则重启后等级又回来了
            env.data.flushDirty();

            SeasonData reloaded = env.jsonStore.read(env.paths.seasonFile(id), SeasonData.class, SeasonData::new);
            assertEquals("S2", reloaded.seasonId);
            assertEquals(1, reloaded.level);
        }
    }

    @Test
    void 任务完成后重置会清空进度状态(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            TaskProgress progress = new TaskProgress("t_exp_1");
            progress.setStatus(TaskStatus.CLAIMED);
            season.dailyTasks.put("explore", progress);
            season.level = 8;

            env.configs.config().seasonId = "S2";
            env.seasonReset.checkAndRoll(List.of(id));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertTrue(season.dailyTasks.isEmpty(), "旧赛季的任务进度必须清空");
        }
    }

    // ── 2026-10 全模组自查新增：赛季日程提醒（checkAndAnnounceSchedule）──

    /** 把服务端时钟固定到测试日期，避免用例依赖"跑测试当天"的真实日期。 */
    private static void freezeClock(ServerTestEnv env, String date) {
        env.seasonReset.setClock(() -> java.time.LocalDate.parse(date)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void 未配置开始日期时日程提醒直接跳过(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            freezeClock(env, "2026-10-22");
            env.configs.config().seasonStartDate = "";

            assertFalse(env.seasonReset.checkAndAnnounceSchedule(List.of(UUID.randomUUID())),
                    "没配开始日期 = 关闭日程检查，不应播报");
            assertTrue(env.scheduleAnnouncements.isEmpty());
            assertEquals("", env.states.state().lastSeasonNoticeDate);
        }
    }

    @Test
    void 临近结束时播报一次且当日不重复(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            freezeClock(env, "2026-10-22");
            // 开始 09-23 + 时长 30 → 结束日 10-22（剩 1 天），落在 3 天提醒窗口内
            env.configs.config().seasonStartDate = "2026-09-23";
            env.configs.config().durationDays = 30;
            env.configs.config().rolloverNoticeDays = 3;

            assertTrue(env.seasonReset.checkAndAnnounceSchedule(List.of(id)),
                    "剩 1 天且进入 3 天窗口，应播报");
            assertEquals(1, env.scheduleAnnouncements.size());
            assertTrue(env.scheduleAnnouncements.get(0).contains("还有 1 天结束"),
                    "播报文本应带剩余天数：" + env.scheduleAnnouncements.get(0));
            assertEquals("2026-10-22", env.states.state().lastSeasonNoticeDate,
                    "播报后应记下本业务日，供幂等去重");

            assertFalse(env.seasonReset.checkAndAnnounceSchedule(List.of(id)),
                    "同一天不应重复播报");
            assertEquals(1, env.scheduleAnnouncements.size());
        }
    }

    @Test
    void 已到期时每天播报直到赛季被推进(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            freezeClock(env, "2026-10-22");
            // 开始 09-01 + 时长 30 → 结束日 09-30，已超期 22 天
            env.configs.config().seasonStartDate = "2026-09-01";
            env.configs.config().durationDays = 30;
            env.configs.config().rolloverNoticeDays = 3;

            assertTrue(env.seasonReset.checkAndAnnounceSchedule(List.of(id)));
            assertEquals(1, env.scheduleAnnouncements.size());
            assertTrue(env.scheduleAnnouncements.get(0).contains("已到期"),
                    "到期播报文本应带到期提示：" + env.scheduleAnnouncements.get(0));
            assertFalse(env.scheduleAnnouncements.get(0).contains("还有 "),
                    "到期文案不应写成'还有 N 天'");

            // 同一天第二次调用不重复
            assertFalse(env.seasonReset.checkAndAnnounceSchedule(List.of(id)));
            assertEquals(1, env.scheduleAnnouncements.size());

            // 模拟次日：把幂等日期改回前一天，且管理员还没推进赛季 → 应再次播报
            env.states.markSeasonNoticed("2026-10-21");
            assertTrue(env.seasonReset.checkAndAnnounceSchedule(List.of(id)),
                    "跨日后应再次提醒（赛季仍未推进）");
            assertEquals(2, env.scheduleAnnouncements.size());
        }
    }

    @Test
    void 没有玩家在线时不播报也不写幂等标记(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            freezeClock(env, "2026-10-22");
            env.configs.config().seasonStartDate = "2026-09-23";
            env.configs.config().durationDays = 30;

            assertFalse(env.seasonReset.checkAndAnnounceSchedule(List.of()),
                    "无人在线时公告没人看，不应播报");
            assertEquals(0, env.scheduleAnnouncements.size());
            assertEquals("", env.states.state().lastSeasonNoticeDate,
                    "没播报就不该写幂等标记，否则在线后会错过当天的提醒");
        }
    }

    @Test
    void 距结束还远时不播报也不写幂等标记(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            freezeClock(env, "2026-10-22");
            // 开始 11-01 + 时长 30 → 结束日 11-30，距今天 40 天，远超提醒窗口
            env.configs.config().seasonStartDate = "2026-11-01";
            env.configs.config().durationDays = 30;
            env.configs.config().rolloverNoticeDays = 3;

            assertFalse(env.seasonReset.checkAndAnnounceSchedule(List.of(id)),
                    "距结束超过提醒窗口，不应播报");
            assertEquals(0, env.scheduleAnnouncements.size());
            assertEquals("", env.states.state().lastSeasonNoticeDate,
                    "没播报就不该写幂等标记");
        }
    }

    // ── 2026-10 全模组自查新增：自然月到期自动轮换（checkAndAutoRollover）──

    /** 把配置切到自然月自动轮换口径。 */
    private static void enableAutoRollover(ServerTestEnv env, String seasonId, String startDate) {
        SeasonConfig config = env.configs.config();
        config.autoRollover = true;
        config.cycleMode = SeasonSchedule.CYCLE_NATURAL;
        config.seasonId = seasonId;
        config.seasonStartDate = startDate;
    }

    @Test
    void 关闭自动轮换时到期只提醒不滚动(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            freezeClock(env, "2026-12-01");
            SeasonConfig config = env.configs.config();
            config.autoRollover = false;
            config.cycleMode = SeasonSchedule.CYCLE_NATURAL;
            config.seasonId = "S1";
            config.seasonStartDate = "2026-11-01";

            assertFalse(env.seasonReset.checkAndAutoRollover(List.of(id)),
                    "开关关闭时不自动滚动，交给管理员手动推进");
            assertEquals("S1", config.seasonId);
            assertEquals("2026-11-01", config.seasonStartDate);
            assertTrue(env.announcements.isEmpty());
        }
    }

    @Test
    void 自然月到期自动轮换到S2并归档重置且落盘(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.level = 12;
            season.xp = 30;
            season.setBranch(Branch.HUNT);
            season.dailyTasks.put("explore", new TaskProgress("t_exp_1"));
            env.data.global(id).starCoin = 240;
            env.data.rememberPlayerName(id, "玩家甲");

            freezeClock(env, "2026-12-01");
            enableAutoRollover(env, "S1", "2026-11-01");

            assertTrue(env.seasonReset.checkAndAutoRollover(List.of(id)),
                    "到期日且开关开启，应发起自动轮换");
            assertTrue(env.seasonReset.awaitIdle(8000));

            SeasonConfig config = env.configs.config();
            assertEquals("S2", config.seasonId, "赛季 ID 应按 S1 → S2 递增");
            assertEquals("2026-12-01", config.seasonStartDate, "新开始日补回本月 1 号");

            assertEquals(1, season.level, "玩家等级重置");
            assertTrue(season.dailyTasks.isEmpty(), "旧赛季任务清空");
            assertEquals(240, env.data.global(id).starCoin, "§4：星币随永久数据保留");

            assertEquals("S2", env.states.state().lastSeasonId);
            assertEquals("S1", env.states.state().pendingSeasonRolledFrom, "留下待播结算公告");
            assertTrue(Files.isRegularFile(env.paths.historyFile("S1")), "上赛季应归档");

            // 配置必须落盘：重启重读磁盘后仍是 S2 + 新开始日，避免二次启动被当成旧赛季再清一次档
            SeasonConfig onDisk = env.jsonStore.read(env.paths.seasonConfigFile(), SeasonConfig.class, SeasonConfig::new);
            assertEquals("S2", onDisk.seasonId);
            assertEquals("2026-12-01", onDisk.seasonStartDate);

            assertTrue(env.seasonReset.announcePendingIfAny());
            assertEquals(List.of("S1->S2"), env.announcements);
        }
    }

    @Test
    void 自动轮换当天重复调用不会二次滚动(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            freezeClock(env, "2026-12-01");
            enableAutoRollover(env, "S1", "2026-11-01");

            assertTrue(env.seasonReset.checkAndAutoRollover(List.of(id)));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertFalse(env.seasonReset.checkAndAutoRollover(List.of(id)),
                    "新赛季刚开始（剩整月），不应再次轮换");
            assertEquals("S2", env.configs.config().seasonId);
            assertEquals(1, env.data.season(id).level, "玩家不会被第二次重置");
        }
    }

    @Test
    void 宕机超期后开机新开始日补回月初而不是宕机当天(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 12-03 才开机（12-01 就已到期）：新赛季开始日补回 12-01，当月天数不被截短
            freezeClock(env, "2026-12-03");
            enableAutoRollover(env, "S1", "2026-11-01");

            assertTrue(env.seasonReset.checkAndAutoRollover(List.of()));
            assertTrue(env.seasonReset.awaitIdle(8000));

            SeasonConfig config = env.configs.config();
            assertEquals("2026-12-01", config.seasonStartDate, "补回 12 月 1 号，而不是 12-03");
            assertEquals("S2", config.seasonId);
        }
    }

    @Test
    void 非标准赛季ID追加月份后缀避免归档串档(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            freezeClock(env, "2026-12-01");
            enableAutoRollover(env, "beta", "2026-11-01");

            assertTrue(env.seasonReset.checkAndAutoRollover(List.of()));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertEquals("beta-2026-12", env.configs.config().seasonId,
                    "非 S+数字 的 ID 追加月份后缀，两个赛季不会共用同一归档文件名");
        }
    }

    @Test
    void 自动轮换未配置开始日期时直接跳过(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            freezeClock(env, "2026-12-01");
            SeasonConfig config = env.configs.config();
            config.autoRollover = true;
            config.cycleMode = SeasonSchedule.CYCLE_NATURAL;
            config.seasonId = "S1";
            config.seasonStartDate = "";

            assertFalse(env.seasonReset.checkAndAutoRollover(List.of()));
            assertEquals("S1", config.seasonId, "没配开始日无法判断到期时间，不滚动");
        }
    }

    @Test
    void 固定时长模式自动轮换后新开始日沿旧节奏向后推(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            freezeClock(env, "2026-10-31");
            SeasonConfig config = env.configs.config();
            config.autoRollover = true;
            config.cycleMode = SeasonSchedule.CYCLE_FIXED;
            config.seasonId = "S1";
            config.seasonStartDate = "2026-10-01";
            config.durationDays = 30;

            assertTrue(env.seasonReset.checkAndAutoRollover(List.of()));
            assertTrue(env.seasonReset.awaitIdle(8000));

            assertEquals("S2", config.seasonId);
            assertEquals("2026-10-31", config.seasonStartDate,
                    "固定时长不补月初：新开始日 = 旧开始日 + 30 天，保持 30 天一轮的节奏连续");
        }
    }

    @Test
    void 赛季ID递增规则覆盖大小写与边界() {
        ZonedDateTime dec1 = LocalDate.parse("2026-12-01").atStartOfDay(ZoneId.of("Asia/Shanghai"));

        assertEquals("S2", SeasonResetService.nextSeasonId("S1", dec1));
        assertEquals("S4", SeasonResetService.nextSeasonId("s3", dec1), "小写 s 同样识别");
        assertEquals("S11", SeasonResetService.nextSeasonId("S10", dec1), "两位数正常进位");
        assertEquals("S2", SeasonResetService.nextSeasonId("", dec1), "空 ID 兜底从 S2 起");
        assertEquals("beta-2026-12", SeasonResetService.nextSeasonId("beta", dec1));
    }
}
