package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §5.1（每日三组各抽 1、同日不重复、与昨日不重复）与 §5.4（每周抽取数量）。
 *
 * <p>为什么必须用固定种子的 Random：抽取规则的错误（例如漏掉"排除昨日"）在真机上表现为
 * "偶尔抽到重复任务"，靠人工观察几乎不可能稳定复现。固定种子后，同一份池子的抽取结果完全确定，
 * 规则写错就必然失败。
 */
class TaskSelectorTest {

    private static TaskDefinition task(String id) {
        TaskDefinition definition = new TaskDefinition();
        definition.id = id;
        definition.action = "REACH_POSITION";
        definition.name = id;
        definition.target = 1;
        return definition;
    }

    private static List<TaskDefinition> tasks(String prefix, int count) {
        List<TaskDefinition> list = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            list.add(task(prefix + i));
        }

        return list;
    }

    private static TaskPool poolOf(int explore, int build, int general, int weekly) {
        TaskPool pool = new TaskPool();
        pool.groups.put("explore", tasks("e", explore));
        pool.groups.put("build", tasks("b", build));
        pool.groups.put("general", tasks("g", general));
        pool.weekly = tasks("w", weekly);
        return pool;
    }

    @Test
    void pickOne会排除指定任务() {
        List<TaskDefinition> pool = tasks("t", 3);
        Set<String> excluded = Set.of("t0", "t1");

        for (int seed = 0; seed < 20; seed++) {
            TaskDefinition picked = TaskSelector.pickOne(pool, excluded, new Random(seed));
            assertEquals("t2", picked.id, "只剩 t2 可选");
        }
    }

    @Test
    void 候选被排空时返回null而不是硬给一个() {
        List<TaskDefinition> pool = tasks("t", 2);
        assertNull(TaskSelector.pickOne(pool, Set.of("t0", "t1"), new Random(1)));
    }

    @Test
    void pickMany取指定数量且互不重复() {
        List<TaskDefinition> pool = tasks("t", 10);

        for (int seed = 0; seed < 20; seed++) {
            List<TaskDefinition> picked = TaskSelector.pickMany(pool, 3, Set.of(), new Random(seed));

            assertEquals(3, picked.size());
            assertEquals(3, new HashSet<>(picked.stream().map(t -> t.id).toList()).size(), "不应重复");
        }
    }

    @Test
    void pickMany数量超过池大小时返回全部() {
        List<TaskDefinition> picked = TaskSelector.pickMany(tasks("t", 2), 5, Set.of(), new Random(1));
        assertEquals(2, picked.size());
    }

    @Test
    void rollDaily每组各抽一个且不与昨日重复() {
        TaskPool pool = poolOf(12, 12, 12, 6);
        Set<String> yesterday = Set.of("e0", "b0", "g0");

        Map<String, TaskDefinition> rolled = TaskSelector.rollDaily(pool, yesterday, new Random(42));

        assertEquals(3, rolled.size(), "三组各一个");
        assertTrue(rolled.containsKey("explore"));
        assertTrue(rolled.containsKey("build"));
        assertTrue(rolled.containsKey("general"));

        for (TaskDefinition picked : rolled.values()) {
            assertNotNull(picked);
            assertFalse(yesterday.contains(picked.id), "不能抽到昨日的任务：" + picked.id);
        }

        // 三组之间也不能重复（任务 ID 全局唯一，抽一次就不会再被另一组选中）
        assertEquals(3, new HashSet<>(rolled.values().stream().map(t -> t.id).toList()).size());
    }

    @Test
    void 某组被排空时该组缺失但不影响其他组() {
        TaskPool pool = poolOf(2, 12, 12, 6);
        // 把 explore 组两个任务全部排除
        Set<String> excluded = Set.of("e0", "e1");

        Map<String, TaskDefinition> rolled = TaskSelector.rollDaily(pool, excluded, new Random(7));

        assertFalse(rolled.containsKey("explore"), "该组无候选时不应硬塞");
        assertEquals(2, rolled.size(), "其余两组照常抽出");
        assertTrue(rolled.containsKey("build"));
        assertTrue(rolled.containsKey("general"));
    }

    @Test
    void dailyExclusion同时包含昨日与当前任务() {
        SeasonData season = new SeasonData("uuid", "S1");
        season.previousDailyTaskIds = new ArrayList<>(List.of("yesterday_a", "yesterday_b"));
        season.dailyTasks.put("explore", new TaskProgress("today_explore"));
        season.dailyTasks.put("build", new TaskProgress("today_build"));

        Set<String> excluded = TaskSelector.dailyExclusion(season);

        assertEquals(4, excluded.size());
        assertTrue(excluded.contains("yesterday_a"));
        assertTrue(excluded.contains("yesterday_b"));
        assertTrue(excluded.contains("today_explore"));
        assertTrue(excluded.contains("today_build"));
    }

    @Test
    void currentDailyIds返回当前任务供转存为昨日() {
        SeasonData season = new SeasonData("uuid", "S1");
        season.dailyTasks.put("explore", new TaskProgress("a"));
        season.dailyTasks.put("build", new TaskProgress("b"));

        List<String> ids = TaskSelector.currentDailyIds(season);

        assertEquals(2, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }

    @Test
    void 周日到周六都归到同一个周一() {
        // 需求文档 §5.4：每周挑战默认每周一 06:00 刷新，因此"周键"必须按周一对齐。
        assertEquals("2026-01-05", TaskAssignmentService.weekStartKey("2026-01-05"), "周一");
        assertEquals("2026-01-05", TaskAssignmentService.weekStartKey("2026-01-07"), "周三");
        assertEquals("2026-01-05", TaskAssignmentService.weekStartKey("2026-01-11"), "周日");
        assertEquals("2026-01-12", TaskAssignmentService.weekStartKey("2026-01-12"), "下一个周一");
    }
}
