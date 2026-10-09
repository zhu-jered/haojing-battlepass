package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §5.8「防刷」的全部要求 —— 只统计玩家本人行为、假人不计入、
 * CLAIMED 后不再计数、同一 tick 内批量操作去重，以及 §5.5 的状态迁移。
 *
 * <p>这些规则如果写错，表现是"进度涨得莫名其妙快"或"明明做了却不涨"，两者都只能靠
 * 玩家举报才发现。用临时目录 + 内置默认任务池把它们固定下来，跑一次测试只要几百毫秒。
 */
class TaskProgressTrackerTest {

    private StoragePaths paths;
    private PlayerDataManager data;
    private TaskProgressTracker tracker;

    private void setUp(Path tmp) throws IOException {
        paths = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        paths.ensureDirectories();

        JsonStore store = new JsonStore();

        ConfigManager configs = new ConfigManager(paths, store);
        configs.load();

        data = new PlayerDataManager(paths, store, "S1");

        TaskPoolManager pools = new TaskPoolManager(paths, store);
        pools.load();   // 从内置默认任务池生成并加载

        tracker = new TaskProgressTracker(data, pools, configs);
    }

    /** 把某个默认池里的任务分配给玩家的某个每日组。 */
    private void assignDaily(UUID uuid, String group, String taskId) throws IOException {
        SeasonData season = data.season(uuid);
        season.dailyTasks.put(group, new TaskProgress(taskId));
        tracker.invalidate(uuid);
    }

    private void assignWeekly(UUID uuid, String taskId) throws IOException {
        SeasonData season = data.season(uuid);
        season.weeklyTasks.put(taskId, new TaskProgress(taskId));
        tracker.invalidate(uuid);
    }

    private static TaskFilterContext stoneContext() {
        return TaskFilterContext.block("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON);
    }

    @Test
    void 破坏石头会推进对应任务进度(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");   // 破坏 64 个石头

        int advanced = tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100);

        assertEquals(1, advanced);
        TaskProgress progress = data.season(id).dailyTasks.get("build");
        assertEquals(1, progress.progress);
        assertEquals(TaskStatus.IN_PROGRESS, progress.status(), "首次计数应把未激活提升为进行中");
    }

    @Test
    void 过滤器不匹配时不计数(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");

        TaskFilterContext dirt = TaskFilterContext.block("minecraft:dirt", "minecraft:overworld", 64, LocalTime.NOON);
        assertEquals(0, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, dirt, "1,2,3", 100));
        assertEquals(0, data.season(id).dailyTasks.get("build").progress);
    }

    @Test
    void 动作不符时不计数(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");   // BREAK_BLOCK

        // 同一份石头上下文，但动作是"放置"：不该计入破坏任务
        assertEquals(0, tracker.advance(id, "Alice", TaskAction.PLACE_BLOCK, stoneContext(), "10,64,10", 100));
    }

    @Test
    void 达到目标后状态变为完成待领取(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_011");   // 放置 4 个箱子

        TaskFilterContext chest = TaskFilterContext.block("minecraft:chest", "minecraft:overworld", 64, LocalTime.NOON);

        for (int i = 0; i < 4; i++) {
            tracker.advance(id, "Alice", TaskAction.PLACE_BLOCK, chest, "x" + i, 100 + i);
        }

        TaskProgress progress = data.season(id).dailyTasks.get("build");
        assertEquals(4, progress.progress);
        assertEquals(TaskStatus.COMPLETED, progress.status());
    }

    @Test
    void CLAIMED之后不再计数(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");

        TaskProgress progress = data.season(id).dailyTasks.get("build");
        progress.progress = 64;
        progress.setStatus(TaskStatus.CLAIMED);

        assertEquals(0, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100));
        assertEquals(64, progress.progress, "已领取的任务不该继续涨");
    }

    @Test
    void 同一tick同一坐标只计一次(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");

        assertEquals(1, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100));
        assertEquals(0, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100),
                "同一 tick 内同一坐标的重复事件应被去重");
        assertEquals(1, data.season(id).dailyTasks.get("build").progress);

        // 同一 tick 但不同坐标：是两次真实操作，应当分别计数
        assertEquals(1, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "11,64,10", 100));
        assertEquals(2, data.season(id).dailyTasks.get("build").progress);

        // 不同 tick 同一坐标：也应继续计数
        assertEquals(1, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 101));
        assertEquals(3, data.season(id).dailyTasks.get("build").progress);
    }

    @Test
    void 假人的行为完全不计数(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "build", "bld_001");

        // 默认配置的前缀是 bot_
        assertEquals(0, tracker.advance(id, "bot_farmer", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100));
        assertEquals(0, data.season(id).dailyTasks.get("build").progress);
    }

    @Test
    void 没有过滤器的任务任何同类行为都计数(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "general", "gen_007");   // FISH，无过滤器
        assignWeekly(id, "gen_008");             // FISH，要求河流群系

        // 沙漠群系钓鱼：只有"无过滤器"的那个应计数
        TaskFilterContext desert = TaskFilterContext.generic("minecraft:desert", "minecraft:overworld", 64, LocalTime.NOON);
        assertEquals(1, tracker.advance(id, "Alice", TaskAction.FISH, desert, null, 100));

        assertEquals(1, data.season(id).dailyTasks.get("general").progress);
        assertEquals(0, data.season(id).weeklyTasks.get("gen_008").progress, "群系不符的每周任务不该计数");

        // 河流群系钓鱼：两个都应计数
        TaskFilterContext river = TaskFilterContext.generic("minecraft:river", "minecraft:overworld", 64, LocalTime.NOON);
        assertEquals(2, tracker.advance(id, "Alice", TaskAction.FISH, river, null, 101));

        assertEquals(2, data.season(id).dailyTasks.get("general").progress);
        assertEquals(1, data.season(id).weeklyTasks.get("gen_008").progress);
    }

    @Test
    void 已从任务池删除的任务不会被推进(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        // 管理员把任务从池里删了，但玩家存档里还留着它
        assignDaily(id, "build", "removed_task_id");

        // 不应抛异常，也不该有任何进度
        assertEquals(0, tracker.advance(id, "Alice", TaskAction.BREAK_BLOCK, stoneContext(), "10,64,10", 100));
        assertEquals(0, data.season(id).dailyTasks.get("build").progress);
    }

    @Test
    void 位置类任务只按由假变真推进一次(@TempDir Path tmp) throws IOException {
        setUp(tmp);
        UUID id = UUID.randomUUID();
        assignDaily(id, "explore", "exp_002");   // 抵达 Y≥120（目标 1）

        TaskFilterContext high = TaskFilterContext.position("minecraft:plains", "minecraft:overworld", 130, LocalTime.NOON);

        assertTrue(tracker.advanceTask(id, "Alice", "exp_002", high, 100));
        assertEquals(1, data.season(id).dailyTasks.get("explore").progress);

        // 目标已达标：再次推进不应超过目标值（防止站着不动把进度刷爆）
        tracker.advanceTask(id, "Alice", "exp_002", high, 120);
        assertEquals(1, data.season(id).dailyTasks.get("explore").progress, "进度应封顶在目标值");

        // 条件不满足时也不该推进
        TaskFilterContext low = TaskFilterContext.position("minecraft:plains", "minecraft:overworld", 60, LocalTime.NOON);
        assertFalse(tracker.advanceTask(id, "Alice", "exp_002", low, 140));
    }
}
