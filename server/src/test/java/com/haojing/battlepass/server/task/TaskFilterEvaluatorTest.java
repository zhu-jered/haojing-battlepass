package com.haojing.battlepass.server.task;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §5.7 过滤器系统的全部条件维度与 AND/OR 嵌套组合。
 *
 * <p>为什么必须用测试锁死：过滤器写错的典型表现是"任务进度莫名其妙涨或不涨"，
 * 而 §5.7 允许任意深度的嵌套，人工穷举组合根本不现实。这里把每种条件的
 * 命中/不命中、以及与 null 上下文的交互都钉住，避免以后调整过滤器时悄悄改坏判定。
 */
class TaskFilterEvaluatorTest {

    private static FilterNode leaf(String type, Consumer<FilterNode> configure) {
        FilterNode node = new FilterNode();
        node.type = type;
        configure.accept(node);
        return node;
    }

    private static FilterNode group(String op, FilterNode... children) {
        FilterNode node = new FilterNode();
        node.op = op;
        // 用 Arrays.asList 而不是 List.of(children)：后者在只传一个数组时会被
        // 重载解析成 List.of(E e1)（E 推断为 FilterNode[]），得到的类型是 List<FilterNode[]>。
        node.conditions = new ArrayList<>(Arrays.asList(children));
        return node;
    }

    private static TaskFilterContext blockCtx(String blockId, String dimension, Integer y, LocalTime time) {
        return TaskFilterContext.block(blockId, dimension, y, time);
    }

    @Test
    void 没有过滤器时任何同类行为都计数() {
        assertTrue(TaskFilterEvaluator.matches(null, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "例如「钓鱼成功 10 次」不限群系，就不该写过滤器");
    }

    @Test
    void 单值方块ID命中且忽略大小写() {
        FilterNode filter = leaf("BLOCK_ID", n -> n.value = "minecraft:stone");
        TaskFilterContext ctx = blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON);

        assertTrue(TaskFilterEvaluator.matches(filter, ctx));

        FilterNode upper = leaf("BLOCK_ID", n -> n.value = "MINECRAFT:STONE");
        assertTrue(TaskFilterEvaluator.matches(upper, ctx), "管理员手抄大写不该导致静默失效");
    }

    @Test
    void 多值列表任一命中即可() {
        FilterNode filter = leaf("BLOCK_ID", n -> n.values = List.of("minecraft:oak_log", "minecraft:birch_log"));
        TaskFilterContext ctx = blockCtx("minecraft:birch_log", "minecraft:overworld", 70, LocalTime.NOON);

        assertTrue(TaskFilterEvaluator.matches(filter, ctx));

        TaskFilterContext other = blockCtx("minecraft:spruce_log", "minecraft:overworld", 70, LocalTime.NOON);
        assertFalse(TaskFilterEvaluator.matches(filter, other));
    }

    @Test
    void 上下文缺少该字段时不命中() {
        FilterNode filter = leaf("BLOCK_ID", n -> n.value = "minecraft:stone");

        // 击杀类行为没有 blockId，此时方块条件不该被"忽略后通过"
        TaskFilterContext killCtx = TaskFilterContext.entity("minecraft:zombie", "minecraft:overworld", 64, LocalTime.NOON);
        assertFalse(TaskFilterEvaluator.matches(filter, killCtx), "缺字段应保守判为不命中，避免误计");
    }

    @Test
    void AND需要全部条件满足() {
        FilterNode filter = group("AND",
                leaf("BLOCK_ID", n -> n.value = "minecraft:stone"),
                leaf("DIMENSION", n -> n.value = "minecraft:overworld"));

        assertTrue(TaskFilterEvaluator.matches(filter,
                blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)));

        assertFalse(TaskFilterEvaluator.matches(filter,
                blockCtx("minecraft:stone", "minecraft:the_nether", 64, LocalTime.NOON)),
                "维度不符时 AND 应整体不通过");
    }

    @Test
    void OR任一条件满足即可() {
        FilterNode filter = group("OR",
                leaf("BIOME", n -> n.value = "minecraft:river"),
                leaf("BIOME", n -> n.value = "minecraft:ocean"));

        assertTrue(TaskFilterEvaluator.matches(filter,
                TaskFilterContext.position("minecraft:ocean", "minecraft:overworld", 62, LocalTime.NOON)));
        assertFalse(TaskFilterEvaluator.matches(filter,
                TaskFilterContext.position("minecraft:desert", "minecraft:overworld", 62, LocalTime.NOON)));
    }

    @Test
    void 多层嵌套组合() {
        // AND( 维度=主世界 , OR( 群系=河流 , 群系=海洋 ) , Y区间 50~70 )
        FilterNode filter = group("AND",
                leaf("DIMENSION", n -> n.value = "minecraft:overworld"),
                group("OR",
                        leaf("BIOME", n -> n.value = "minecraft:river"),
                        leaf("BIOME", n -> n.value = "minecraft:ocean")),
                leaf("Y_RANGE", n -> {
                    n.min = 50;
                    n.max = 70;
                }));

        assertTrue(TaskFilterEvaluator.matches(filter,
                TaskFilterContext.position("minecraft:river", "minecraft:overworld", 62, LocalTime.NOON)));

        assertFalse(TaskFilterEvaluator.matches(filter,
                TaskFilterContext.position("minecraft:river", "minecraft:overworld", 80, LocalTime.NOON)),
                "Y 超出区间应不通过");
        assertFalse(TaskFilterEvaluator.matches(filter,
                TaskFilterContext.position("minecraft:river", "minecraft:the_nether", 62, LocalTime.NOON)),
                "维度不符应不通过");
    }

    @Test
    void Y区间为闭区间且可只给一侧() {
        FilterNode both = leaf("Y_RANGE", n -> {
            n.min = -64;
            n.max = -40;
        });
        assertTrue(TaskFilterEvaluator.matches(both, blockCtx("minecraft:stone", "minecraft:overworld", -64, LocalTime.NOON)), "下界应为闭区间");
        assertTrue(TaskFilterEvaluator.matches(both, blockCtx("minecraft:stone", "minecraft:overworld", -40, LocalTime.NOON)), "上界应为闭区间");
        assertFalse(TaskFilterEvaluator.matches(both, blockCtx("minecraft:stone", "minecraft:overworld", -39, LocalTime.NOON)));

        FilterNode onlyMax = leaf("Y_RANGE", n -> n.max = 0);
        assertTrue(TaskFilterEvaluator.matches(onlyMax, blockCtx("minecraft:stone", "minecraft:overworld", -500, LocalTime.NOON)));

        FilterNode empty = leaf("Y_RANGE", n -> {
        });
        assertFalse(TaskFilterEvaluator.matches(empty, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "两端都不给属于无效配置，应不命中而不是「任意 Y 都过」");
    }

    @Test
    void 时段条件支持同日与跨零点() {
        FilterNode sameDay = leaf("TIME_RANGE", n -> {
            n.from = "00:00";
            n.to = "06:00";
        });
        assertTrue(TaskFilterEvaluator.matches(sameDay, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.of(3, 0))));
        assertFalse(TaskFilterEvaluator.matches(sameDay, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)));

        FilterNode crossMidnight = leaf("TIME_RANGE", n -> {
            n.from = "22:00";
            n.to = "06:00";
        });
        assertTrue(TaskFilterEvaluator.matches(crossMidnight, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.of(23, 30))));
        assertTrue(TaskFilterEvaluator.matches(crossMidnight, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.of(2, 0))));
        assertFalse(TaskFilterEvaluator.matches(crossMidnight, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)));

        FilterNode malformed = leaf("TIME_RANGE", n -> n.from = "22:00");
        assertFalse(TaskFilterEvaluator.matches(malformed, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.of(23, 0))),
                "只写了 from 属于无效配置，应不命中");
    }

    @Test
    void 未知条件类型与空条件组都按保守方向处理() {
        FilterNode unknown = leaf("NOT_A_REAL_TYPE", n -> n.value = "minecraft:stone");
        assertFalse(TaskFilterEvaluator.matches(unknown, blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)));

        assertTrue(TaskFilterEvaluator.matches(group("AND"),
                blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "空的 AND 组表示无附加条件，应通过");
        assertFalse(TaskFilterEvaluator.matches(group("OR"),
                blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "空的 OR 组没有任何可满足项，应不通过");

        assertFalse(TaskFilterEvaluator.matches(leaf("BLOCK_ID", n -> {
        }), blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "BLOCK_ID 既没给 value 也没给 values，属于无效配置");

        assertFalse(TaskFilterEvaluator.matches(group("AND", new FilterNode()),
                blockCtx("minecraft:stone", "minecraft:overworld", 64, LocalTime.NOON)),
                "既不是组合节点也不是合法叶子节点时应不命中");
    }

    @Test
    void 上下文为null时不命中() {
        assertFalse(TaskFilterEvaluator.matches(leaf("BLOCK_ID", n -> n.value = "minecraft:stone"), null));
    }
}
