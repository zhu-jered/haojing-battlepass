package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证阶段 5 的核心业务规则 —— 需求文档 §4（升级与每级 +10 星币、分支锁定）、
 * §5.9（豁免卡）、§5.10（每日经验上限）、§5.11（长夜经验减半）、§16（同一奖励只发一次）。
 *
 * <p>这些用例之所以能毫秒级跑完，是因为 BattlePassService 与 Minecraft 完全解耦：
 * 倍率走 {@link XpMultiplierProvider}，发奖走 {@link com.haojing.battlepass.server.reward.RewardSink}。
 * 于是"半夜长夜期间经验是否真的减半""30 级满级后经验怎么办"这类问题
 * 不需要开服、不需要等到特定时刻就能验证。
 */
class BattlePassServiceTest {

    private SeasonConfig config(ServerTestEnv env) {
        return env.configs.config();
    }

    /** 把某个每日任务组做成"已完成待领取"的状态。 */
    private void completeDailyTask(ServerTestEnv env, UUID id, String group, String taskId) {
        SeasonData season = env.data.season(id);
        TaskProgress progress = new TaskProgress(taskId);
        progress.progress = 2;
        progress.setStatus(TaskStatus.COMPLETED);
        season.dailyTasks.put(group, progress);
    }

    @Test
    void 升级发放星币并结算等级奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            XpGrantResult result = env.battlePass.addXp(id, 60, XpSource.DAILY_TASK);

            assertEquals(1, result.levelBefore());
            assertEquals(2, result.levelAfter());
            assertEquals(60, result.granted());
            assertEquals(10, result.starCoinGained(), "§4：每升 1 级 +10 星币");
            // 注意：测试环境里的奖励出口只记录不落库，因此这里断言的是"升级星币"，
            // 等级 2 的那条 STAR_COIN ×10 只体现在 sink 的记录里（下一条断言）。
            assertEquals(10, env.data.global(id).starCoin, "升级星币应立即计入永久数据");
            assertTrue(env.sink.sawText("STAR_COIN"), "等级奖励应通过奖励出口发放");
        }
    }

    @Test
    void 一次给足经验可连升多级(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            // 升到 7 级累计需要 50+60+70+80+90+100 = 450，还剩 50 点留在 7 级内
            XpGrantResult result = env.battlePass.addXp(id, 500, XpSource.WEEKLY_TASK);

            assertEquals(7, result.levelAfter());
            assertEquals(60, result.starCoinGained(), "连升 6 级应得 6×10 星币");
            assertEquals(50, env.data.season(id).xp, "多余经验应留在当前等级内");
        }
    }

    @Test
    void 每日经验上限会截断部分发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 100;

            XpGrantResult first = env.battlePass.addXp(id, 80, XpSource.DAILY_TASK);
            assertEquals(80, first.granted());
            assertFalse(first.cappedByDailyLimit());

            XpGrantResult second = env.battlePass.addXp(id, 50, XpSource.DAILY_TASK);
            assertEquals(20, second.granted(), "§5.10：超限部分不再发放，但上限内的仍要发");
            assertTrue(second.cappedByDailyLimit());
            assertEquals(100, env.data.season(id).dailyXpEarned);
        }
    }

    @Test
    void 上限用尽后不再发经验(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 50;
            env.battlePass.addXp(id, 50, XpSource.DAILY_TASK);

            XpGrantResult result = env.battlePass.addXp(id, 30, XpSource.DAILY_TASK);

            assertEquals(0, result.granted());
            assertTrue(result.cappedByDailyLimit());
            assertTrue(result.grantedNothing());
        }
    }

    @Test
    void 每日上限为0表示不限制(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 0;

            XpGrantResult result = env.battlePass.addXp(id, 5000, XpSource.ADMIN);

            assertEquals(5000, result.granted());
            assertFalse(result.cappedByDailyLimit());
            assertEquals(-1, env.battlePass.remainingDailyXp(id), "0 表示不限，用 -1 表达");
        }
    }

    @Test
    void 长夜倍率按玩家生效(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID normal = UUID.randomUUID();
            UUID admin = UUID.randomUUID();

            // 用假的倍率出口模拟"长夜中：普通玩家 0.5、白名单管理员 1.0"（D-6）。
            BattlePassService service = new BattlePassService(env.data, env.configs, env.pools, env.rewards,
                    uuid -> uuid.equals(admin) ? 1.0D : 0.5D);
            service.setRewardSink(env.sink);

            XpGrantResult normalResult = service.addXp(normal, 25, XpSource.DAILY_TASK);
            XpGrantResult adminResult = service.addXp(admin, 25, XpSource.DAILY_TASK);

            assertEquals(13, normalResult.granted(), "25 × 0.5 = 12.5，四舍五入为 13");
            assertEquals(0.5D, normalResult.multiplier(), 1e-9D);
            assertEquals(25, adminResult.granted(), "白名单管理员不受长夜减半影响");
            assertEquals(1.0D, adminResult.multiplier(), 1e-9D);
        }
    }

    @Test
    void 每日上限统计的是减半后的经验(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 100;

            BattlePassService service = new BattlePassService(env.data, env.configs, env.pools, env.rewards,
                    uuid -> 0.5D);
            service.setRewardSink(env.sink);

            service.addXp(id, 60, XpSource.DAILY_TASK);

            assertEquals(30, env.data.season(id).dailyXpEarned, "记入上限的应是真正进入经验条的经验");
        }
    }

    @Test
    void 满级后不再发放经验(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.level = 30;
            season.xp = 10;

            XpGrantResult result = env.battlePass.addXp(id, 9999, XpSource.DAILY_TASK);

            assertTrue(result.maxLevelReached());
            assertEquals(0, result.granted());
            assertEquals(30, season.level);
            assertEquals(0, season.dailyXpEarned, "满级后没有发放，就不该占用每日额度");
        }
    }

    @Test
    void 管理员发放可绕过每日上限(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 10;

            XpGrantResult result = env.battlePass.addXp(id, 200, XpSource.ADMIN, true);

            assertEquals(200, result.granted());
            assertFalse(result.cappedByDailyLimit());
        }
    }

    @Test
    void 剩余可获取经验随发放递减(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).dailyXpCap = 500;

            assertEquals(500, env.battlePass.remainingDailyXp(id));
            env.battlePass.addXp(id, 120, XpSource.DAILY_TASK);
            assertEquals(380, env.battlePass.remainingDailyXp(id));
        }
    }

    @Test
    void 经验参数非法时不产生副作用(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            XpGrantResult zero = env.battlePass.addXp(id, 0, XpSource.DAILY_TASK);
            XpGrantResult negative = env.battlePass.addXp(id, -50, XpSource.DAILY_TASK);

            assertEquals(0, zero.granted());
            assertEquals(0, negative.granted());
            assertEquals(0, env.data.season(id).dailyXpEarned);
        }
    }

    @Test
    void 等级奖励只发一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 3;

            env.battlePass.grantDueLevelRewards(id);
            int afterFirst = env.sink.granted.size();

            env.battlePass.grantDueLevelRewards(id);

            assertEquals(afterFirst, env.sink.granted.size(), "§16：同一奖励重复结算只发一次");
            assertTrue(env.data.season(id).isLevelRewardClaimed(3, null));
        }
    }

    @Test
    void 分支未达等级时不可选择(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 5;

            assertEquals(BattlePassService.BranchChooseOutcome.LEVEL_TOO_LOW,
                    env.battlePass.chooseBranch(id, Branch.HUNT));
            assertFalse(env.data.season(id).hasChosenBranch());
        }
    }

    @Test
    void 分支选定后本赛季不可更换(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 10;

            assertEquals(BattlePassService.BranchChooseOutcome.OK, env.battlePass.chooseBranch(id, Branch.HUNT));
            assertEquals(Branch.HUNT, env.data.season(id).branch());
            assertEquals(BattlePassService.BranchChooseOutcome.ALREADY_CHOSEN,
                    env.battlePass.chooseBranch(id, Branch.BUILD), "§4：本赛季锁定");
            assertEquals(Branch.HUNT, env.data.season(id).branch());
        }
    }

    @Test
    void 不能选择空分支(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 15;

            assertEquals(BattlePassService.BranchChooseOutcome.INVALID_BRANCH,
                    env.battlePass.chooseBranch(id, Branch.NONE));
        }
    }

    @Test
    void 补选分支会补发已达等级的分支奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).branchUnlockLevel = 3;
            env.data.season(id).level = 4;

            assertEquals(BattlePassService.BranchChooseOutcome.OK, env.battlePass.chooseBranch(id, Branch.HUNT));

            assertTrue(env.sink.sawText("STAR_COIN x7"), "等级 4 的狩猎分支奖励应被补发");
        }
    }

    @Test
    void 管理员改分支会补发新分支奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            config(env).branchUnlockLevel = 3;
            env.data.season(id).level = 4;
            env.battlePass.chooseBranch(id, Branch.HUNT);

            assertTrue(env.battlePass.adminSetBranch(id, Branch.BUILD));

            assertEquals(Branch.BUILD, env.data.season(id).branch());
            assertTrue(env.sink.sawText("STAR_COIN x9"), "改分支后应补发建造分支的奖励");
            assertFalse(env.battlePass.adminSetBranch(id, Branch.BUILD), "重复改成同一分支应返回 false");
        }
    }

    @Test
    void 未完成的任务不能领取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            TaskProgress progress = new TaskProgress("t_exp_1");
            progress.setStatus(TaskStatus.IN_PROGRESS);
            season.dailyTasks.put("explore", progress);

            assertEquals(BattlePassService.ClaimOutcome.NOT_COMPLETED,
                    env.battlePass.claimTaskReward(id, "t_exp_1").outcome());
        }
    }

    @Test
    void 不存在的任务无法领取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(BattlePassService.ClaimOutcome.TASK_NOT_FOUND,
                    env.battlePass.claimTaskReward(id, "t_not_in_pool").outcome());
            assertEquals(BattlePassService.ClaimOutcome.TASK_NOT_FOUND,
                    env.battlePass.claimTaskReward(id, null).outcome());
        }
    }

    @Test
    void 每日任务领取发经验并且只发一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            completeDailyTask(env, id, "explore", "t_exp_1");

            BattlePassService.ClaimResult first = env.battlePass.claimTaskReward(id, "t_exp_1");

            assertEquals(BattlePassService.ClaimOutcome.OK, first.outcome());
            assertNotNull(first.xpResult());
            assertEquals(30, first.xpResult().granted());
            assertEquals(TaskStatus.CLAIMED, env.data.season(id).dailyTasks.get("explore").status());

            BattlePassService.ClaimResult second = env.battlePass.claimTaskReward(id, "t_exp_1");

            assertEquals(BattlePassService.ClaimOutcome.ALREADY_CLAIMED, second.outcome(), "§16：重复领取只发一次");
            assertEquals(30, env.data.season(id).dailyXpEarned, "第二次领取不应再加经验");
        }
    }

    @Test
    void 每周挑战领取同时给经验与星币(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            TaskProgress progress = new TaskProgress("t_weekly_1");
            progress.setStatus(TaskStatus.COMPLETED);
            season.weeklyTasks.put("t_weekly_1", progress);

            BattlePassService.ClaimResult result = env.battlePass.claimTaskReward(id, "t_weekly_1");

            assertEquals(BattlePassService.ClaimOutcome.OK, result.outcome());
            assertEquals(200, result.xpResult().granted());
            assertEquals(5, result.starCoinGained(), "每周挑战自带的星币");
            // 200 点经验让玩家从 1 级升到 4 级（50+60+70=180），因此另有 3×10 星币的升级奖励。
            assertEquals(35, env.data.global(id).starCoin, "升级星币 + 任务星币");
            assertEquals(4, result.xpResult().levelAfter());
        }
    }

    @Test
    void 豁免卡能把未完成的任务直接置为已领取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.exemptCards = 1;
            season.dailyTasks.put("explore", new TaskProgress("t_exp_1"));

            assertEquals(BattlePassService.ExemptCardOutcome.OK, env.battlePass.useExemptCard(id, "explore"));

            assertEquals(TaskStatus.CLAIMED, season.dailyTasks.get("explore").status());
            assertEquals(0, season.exemptCards, "使用后应扣掉一张");
            assertEquals(0, season.dailyXpEarned, "§5.9：使用豁免卡视为已领取，不重复发奖");
            assertTrue(env.sink.granted.isEmpty());
        }
    }

    @Test
    void 没有豁免卡时无法使用(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).dailyTasks.put("explore", new TaskProgress("t_exp_1"));

            assertEquals(BattlePassService.ExemptCardOutcome.NO_CARD,
                    env.battlePass.useExemptCard(id, "explore"));
        }
    }

    @Test
    void 已完成的任务不该浪费豁免卡(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            season.exemptCards = 2;
            completeDailyTask(env, id, "explore", "t_exp_1");

            assertEquals(BattlePassService.ExemptCardOutcome.TASK_ALREADY_COMPLETED,
                    env.battlePass.useExemptCard(id, "explore"));
            assertEquals(2, season.exemptCards, "被拒绝时不应扣卡");
            assertEquals(TaskStatus.COMPLETED, season.dailyTasks.get("explore").status());
        }
    }

    @Test
    void 组名非法或任务缺失时拒绝使用豁免卡(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).exemptCards = 3;

            assertEquals(BattlePassService.ExemptCardOutcome.INVALID_GROUP,
                    env.battlePass.useExemptCard(id, "not_a_group"));
            assertEquals(BattlePassService.ExemptCardOutcome.TASK_NOT_FOUND,
                    env.battlePass.useExemptCard(id, "explore"), "该组还没有任务");
            assertEquals(3, env.data.season(id).exemptCards);
        }
    }

    @Test
    void 未接入奖励出口时等级奖励不会被静默吞掉(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 3;

            // 造一个没有注入出口的服务：它必须什么都不发，也不能把奖励记成已发。
            BattlePassService bare = new BattlePassService(env.data, env.configs, env.pools, env.rewards,
                    XpMultiplierProvider.alwaysOne());
            bare.grantDueLevelRewards(id);

            assertFalse(env.data.season(id).isLevelRewardClaimed(3, null), "没发出去就不该登记为已发");
            assertTrue(env.sink.granted.isEmpty());
        }
    }

    @Test
    void 升级奖励的键按分支区分(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            config(env).branchUnlockLevel = 3;
            season.level = 4;

            season.markLevelRewardClaimed(4, Branch.HUNT);

            assertTrue(season.isLevelRewardClaimed(4, Branch.HUNT));
            assertFalse(season.isLevelRewardClaimed(4, Branch.BUILD), "另一分支的奖励不应被视为已发");
            assertFalse(season.isLevelRewardClaimed(4, null), "通用奖励与分支奖励是两笔");
        }
    }

    @Test
    void 奖励出口收到的奖励定义与配置一致(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.season(id).level = 3;

            env.battlePass.grantDueLevelRewards(id);

            assertEquals(1, env.sink.countOf(RewardType.TITLE));
            assertEquals(Branch.NONE, env.data.season(id).branch());
        }
    }
}
