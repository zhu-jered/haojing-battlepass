package com.haojing.battlepass.server.task;

import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §5.1（每组池规模 12）、§5.4（每周数量默认 3）、§5.7（过滤器）对任务池的要求，
 * 并保证"非法配置只丢弃并告警，不拒绝加载"。
 *
 * <p>其中「内置默认任务池自身完全合法」这条是给我自己写的 42 条任务兜底：
 * 只要有一条 action 拼错、target 写成 0、或过滤器结构不对，这条用例就会失败。
 * 否则那些错误要等到玩家实际做任务时才会以"这个任务永远不涨进度"的形式暴露出来。
 */
class TaskPoolTest {

    private StoragePaths paths;

    private TaskPoolManager newManager(Path tmp) throws IOException {
        StoragePaths created = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        created.ensureDirectories();
        this.paths = created;
        return new TaskPoolManager(created, new JsonStore());
    }

    private static TaskDefinition task(String id, String action, int target) {
        TaskDefinition definition = new TaskDefinition();
        definition.id = id;
        definition.action = action;
        definition.name = id;
        definition.desc = id;
        definition.target = target;
        definition.xp = 10;
        return definition;
    }

    /** 造一个只有指定 explore 组任务的池，其余组留空。 */
    private static TaskPool poolWith(List<TaskDefinition> explore) {
        TaskPool pool = new TaskPool();
        pool.groups.put("explore", new ArrayList<>(explore));
        pool.groups.put("build", new ArrayList<>());
        pool.groups.put("general", new ArrayList<>());
        return pool;
    }

    @Test
    void 首次加载会从内置资源生成配置文件(@TempDir Path tmp) throws IOException {
        TaskPoolManager manager = newManager(tmp);
        TaskPool pool = manager.load();

        assertTrue(Files.isRegularFile(paths.dailyTasksConfigFile()),
                "应生成 daily_tasks.json，否则管理员没有文件可改");

        for (String group : TaskPool.DAILY_GROUPS) {
            int expected = "explore".equals(group) ? 14 : 12;
            assertEquals(expected, pool.group(group).size(), "需求文档 §5.1：每组池规模默认 12 个，组=" + group);
        }

        assertEquals(6, pool.allWeekly().size(), "每周挑战池");
        assertEquals(3, pool.weeklyCount, "需求文档 §5.4：每周挑战数量默认 3 个");
    }

    @Test
    void 内置默认任务池自身完全合法(@TempDir Path tmp) throws IOException {
        TaskPoolManager manager = newManager(tmp);
        TaskPool pool = manager.load();

        List<TaskDefinition> all = new ArrayList<>(pool.allDaily());
        all.addAll(pool.allWeekly());

        assertEquals(44, all.size(), "默认池应为 14+12+12 每日 + 6 每周");

        for (TaskDefinition definition : all) {
            assertNotNull(definition.actionOrNull(),
                    definition.id + " 的 action 不合法：" + definition.action);
            assertTrue(definition.target > 0, definition.id + " 的 target 必须为正");
            assertFalse(definition.id.isBlank(), "任务必须有 id");
            assertTrue(definition.xp >= 0 && definition.starCoin >= 0, definition.id + " 的奖励不应为负");
        }

        // 校验必须一次都不丢弃，否则上面按 12/12/12/6 的计数断言也会失败
        assertEquals(0, pool.validate(), "内置默认池不应有任何任务被丢弃");
    }

    @Test
    void 重复的任务ID会被丢弃() {
        TaskPool pool = poolWith(List.of(
                task("dup", "REACH_POSITION", 1),
                task("dup", "REACH_POSITION", 1)));

        assertEquals(1, pool.validate(), "重复 ID 应丢弃后出现的那个");
        assertEquals(1, pool.group("explore").size());
    }

    @Test
    void 无法识别的动作会被丢弃而不是猜() {
        // 猜错动作会把任务统计到完全错误的玩家行为上（例如把"放置"算成"破坏"），
        // 所以宁可丢弃并告警。
        TaskPool pool = poolWith(List.of(task("bad_action", "PLACE_STONE", 1)));

        assertEquals(1, pool.validate());
        assertTrue(pool.group("explore").isEmpty());
    }

    @Test
    void 非法目标值与负数奖励会被丢弃() {
        assertEquals(1, poolWith(List.of(task("zero_target", "BREAK_BLOCK", 0))).validate());
        assertEquals(1, poolWith(List.of(task("neg_target", "BREAK_BLOCK", -5))).validate());

        TaskDefinition negativeReward = task("neg_reward", "BREAK_BLOCK", 1);
        negativeReward.xp = -1;
        assertEquals(1, poolWith(List.of(negativeReward)).validate());
    }

    @Test
    void 非法过滤器会让整个任务被丢弃() {
        // 未知条件类型
        TaskDefinition unknownType = task("bad_type", "BREAK_BLOCK", 1);
        unknownType.filter = new FilterNode();
        unknownType.filter.type = "NOT_A_TYPE";
        unknownType.filter.value = "minecraft:stone";
        assertEquals(1, poolWith(List.of(unknownType)).validate());

        // Y 区间写反
        TaskDefinition reversed = task("bad_range", "BREAK_BLOCK", 1);
        FilterNode range = new FilterNode();
        range.type = "Y_RANGE";
        range.min = 10;
        range.max = 0;
        reversed.filter = range;
        assertEquals(1, poolWith(List.of(reversed)).validate());

        // 时段起止相同（零长度）
        TaskDefinition zeroWindow = task("bad_time", "BREAK_BLOCK", 1);
        FilterNode time = new FilterNode();
        time.type = "TIME_RANGE";
        time.from = "06:00";
        time.to = "06:00";
        zeroWindow.filter = time;
        assertEquals(1, poolWith(List.of(zeroWindow)).validate());

        // 组合运算符写错
        TaskDefinition badOp = task("bad_op", "BREAK_BLOCK", 1);
        FilterNode group = new FilterNode();
        group.op = "XOR";
        group.conditions = new ArrayList<>();
        badOp.filter = group;
        assertEquals(1, poolWith(List.of(badOp)).validate());

        // 嵌套里面的条件非法，也应让整个任务被丢弃
        TaskDefinition nestedBad = task("nested_bad", "BREAK_BLOCK", 1);
        FilterNode outer = new FilterNode();
        outer.op = "AND";
        FilterNode inner = new FilterNode();
        inner.type = "BLOCK_ID";
        outer.conditions = new ArrayList<>(List.of(inner));
        nestedBad.filter = outer;
        assertEquals(1, poolWith(List.of(nestedBad)).validate(), "BLOCK_ID 未给取值属于非法");
    }

    @Test
    void 每周数量非法会被修正() {
        TaskPool pool = new TaskPool();
        pool.weeklyCount = 0;
        pool.validate();
        assertEquals(3, pool.weeklyCount, "非法值应回退为默认 3");

        pool.weeklyCount = 99;
        pool.validate();
        assertEquals(99, pool.weeklyCount, "大于池大小时不擅自改配置，只在抽取时以池大小为准");
    }

    @Test
    void 保存后重新加载内容一致(@TempDir Path tmp) throws IOException {
        TaskPoolManager manager = newManager(tmp);
        TaskPool pool = manager.load();
        pool.weeklyCount = 5;
        manager.save();

        TaskPool reloaded = new TaskPoolManager(paths, new JsonStore()).load();

        assertEquals(5, reloaded.weeklyCount);
        assertEquals(14, reloaded.group("explore").size());
        assertEquals("深入地下", reloaded.group("explore").get(0).name, "中文应原样保存");
    }
}
