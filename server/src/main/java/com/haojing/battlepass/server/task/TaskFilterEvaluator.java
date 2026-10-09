package com.haojing.battlepass.server.task;

import com.haojing.battlepass.server.time.TimeUtil;

import java.time.LocalTime;
import java.util.List;

/**
 * 用途：判断一次玩家行为是否命中某个任务的过滤器（需求文档 §5.7：支持 AND/OR 嵌套组合）。
 *
 * <p>为什么必须是纯函数：过滤器是配置驱动的递归结构，最容易出的错是
 * "嵌套层级一深就把 AND 写成 OR"这类逻辑错误，而这类错误在游戏里表现为
 * "任务进度莫名其妙地涨/不涨"，几乎不可能靠人工观察发现。做成纯函数后用单元测试
 * 把各种嵌套组合一次性钉死，是唯一靠谱的做法。
 *
 * <p>失败方向一律取"不命中"：未知条件类型、上下文缺少所需字段、区间写反等，
 * 全部判为不匹配。宁可任务不涨进度（管理员能看出来并改配置），
 * 也绝不误计（玩家会刷出一堆不存在的进度，且难以追溯）。
 */
public final class TaskFilterEvaluator {

    private TaskFilterEvaluator() {
    }

    /**
     * 判断上下文是否满足过滤器。
     *
     * @param node    过滤器根节点；null 表示无条件通过
     * @param context 行为上下文
     * @return 是否命中
     */
    public static boolean matches(FilterNode node, TaskFilterContext context) {
        if (node == null) {
            // 没有过滤器 = 任何同类行为都计数（例如"钓鱼成功 10 次"不限群系）
            return true;
        }

        if (context == null) {
            return false;
        }

        return node.isGroup() ? matchesGroup(node, context) : matchesLeaf(node, context);
    }

    private static boolean matchesGroup(FilterNode node, TaskFilterContext context) {
        List<FilterNode> children = node.conditions;
        boolean or = "OR".equalsIgnoreCase(node.op);

        if (children == null || children.isEmpty()) {
            // 空条件组：AND 视为"无附加条件"（通过）；OR 视为"没有任何可满足项"（不通过）。
            return !or;
        }

        for (FilterNode child : children) {
            boolean childMatched = matches(child, context);

            if (or && childMatched) {
                return true;
            }

            if (!or && !childMatched) {
                return false;
            }
        }

        // AND：全部命中才会走到这里；OR：一个都没命中
        return !or;
    }

    private static boolean matchesLeaf(FilterNode node, TaskFilterContext context) {
        FilterNode.ConditionType type = FilterNode.ConditionType.fromName(node.type);

        if (type == null) {
            // 未知条件类型无法判定，按不匹配处理（配置校验阶段会告警）。
            return false;
        }

        return switch (type) {
            case BLOCK_ID -> matchesId(node, context.blockId());
            case ENTITY_ID -> matchesId(node, context.entityId());
            case BIOME -> matchesId(node, context.biomeId());
            case DIMENSION -> matchesId(node, context.dimensionId());
            case Y_RANGE -> matchesYRange(node, context.y());
            case TIME_RANGE -> matchesTimeRange(node, context.time());
        };
    }

    /** 单值/多值 ID 匹配。{@code value} 与 {@code values} 之间是"或"的关系。 */
    private static boolean matchesId(FilterNode node, String actual) {
        if (actual == null) {
            return false;
        }

        // 方块/实体/群系 ID 在配置里大小写写法不统一（minecraft:Stone / minecraft:stone），
        // 官方 ID 恒为小写，因此这里忽略大小写比较，避免管理员手抄大写就静默失效。
        if (node.value != null && !node.value.isBlank() && node.value.trim().equalsIgnoreCase(actual)) {
            return true;
        }

        if (node.values != null) {
            for (String candidate : node.values) {
                if (candidate != null && !candidate.isBlank() && candidate.trim().equalsIgnoreCase(actual)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesYRange(FilterNode node, Integer y) {
        if (y == null) {
            return false;
        }

        if (node.min == null && node.max == null) {
            return false;
        }

        if (node.min != null && y < node.min) {
            return false;
        }

        return node.max == null || y <= node.max;
    }

    private static boolean matchesTimeRange(FilterNode node, LocalTime time) {
        if (time == null) {
            return false;
        }

        if (node.from == null || node.to == null) {
            return false;
        }

        LocalTime from = TimeUtil.parseTime(node.from, null);
        LocalTime to = TimeUtil.parseTime(node.to, null);

        if (from == null || to == null) {
            return false;
        }

        return TimeUtil.isWithin(time, from, to);
    }
}
