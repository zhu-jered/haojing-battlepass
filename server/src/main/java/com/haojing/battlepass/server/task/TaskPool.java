package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：任务池，对应 config/haojing_battlepass/daily_tasks.json。
 * 需求文档 §5.1：每日任务从池中抽取 3 个，探险组 / 建造组 / 综合组各 1 个，池规模默认每组 12 个。
 * §5.4：每周挑战数量默认 3 个。
 *
 * <p>为什么校验采取"丢弃非法任务 + 告警"而不是拒绝加载：一条写错的任务不该让整池都不能用。
 * 但每条丢弃都必须留下 WARN，否则就成了静默失效 —— 管理员会发现"任务池少了一个"却找不到原因。
 *
 * <p>为什么每周挑战池也放在这个文件里：需求文档 §18 只给了 {@code groups} 结构，
 * 没有规定每周挑战池的位置。放在同一文件里可以让管理员一处分清两类任务，
 * 且刷新逻辑能共用同一套热重载。此扩展登记在 docs/需求偏差记录.md 的 D-9。
 */
public class TaskPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 每日任务的三个组。需求文档 §5.1 明确分组方式，因此不允许改成别的组名。 */
    public static final List<String> DAILY_GROUPS = List.of("explore", "build", "general");

    private static final int DEFAULT_WEEKLY_COUNT = 3;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 每周挑战抽取数量。需求文档 §5.4 默认 3 个。 */
    public int weeklyCount = DEFAULT_WEEKLY_COUNT;

    /** 每日任务池，键为组名（explore / build / general）。 */
    public Map<String, List<TaskDefinition>> groups = new LinkedHashMap<>();

    /** 每周挑战池。 */
    public List<TaskDefinition> weekly = new ArrayList<>();

    /** @param group 组名 @return 该组的任务列表（永不为 null）。 */
    public List<TaskDefinition> group(String group) {
        List<TaskDefinition> list = groups == null ? null : groups.get(group);
        return list == null ? List.of() : list;
    }

    /** @return 全部每日任务（三个组的并集，去掉 null 项）。 */
    public List<TaskDefinition> allDaily() {
        List<TaskDefinition> all = new ArrayList<>();

        for (String group : DAILY_GROUPS) {
            all.addAll(group(group));
        }

        return all;
    }

    /** @return 全部每周任务（去掉 null 项）。 */
    public List<TaskDefinition> allWeekly() {
        List<TaskDefinition> all = new ArrayList<>();

        if (weekly != null) {
            for (TaskDefinition task : weekly) {
                if (task != null) {
                    all.add(task);
                }
            }
        }

        return all;
    }

    /** @return 按任务 ID 索引的全部任务（每日 + 每周），用于按存档里的进度反查任务定义。 */
    public Map<String, TaskDefinition> indexById() {
        Map<String, TaskDefinition> index = new HashMap<>();

        for (TaskDefinition task : allDaily()) {
            index.put(task.id, task);
        }

        for (TaskDefinition task : allWeekly()) {
            index.put(task.id, task);
        }

        return index;
    }

    /** @return 每日池总规模。 */
    public int dailyPoolSize() {
        return allDaily().size();
    }

    /** @return 任务总数（每日 + 每周）。 */
    public int totalSize() {
        return dailyPoolSize() + allWeekly().size();
    }

    /**
     * 校验并就地修正任务池：丢弃非法任务、修正非法数量、补齐缺失的组。
     *
     * @return 被丢弃的任务数量（0 表示配置完全合法）
     */
    public int validate() {
        int dropped = 0;

        if (groups == null) {
            groups = new LinkedHashMap<>();
        }

        // 补齐缺失的组：宁可有一个空组（表现为当天该组抽不出任务并告警），
        // 也不要因为 NPE 让整个任务系统挂掉。
        for (String group : DAILY_GROUPS) {
            groups.computeIfAbsent(group, key -> new ArrayList<>());
        }

        // 任务 ID 在整个池内必须唯一：玩家存档里的进度是按 ID 记录的，
        // 一旦重名就会出现"两个任务共享同一份进度"，且无法判断该算给谁。
        Map<String, String> idOwner = new HashMap<>();
        int[] counter = {0};

        for (String group : DAILY_GROUPS) {
            dropped += pruneList(groups.get(group), "每日任务组 " + group, idOwner, counter);
        }

        if (weekly == null) {
            weekly = new ArrayList<>();
        }

        dropped += pruneList(weekly, "每周挑战", idOwner, counter);

        if (weeklyCount < 1) {
            LOGGER.warn("{} 任务池 weeklyCount={} 非法，已回退为 {}", ModConstants.LOG_PREFIX, weeklyCount, DEFAULT_WEEKLY_COUNT);
            weeklyCount = DEFAULT_WEEKLY_COUNT;
        }

        if (weeklyCount > weekly.size()) {
            LOGGER.warn("{} 任务池每周挑战只有 {} 个，但 weeklyCount={}，实际抽取数量会以池大小为准",
                    ModConstants.LOG_PREFIX, weekly.size(), weeklyCount);
        }

        if (dropped > 0) {
            LOGGER.warn("{} 任务池校验完成：共丢弃 {} 个非法任务（原因见上面的 WARN），剩余 {} 个",
                    ModConstants.LOG_PREFIX, dropped, totalSize());
        }

        return dropped;
    }

    /**
     * 逐条校验并剔除非法任务。
     *
     * @param list    待校验列表（会被就地修改）
     * @param where   日志用的位置描述
     * @param idOwner 已占用的任务 ID → 位置
     * @param counter 单元素数组，用于生成稳定的序号（避免 lambda 捕获可变变量）
     * @return 丢弃数量
     */
    private int pruneList(List<TaskDefinition> list, String where, Map<String, String> idOwner, int[] counter) {
        if (list == null) {
            return 0;
        }

        int dropped = 0;
        List<TaskDefinition> kept = new ArrayList<>(list.size());

        for (TaskDefinition task : list) {
            counter[0]++;

            if (task == null) {
                dropped++;
                continue;
            }

            String reason = rejectReason(task, where, counter[0], idOwner);

            if (reason != null) {
                LOGGER.warn("{} 丢弃任务（{}）：{}", ModConstants.LOG_PREFIX, where, reason);
                dropped++;
                continue;
            }

            idOwner.put(task.id, where);
            kept.add(task);
        }

        list.clear();
        list.addAll(kept);
        return dropped;
    }

    /** @return 该任务被拒绝的原因；合法时返回 null。 */
    private String rejectReason(TaskDefinition task, String where, int index, Map<String, String> idOwner) {
        if (task.id == null || task.id.isBlank()) {
            return where + " 第 " + index + " 项缺少 id";
        }

        String owner = idOwner.get(task.id);

        if (owner != null) {
            return "任务 ID 重复：" + task.id + "（已被 " + owner + " 使用）";
        }

        if (task.actionOrNull() == null) {
            return "任务 " + task.id + " 的 action 无法识别：" + task.action
                    + "（合法取值见 TaskAction）";
        }

        if (!task.hasValidTarget()) {
            return "任务 " + task.id + " 的 target=" + task.target + " 必须为正数";
        }

        if (task.xp < 0 || task.starCoin < 0) {
            return "任务 " + task.id + " 的奖励为负数（xp=" + task.xp + ", starCoin=" + task.starCoin + "）";
        }

        String filterProblem = filterProblem(task.filter, task.id, "filter");

        if (filterProblem != null) {
            return filterProblem;
        }

        return null;
    }

    /**
     * 递归校验过滤器结构。
     *
     * <p>为什么必须提前校验而不是等运行时不匹配就算了：非法过滤器（例如写错条件名）
     * 在运行时的表现是"这个任务永远不涨进度"，玩家会以为任务坏了，
     * 而管理员从日志里看不到任何线索。在这里直接丢弃并告警，问题立刻可见。
     *
     * @return 问题描述；合法时返回 null
     */
    private String filterProblem(FilterNode node, String taskId, String path) {
        if (node == null) {
            return null;
        }

        if (node.isGroup()) {
            if (!"AND".equalsIgnoreCase(node.op) && !"OR".equalsIgnoreCase(node.op)) {
                return "任务 " + taskId + " 的 " + path + " 运算符非法：" + node.op + "（只能是 AND 或 OR）";
            }

            if (node.conditions != null) {
                for (int i = 0; i < node.conditions.size(); i++) {
                    String problem = filterProblem(node.conditions.get(i), taskId, path + ".conditions[" + i + "]");

                    if (problem != null) {
                        return problem;
                    }
                }
            }

            return null;
        }

        FilterNode.ConditionType type = FilterNode.ConditionType.fromName(node.type);

        if (type == null) {
            return "任务 " + taskId + " 的 " + path + " 条件类型非法：" + node.type;
        }

        return switch (type) {
            case BLOCK_ID, ENTITY_ID, BIOME, DIMENSION -> {
                boolean hasSingle = node.value != null && !node.value.isBlank();
                boolean hasList = node.values != null && !node.values.isEmpty();

                if (!hasSingle && !hasList) {
                    yield "任务 " + taskId + " 的 " + path + "（" + type + "）既没给 value 也没给 values";
                }

                yield null;
            }
            case Y_RANGE -> {
                if (node.min == null && node.max == null) {
                    yield "任务 " + taskId + " 的 " + path + "（Y_RANGE）两端都未给出";
                }

                if (node.min != null && node.max != null && node.min > node.max) {
                    yield "任务 " + taskId + " 的 " + path + "（Y_RANGE）区间写反了：" + node.min + " > " + node.max;
                }

                yield null;
            }
            case TIME_RANGE -> {
                if (node.from == null || node.to == null) {
                    yield "任务 " + taskId + " 的 " + path + "（TIME_RANGE）缺少 from 或 to";
                }

                LocalTime from = TimeUtil.parseTime(node.from, null);
                LocalTime to = TimeUtil.parseTime(node.to, null);

                if (from == null || to == null) {
                    yield "任务 " + taskId + " 的 " + path + "（TIME_RANGE）时间格式非法：" + node.from + " ~ " + node.to;
                }

                if (from.equals(to)) {
                    yield "任务 " + taskId + " 的 " + path + "（TIME_RANGE）起止相同，时段长度为 0";
                }

                yield null;
            }
        };
    }
}
