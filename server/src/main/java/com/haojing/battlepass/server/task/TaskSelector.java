package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 用途：任务抽取。需求文档 §5.1 每日任务「从任务池随机抽取 3 个，分属探险组/建造组/综合组各 1 个。
 * 同日不重复、与昨日不重复」；§5.4 每周挑战默认抽取 3 个。
 *
 * <p>为什么把抽取做成"纯函数 + 显式传入 Random"而不是内部直接用 Math.random()：
 * 随机抽取的规则（排除昨日、组内选一、组被排满时怎么办）都是容易写错的边界，
 * 而随机性会让 bug 时有时无。把 Random 作为参数传入后，测试可以用固定种子
 * 得到完全确定的序列，从而把规则钉死；生产代码传 {@code new Random()} 即可。
 */
public final class TaskSelector {

    private TaskSelector() {
    }

    /**
     * 从池中随机取一个，排除 {@code excluded} 中的任务。
     *
     * @param pool     候选池
     * @param excluded 需要排除的任务 ID
     * @param random   随机源
     * @return 抽中的任务；候选被排空时返回 null
     */
    public static TaskDefinition pickOne(List<TaskDefinition> pool, Set<String> excluded, Random random) {
        List<TaskDefinition> candidates = candidatesOf(pool, excluded);

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * 从池中随机取 {@code count} 个互不重复的任务，排除 {@code excluded} 中的任务。
     *
     * <p>为什么用洗牌而不是循环 pickOne：循环取需要每次把已选项加入排除集，
     * 而洗牌天然保证不重复，代码更短也更不容易漏掉"把自己又抽一遍"的情况。
     *
     * @param pool     候选池
     * @param count    期望数量
     * @param excluded 需要排除的任务 ID
     * @param random   随机源
     * @return 抽中的任务列表；候选不足时返回全部候选
     */
    public static List<TaskDefinition> pickMany(List<TaskDefinition> pool, int count,
                                                Set<String> excluded, Random random) {
        List<TaskDefinition> candidates = candidatesOf(pool, excluded);
        Collections.shuffle(candidates, random);
        return new ArrayList<>(candidates.subList(0, Math.min(Math.max(count, 0), candidates.size())));
    }

    /**
     * 每日任务抽取：三个组各抽 1 个。
     *
     * <p>「同日不重复」由两点共同保证：任务 ID 在整个池内全局唯一（TaskPool#validate 强制），
     * 以及这里把已抽中的 ID 继续加进排除集。「与昨日不重复」由调用方传入的排除集保证。
     *
     * @param pool     任务池
     * @param excluded 需要排除的任务 ID（通常是昨日抽中的那些）
     * @param random   随机源
     * @return 组名 → 抽中的任务；某组无可抽任务时该组不会出现在结果里
     */
    public static Map<String, TaskDefinition> rollDaily(TaskPool pool, Set<String> excluded, Random random) {
        Map<String, TaskDefinition> rolled = new LinkedHashMap<>();

        if (pool == null) {
            return rolled;
        }

        Set<String> taken = new HashSet<>(excluded == null ? Set.of() : excluded);

        for (String group : TaskPool.DAILY_GROUPS) {
            TaskDefinition picked = pickOne(pool.group(group), taken, random);

            if (picked == null) {
                // 不在这里打日志：抽取逻辑保持纯粹，调用方拿到"少了某组"后统一告警。
                continue;
            }

            rolled.put(group, picked);
            taken.add(picked.id);
        }

        return rolled;
    }

    private static List<TaskDefinition> candidatesOf(List<TaskDefinition> pool, Set<String> excluded) {
        List<TaskDefinition> candidates = new ArrayList<>();

        if (pool == null) {
            return candidates;
        }

        for (TaskDefinition task : pool) {
            if (task != null && task.id != null
                    && (excluded == null || !excluded.contains(task.id))) {
                candidates.add(task);
            }
        }

        return candidates;
    }

    /**
     * 计算每日抽取需要排除的任务 ID：昨日抽中的 + 当前已抽中的。
     *
     * <p>这两项分别对应需求文档 §5.1 的「与昨日不重复」和「同日不重复」。
     * 之所以要显式排除"当前已抽中的"，是因为存在重 roll：重 roll 某组时，
     * 另外两组的任务必须仍然不能被重复抽到。
     *
     * @param season 玩家赛季数据
     * @return 排除集
     */
    public static Set<String> dailyExclusion(SeasonData season) {
        Set<String> excluded = new HashSet<>();

        if (season == null) {
            return excluded;
        }

        if (season.previousDailyTaskIds != null) {
            excluded.addAll(season.previousDailyTaskIds);
        }

        if (season.dailyTasks != null) {
            for (TaskProgress progress : season.dailyTasks.values()) {
                if (progress != null && progress.taskId != null) {
                    excluded.add(progress.taskId);
                }
            }
        }

        return excluded;
    }

    /**
     * @param season 玩家赛季数据
     * @return 当前每日任务 ID 列表（刷新时用于转存为"昨日"，实现跨日去重）
     */
    public static List<String> currentDailyIds(SeasonData season) {
        List<String> ids = new ArrayList<>();

        if (season == null || season.dailyTasks == null) {
            return ids;
        }

        for (TaskProgress progress : season.dailyTasks.values()) {
            if (progress != null && progress.taskId != null && !progress.taskId.isBlank()) {
                ids.add(progress.taskId);
            }
        }

        return ids;
    }
}
