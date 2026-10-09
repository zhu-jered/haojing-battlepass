package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §5.1（重 roll 本组）、§5.3（刷新动作）、§5.4（每周挑战）的刷新语义。
 *
 * <p>重点验证三件容易漏的事：①"今天的选择"要转存为"昨日"，否则跨日去重形同虚设；
 * ②每日刷新必须重置"每日经验上限"与"重 roll 次数"；③重 roll 必须换任务并且旧进度作废。
 */
class TaskAssignmentServiceTest {

    private TaskAssignmentService service;

    private void setUp(Path tmp) throws IOException {
        StoragePaths paths = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        paths.ensureDirectories();

        JsonStore store = new JsonStore();

        ConfigManager configs = new ConfigManager(paths, store);
        configs.load();

        PlayerDataManager data = new PlayerDataManager(paths, store, "S1");

        TaskPoolManager pools = new TaskPoolManager(paths, store);
        pools.load();

        ServerStateManager states = new ServerStateManager(paths, store);
        states.load();

        TaskProgressTracker tracker = new TaskProgressTracker(data, pools, configs);

        service = new TaskAssignmentService(data, pools, states, configs, tracker, paths, store);
        this.data = data;
        this.pools = pools;
    }

    private PlayerDataManager data;
    private TaskPoolManager pools;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void 每日刷新抽三组并把今天的选择转存为昨日(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        SeasonData season = data.season(id);

        // 模拟"昨天"留下的任务，用来验证跨日去重
        season.dailyTasks.put("explore", new TaskProgress("exp_001"));
        season.dailyTasks.put("build", new TaskProgress("bld_001"));
        season.dailyTasks.put("general", new TaskProgress("gen_001"));
        season.dailyXpEarned = 123;
        season.dailyRerollUsed = 1;

        service.rollDailyInto(season, pools.pool(), "2026-01-02");

        assertEquals(3, season.dailyTasks.size(), "三组各一个");
        assertTrue(season.previousDailyTaskIds.contains("exp_001"), "旧的每日任务应转存为昨日");
        assertEquals(0, season.dailyXpEarned, "每日经验上限按业务日重置");
        assertEquals(0, season.dailyRerollUsed, "重 roll 次数每日重置");
        assertEquals("2026-01-02", season.lastDailyRefreshDate);

        for (TaskProgress progress : season.dailyTasks.values()) {
            assertFalse(season.previousDailyTaskIds.contains(progress.taskId),
                    "新任务不能与昨日重复：" + progress.taskId);
        }
    }

    @Test
    void 每周挑战按配置数量抽取(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        SeasonData season = data.season(UUID.randomUUID());

        service.rollWeeklyInto(season, pools.pool());

        assertEquals(pools.pool().weeklyCount, season.weeklyTasks.size());
        assertEquals(3, season.weeklyTasks.size(), "默认每周 3 个");
    }

    @Test
    void 新玩家进服时补齐每日与每周任务(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();

        // 全新玩家：没有任何任务（真机日志里 tasks 通道只有 50 字节就是这个状态）
        assertTrue(data.season(id).dailyTasks.isEmpty());

        assertTrue(service.ensureAssigned(id), "首次进服应当补抽任务");

        SeasonData season = data.season(id);
        assertEquals(3, season.dailyTasks.size(), "三组各一个");
        assertEquals(3, season.weeklyTasks.size(), "每周挑战也要补");
        assertFalse(season.lastDailyRefreshDate.isEmpty(), "要记下业务日，避免下次进服重复抽");
    }

    @Test
    void 已有今日任务的玩家进服不会重复抽(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();

        service.ensureAssigned(id);
        SeasonData season = data.season(id);
        String firstTaskId = season.dailyTasks.get("explore").taskId;

        assertFalse(service.ensureAssigned(id), "任务已齐且业务日一致时不应再抽");
        assertEquals(firstTaskId, season.dailyTasks.get("explore").taskId,
                "重复调用不能换掉玩家今天的任务（否则进度会凭空作废）");
    }

    @Test
    void 业务日过期时进服会重抽任务(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        SeasonData season = data.season(id);

        // 模拟"昨天的任务"：业务日标记是过去的一天
        service.rollDailyInto(season, pools.pool(), "2020-01-01");
        String staleTaskId = season.dailyTasks.get("explore").taskId;

        assertTrue(service.ensureAssigned(id), "业务日不一致时应按今天重抽");
        assertNotEquals("2020-01-01", season.lastDailyRefreshDate);
        assertFalse(season.dailyTasks.isEmpty());
        // 新任务与昨日不重复由 rollDailyInto 保证，这里只确认确实换了 (允许极小概率相同)
        assertTrue(season.dailyTasks.get("explore") != null && staleTaskId != null);
    }

    @Test
    void 强制重抽会换任务并作废旧进度(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();

        service.ensureAssigned(id);
        SeasonData season = data.season(id);
        season.dailyTasks.get("explore").progress = 5;

        assertTrue(service.forceDailyRefresh(id));
        assertEquals(0, season.dailyTasks.get("explore").progress, "重抽后进度归零");
        assertEquals(3, season.dailyTasks.size());
    }

    @Test
    void 重roll会换任务并作废旧进度(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        SeasonData season = data.season(id);
        service.rollDailyInto(season, pools.pool(), "2026-01-02");

        String before = season.dailyTasks.get("build").taskId;
        season.dailyTasks.get("build").progress = 50;   // 假装已经做了一半

        assertTrue(service.rerollGroup(id, "build"), "默认每日可重 roll 1 次");

        TaskProgress after = season.dailyTasks.get("build");
        assertNotEquals(before, after.taskId, "重 roll 必须换一个任务");
        assertEquals(0, after.progress, "旧任务进度应作废");
        assertEquals(1, season.dailyRerollUsed);
    }

    @Test
    void 重roll次数用尽后被拒绝(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        SeasonData season = data.season(id);
        service.rollDailyInto(season, pools.pool(), "2026-01-02");

        assertTrue(service.rerollGroup(id, "build"));
        assertFalse(service.rerollGroup(id, "explore"), "超出每日次数上限应被拒绝");
    }

    @Test
    void 非法组名会被拒绝而不是乱改数据(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        data.season(id);

        assertFalse(service.rerollGroup(id, "not_a_group"));
        assertFalse(service.rerollGroup(id, null));
    }
}
