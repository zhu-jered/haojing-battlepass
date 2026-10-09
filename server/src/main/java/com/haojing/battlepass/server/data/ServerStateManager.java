package com.haojing.battlepass.server.data;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.config.FileChangeDetector;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用途：全服共用状态的读写（data/haojing_battlepass/state.json）。
 * 核心是需求文档 §5.3 的幂等标记 {@code lastDailyRefreshDate}。
 *
 * <p>为什么写入是同步的、而不是像玩家数据那样异步节流：这个文件只在一处被写 ——
 * 每日/每周刷新流程，而那段流程本身就跑在后台线程上（见 DailyRefreshService），
 * 因此这里的同步写不会阻塞服务端主线程，也就不需要再叠一层异步。
 * 需求文档 §12 禁止的是"主线程同步 IO"，不是"同步 IO"本身。
 */
public final class ServerStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final FileChangeDetector changeDetector = new FileChangeDetector("全服状态");

    private volatile ServerState state;

    public ServerStateManager(StoragePaths paths, JsonStore jsonStore) {
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /** @return 当前全服状态；未加载时为 null。 */
    public ServerState state() {
        return state;
    }

    /**
     * 从磁盘加载全服状态。文件缺失时用默认值（空的刷新标记，等价于"从未刷新过"）。
     *
     * @return 加载后的状态（永不为 null）
     */
    public ServerState load() {
        ServerState loaded = jsonStore.read(paths.stateFile(), ServerState.class, ServerState::new);
        state = loaded;
        writeQuietly(loaded);
        changeDetector.reset(paths.stateFile());
        return loaded;
    }

    /** 把当前状态写回磁盘。 */
    public void save() {
        ServerState current = state;

        if (current == null) {
            LOGGER.warn("{} 全服状态尚未加载，忽略保存请求", ModConstants.LOG_PREFIX);
            return;
        }

        writeQuietly(current);
    }

    /**
     * 记录"本业务日已完成每日刷新"。需求文档 §5.3 要求写入全局标记以实现幂等。
     *
     * @param businessDateKey 业务日键（yyyy-MM-dd，以 dailyRefreshTime 为界）
     */
    public void markDailyRefreshed(String businessDateKey) {
        ServerState current = ensureLoaded();
        current.lastDailyRefreshDate = businessDateKey;
        writeQuietly(current);
    }

    /**
     * 记录"本周已完成每周挑战刷新"。
     *
     * @param weekStartKey 周起始日键（yyyy-MM-dd，周一）
     */
    public void markWeeklyRefreshed(String weekStartKey) {
        ServerState current = ensureLoaded();
        current.lastWeeklyRefreshDate = weekStartKey;
        writeQuietly(current);
    }

    /**
     * 记录一次赛季滚动（阶段 5）。
     *
     * <p>同时做三件事，且必须一起写：
     * ① 记下"已核对到哪个赛季"，让 {@code SeasonResetService} 走 O(1) 快速路径；
     * ② 在有玩家被真正重置时清空每日/每周刷新标记 —— 因为那些玩家的任务被清空了，
     * 若不清标记，下一次轮询会认为"今天已经刷过了"而不重抽任务，
     * 结果就是全体玩家在一整天内没有任务可做；
     * ③ 记下待公告的来源赛季（若确实滚动了）。
     *
     * <p>为什么清标记要由 {@code tasksReset} 控制而不是无条件执行：升级模组后首次启动
     * 常常出现"标记为空、但玩家存档已经全是当前赛季"的情况，此时没有任何人需要重置；
     * 若顺手清了刷新标记，全体玩家当天的任务进度会被平白作废。
     *
     * @param rolledToSeasonId   滚动到的赛季 ID（配置里的当前赛季）
     * @param rolledFromSeasonId 被归档的来源赛季 ID；为空表示无需公告
     * @param tasksReset          本次是否真的重置了玩家的任务数据
     */
    public void markSeasonRolled(String rolledToSeasonId, String rolledFromSeasonId, boolean tasksReset) {
        ServerState current = ensureLoaded();
        current.lastSeasonId = rolledToSeasonId;

        if (tasksReset) {
            current.lastDailyRefreshDate = "";
            current.lastWeeklyRefreshDate = "";
        }

        if (rolledFromSeasonId != null && !rolledFromSeasonId.isBlank()) {
            current.pendingSeasonRolledFrom = rolledFromSeasonId;
        }

        writeQuietly(current);
    }

    /**
     * 清除"待公告"标记。由播报方在真正把公告发出去之后调用。
     */
    public void clearPendingSeasonAnnouncement() {
        ServerState current = ensureLoaded();
        current.pendingSeasonRolledFrom = "";
        writeQuietly(current);
    }

    // ------------------------------------------------------------------
    // 全服里程碑（阶段 6）
    // ------------------------------------------------------------------

    /**
     * @return 已持久化的累计计数器快照（键为指标名）；字段缺失时为空表。
     */
    public Map<String, Long> metrics() {
        ServerState current = ensureLoaded();

        if (current.metrics == null) {
            current.metrics = new LinkedHashMap<>();
        }

        return current.metrics;
    }

    /**
     * @return 已持久化的"已达成里程碑"集合。
     */
    public Set<String> reachedMilestones() {
        ServerState current = ensureLoaded();

        if (current.reachedMilestones == null) {
            current.reachedMilestones = new LinkedHashSet<>();
        }

        return current.reachedMilestones;
    }

    /**
     * 落盘里程碑的计数器与已达成集合。
     *
     * <p>由后台线程周期调用（内存累加 + 定时合并落盘），因此这里可以放心做同步写。
     *
     * @param metrics  计数器快照
     * @param reached  已达成集合快照
     */
    public void saveMetrics(Map<String, Long> metrics, Set<String> reached) {
        ServerState current = ensureLoaded();
        current.metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
        current.reachedMilestones = reached == null ? new LinkedHashSet<>() : new LinkedHashSet<>(reached);
        writeQuietly(current);
    }

    private ServerState ensureLoaded() {
        ServerState current = state;

        if (current == null) {
            current = load();
        }

        return current;
    }

    private void writeQuietly(ServerState value) {
        try {
            jsonStore.write(paths.stateFile(), value);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入全服状态失败：{}", ModConstants.LOG_PREFIX, paths.stateFile(), e);
        }
    }
}
