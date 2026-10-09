package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.data.PlayerDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：把一次玩家行为换算成任务进度。覆盖需求文档 §5.8「防刷」的全部要求：
 * 只统计玩家本人行为、假人不计入、CLAIMED 后不再计数、同一 tick 内批量操作去重。
 *
 * <p>为什么本类刻意不引用任何 Minecraft 类型：调用方（事件监听）负责把游戏对象
 * 翻译成 {@link TaskFilterContext} 里的几个字符串/数字，本类只做纯粹的判定与累加。
 * 这样"哪些行为算数、去重规则对不对"就能用单元测试直接验证，
 * 而不必开服务器、造真人玩家、卡到同一个 tick 里操作。
 *
 * <p>为什么按"动作"建索引：需求文档 §13 要求"禁止每 tick 遍历全部玩家 × 全部任务"。
 * 玩家身上最多 3 个每日 + 3 个每周任务，这里把它们按动作分桶，
 * 事件到达时只比较同一动作的少数几个任务，与池子大小无关。
 */
public final class TaskProgressTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 去重表容量上限。超过就清理掉非当前 tick 的旧记录，避免长时间运行后无限增长。 */
    private static final int MAX_DEDUPE_ENTRIES = 8192;

    private final PlayerDataManager dataManager;
    private final TaskPoolManager poolManager;
    private final ConfigManager configManager;

    /** 玩家 → 动作 → 该动作下已分配的任务。分配变化（刷新/重 roll/热重载）时必须失效。 */
    private final Map<UUID, Map<TaskAction, List<Assigned>>> indexCache = new ConcurrentHashMap<>();

    /** 同 tick 去重表：键为 玩家|动作|判别值，值为最近一次计数所在的 tick。 */
    private final Map<String, Integer> recentActions = new ConcurrentHashMap<>();

    public TaskProgressTracker(PlayerDataManager dataManager, TaskPoolManager poolManager,
                               ConfigManager configManager) {
        this.dataManager = dataManager;
        this.poolManager = poolManager;
        this.configManager = configManager;
    }

    /** 已分配的任务定义与其进度对象。持有进度对象的引用，因此累加后无需回写查找。 */
    private record Assigned(TaskDefinition definition, TaskProgress progress) {
    }

    /**
     * 推进某个玩家的任务进度。
     *
     * @param playerUuid    玩家 UUID
     * @param playerName    玩家名（用于假人过滤）
     * @param action        触发动作
     * @param context       过滤器上下文
     * @param discriminator 同 tick 去重的判别值：方块类传坐标、击杀类传实体 UUID、其余传 null
     * @param currentTick   当前服务端 tick（同 tick 去重依据）
     * @return 被推进的任务数量（供调用方判断是否需要推送更新包）
     */
    public int advance(UUID playerUuid, String playerName, TaskAction action, TaskFilterContext context,
                       String discriminator, int currentTick) {
        if (playerUuid == null || action == null) {
            return 0;
        }

        if (isFakePlayer(playerName)) {
            // 需求文档 §5.8：假人的行为不计入。这里直接返回，连去重表都不写，
            // 免得假人刷屏把去重表占满。
            return 0;
        }

        if (isDuplicateInSameTick(playerUuid, action, discriminator, currentTick)) {
            return 0;
        }

        SeasonData season = dataManager.season(playerUuid);
        Map<TaskAction, List<Assigned>> index =
                indexCache.computeIfAbsent(playerUuid, id -> buildIndex(season));
        List<Assigned> candidates = index.get(action);

        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int advanced = 0;

        for (Assigned assigned : candidates) {
            if (!TaskFilterEvaluator.matches(assigned.definition().filter, context)) {
                continue;
            }

            if (applyProgress(assigned)) {
                advanced++;
            }
        }

        if (advanced > 0) {
            // 只标脏，不在这里写盘：写盘由 PlayerDataManager 的节流线程负责（§12 禁止主线程同步 IO）。
            dataManager.markSeasonDirty(playerUuid);
        }

        return advanced;
    }

    /** 任务分配发生变化（每日/每周刷新、重 roll）后必须调用，否则会对着旧任务累加。 */
    public void invalidate(UUID playerUuid) {
        indexCache.remove(playerUuid);
    }

    /** 任务池热重载后必须调用：任务定义换了，索引里的引用就过期了。 */
    public void invalidateAll() {
        indexCache.clear();
    }

    /**
     * 位置类动作的集合。这些动作没有服务端事件，只能靠每 20 tick 轮询比对（§5.6 的规定做法）。
     *
     * <p>它们的共同点是"按条件由假变真计数"：抵达某高度只算一次、进入群系只算一次，
     * 而不是站在那里每 20 tick 就加一次。因此轮询方需要先拿到任务清单，
     * 自己比对上一轮是否已满足，再决定要不要调用 {@link #advanceTask}。
     */
    public static final EnumSet<TaskAction> POSITION_ACTIONS = EnumSet.of(
            TaskAction.ENTER_BIOME, TaskAction.ENTER_DIMENSION, TaskAction.REACH_POSITION);

    /**
     * @param playerUuid 玩家
     * @return 该玩家身上所有"位置类"任务的定义（可能为空）
     */
    public List<TaskDefinition> positionTasks(UUID playerUuid) {
        SeasonData season = dataManager.season(playerUuid);
        Map<TaskAction, List<Assigned>> index =
                indexCache.computeIfAbsent(playerUuid, id -> buildIndex(season));
        List<TaskDefinition> definitions = new ArrayList<>();

        for (TaskAction action : POSITION_ACTIONS) {
            List<Assigned> list = index.get(action);

            if (list != null) {
                for (Assigned assigned : list) {
                    definitions.add(assigned.definition());
                }
            }
        }

        return definitions;
    }

    /**
     * 精确推进指定的一个任务。位置类任务由轮询调用。
     *
     * <p>为什么不复用 {@link #advance}：那个方法是"按动作批量推进"，
     * 用在位置类任务上会把已经满足条件的任务每轮再加一次（目标值大于 1 时直接失控）。
     * 轮询方已经做过"由假变真"的比对，因此这里必须只推进它指定的那一个。
     *
     * @return 是否真的推进了
     */
    public boolean advanceTask(UUID playerUuid, String playerName, String taskId,
                               TaskFilterContext context, int currentTick) {
        if (playerUuid == null || taskId == null || isFakePlayer(playerName)) {
            return false;
        }

        SeasonData season = dataManager.season(playerUuid);
        Map<TaskAction, List<Assigned>> index =
                indexCache.computeIfAbsent(playerUuid, id -> buildIndex(season));

        for (List<Assigned> list : index.values()) {
            for (Assigned assigned : list) {
                if (!taskId.equals(assigned.definition().id)) {
                    continue;
                }

                if (!TaskFilterEvaluator.matches(assigned.definition().filter, context)) {
                    return false;
                }

                boolean changed = applyProgress(assigned);

                if (changed) {
                    dataManager.markSeasonDirty(playerUuid);
                }

                return changed;
            }
        }

        return false;
    }

    /** 玩家退出时释放其索引，避免内存随"见过的玩家数"无限增长。 */
    public void forget(UUID playerUuid) {
        indexCache.remove(playerUuid);
    }

    private boolean isFakePlayer(String playerName) {
        if (playerName == null) {
            return false;
        }

        var config = configManager.config();

        if (config == null) {
            return false;
        }

        String prefix = config.fakePlayerNamePrefix;

        // 空串表示不启用假人过滤（用户可能把前缀清掉）。
        return prefix != null && !prefix.isEmpty() && playerName.startsWith(prefix);
    }

    private boolean isDuplicateInSameTick(UUID playerUuid, TaskAction action, String discriminator, int currentTick) {
        String key = playerUuid + "|" + action + "|" + (discriminator == null ? "" : discriminator);
        Integer previous = recentActions.put(key, currentTick);

        if (recentActions.size() > MAX_DEDUPE_ENTRIES) {
            // 只在超限时才做一次清理；移除所有非当前 tick 的记录即可，代价很低。
            recentActions.entrySet().removeIf(entry -> entry.getValue() != currentTick);
        }

        return previous != null && previous == currentTick;
    }

    /**
     * 对单个已分配任务执行一次累加，并同步状态。返回是否发生了变化。
     *
     * <p>需求的三个细节都在这里：CLAIMED 后不再计数（§5.8）、进度封顶在目标值（避免涨到荒谬的数字）、
     * 首次计数把 NOT_ACTIVE 提升为 IN_PROGRESS、达标时置为 COMPLETED（完成待领取，§5.5）。
     */
    private boolean applyProgress(Assigned assigned) {
        TaskProgress progress = assigned.progress();

        // 需求文档 §5.8：CLAIMED 后不再计数。
        if (progress.isClaimed()) {
            return false;
        }

        int target = assigned.definition().target;
        int before = progress.progress;
        int next = Math.min(target, before + 1);
        progress.progress = next;

        TaskStatus statusBefore = progress.status();

        if (next >= target) {
            progress.setStatus(TaskStatus.COMPLETED);
        } else if (statusBefore == TaskStatus.NOT_ACTIVE) {
            progress.setStatus(TaskStatus.IN_PROGRESS);
        }

        return next != before || progress.status() != statusBefore;
    }

    /**
     * 用玩家当前已分配的任务构建动作索引。
     *
     * <p>不在这里做任何 IO：season 已经在内存里（PlayerDataManager 保证），
     * 因此可以安全地在 ConcurrentHashMap 的映射函数中执行，不会因读盘而长时间持锁。
     */
    private Map<TaskAction, List<Assigned>> buildIndex(SeasonData season) {
        Map<TaskAction, List<Assigned>> index = new EnumMap<>(TaskAction.class);
        TaskPool pool = poolManager.pool();

        if (season == null || pool == null) {
            return index;
        }

        Map<String, TaskDefinition> definitions = pool.indexById();

        if (season.dailyTasks != null) {
            for (TaskProgress progress : season.dailyTasks.values()) {
                addToIndex(index, definitions.get(progress == null ? null : progress.taskId), progress);
            }
        }

        if (season.weeklyTasks != null) {
            for (Map.Entry<String, TaskProgress> entry : season.weeklyTasks.entrySet()) {
                addToIndex(index, definitions.get(entry.getKey()), entry.getValue());
            }
        }

        return index;
    }

    private void addToIndex(Map<TaskAction, List<Assigned>> index, TaskDefinition definition, TaskProgress progress) {
        if (definition == null || progress == null) {
            // 任务已从池子里删掉（管理员改动），玩家身上还留着旧进度：
            // 不报错、也不推进，静默跳过即可 —— 它会在下次刷新时被换掉。
            return;
        }

        TaskAction action = definition.actionOrNull();

        if (action == null) {
            return;
        }

        index.computeIfAbsent(action, key -> new ArrayList<>()).add(new Assigned(definition, progress));
    }
}
