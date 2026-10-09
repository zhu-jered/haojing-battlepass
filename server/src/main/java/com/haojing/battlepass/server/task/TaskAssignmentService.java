package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerState;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用途：每日任务与每周挑战的刷新、以及「重 roll 本组」。对应需求文档 §5.1 / §5.3 / §5.4。
 *
 * <p>为什么刷新要分成"主线程改在线玩家内存 / 后台线程改离线玩家文件"两条路：
 * §5.3 要求对「在线 + 离线玩家统一刷新」，但 §12 又禁止主线程同步 IO。
 * 在线玩家的数据已经在内存里，改它就是纯内存操作（快，可以在主线程做）；
 * 离线玩家必须读文件、改、再写文件，这部分丢到后台线程。
 * 两者都不违反"主线程不做同步 IO"。
 *
 * <p>为什么幂等标记最后才写：§5.3 的标记 {@code lastDailyRefreshDate} 用来保证
 * "跨多天停服只补刷一次"。若在刷新开始时就写标记，中途崩溃会导致一部分玩家没被刷到
 * 却再也不会重试；最后写则最坏情况是重刷一次（表现为当天的任务被重新抽一遍），
 * 这是更安全的失败方向。
 */
public final class TaskAssignmentService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final PlayerDataManager dataManager;
    private final TaskPoolManager poolManager;
    private final ServerStateManager stateManager;
    private final ConfigManager configManager;
    private final TaskProgressTracker tracker;
    private final StoragePaths paths;
    private final JsonStore jsonStore;

    private final Random random = new Random();

    /** 刷新防重入：后台补刷还没跑完时，不要因为主线程再次轮询而启动第二轮。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HaoJingBP-TaskRefresh");
        // 守护线程：即使服务端异常退出，也不会把 JVM 拖住。
        thread.setDaemon(true);
        return thread;
    });

    public TaskAssignmentService(PlayerDataManager dataManager, TaskPoolManager poolManager,
                                 ServerStateManager stateManager, ConfigManager configManager,
                                 TaskProgressTracker tracker, StoragePaths paths, JsonStore jsonStore) {
        this.dataManager = dataManager;
        this.poolManager = poolManager;
        this.stateManager = stateManager;
        this.configManager = configManager;
        this.tracker = tracker;
        this.paths = paths;
        this.jsonStore = jsonStore;
    }

    /**
     * 由 tick 轮询调用（服务端主线程）。只做日期比较与在线玩家的内存改动，不做文件 IO。
     *
     * @param now             当前北京时间
     * @param onlinePlayerIds 当前在线玩家
     */
    public void poll(ZonedDateTime now, List<UUID> onlinePlayerIds) {
        SeasonConfig config = configManager.config();
        TaskPool pool = poolManager.pool();
        ServerState state = stateManager.state();

        if (config == null || pool == null || state == null) {
            return;
        }

        LocalTime boundary = config.dailyRefresh();
        String businessDate = TimeUtil.businessDateKey(now, boundary);
        String weekStart = weekStartKey(businessDate);

        boolean needDaily = !businessDate.equals(state.lastDailyRefreshDate);
        boolean needWeekly = !weekStart.equals(state.lastWeeklyRefreshDate);

        if (!needDaily && !needWeekly) {
            return;
        }

        if (!refreshing.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("{} 触发任务刷新：业务日={}（上次 {}）周起始={}（上次 {}）[每日={} 每周={}]",
                ModConstants.LOG_PREFIX, businessDate, state.lastDailyRefreshDate,
                weekStart, state.lastWeeklyRefreshDate, needDaily, needWeekly);

        Set<UUID> online = new HashSet<>();
        List<UUID> onlineList = onlinePlayerIds == null ? List.of() : onlinePlayerIds;

        for (UUID uuid : onlineList) {
            online.add(uuid);
            SeasonData season = dataManager.season(uuid);
            applyRefresh(season, pool, needDaily, needWeekly, businessDate);
            dataManager.markSeasonDirty(uuid);
            // 任务换了，进度索引必须重建，否则会继续往旧任务上加进度。
            tracker.invalidate(uuid);
        }

        List<UUID> offline = new ArrayList<>();

        for (UUID uuid : dataManager.allStoredPlayerIds()) {
            if (!online.contains(uuid)) {
                offline.add(uuid);
            }
        }

        if (offline.isEmpty()) {
            finishRefresh(businessDate, weekStart);
            return;
        }

        worker.submit(() -> refreshOffline(offline, pool, needDaily, needWeekly, businessDate, weekStart));
    }

    /**
     * 重 roll 某个任务组。需求文档 §5.1：玩家可「重 roll 本组」，默认每日 1 次，管理员可调。
     *
     * <p>旧任务的进度直接作废 —— 用一个新的 {@link TaskProgress} 覆盖掉它。
     * 这是"重 roll"这个词的应有之义：换一个任务重新做，而不是保留进度换皮。
     *
     * @param playerUuid 玩家
     * @param group      组名（explore / build / general）
     * @return 是否成功重 roll
     */
    public boolean rerollGroup(UUID playerUuid, String group) {
        TaskPool pool = poolManager.pool();
        SeasonConfig config = configManager.config();

        if (playerUuid == null || pool == null || config == null || group == null) {
            return false;
        }

        if (!TaskPool.DAILY_GROUPS.contains(group)) {
            return false;
        }

        SeasonData season = dataManager.season(playerUuid);

        if (season.dailyRerollUsed >= config.dailyRerollLimit) {
            return false;
        }

        // 排除集包含"本组当前任务"，因此重 roll 一定会换一个不同的任务；
        // 若本组池子被排空（池太小或有大量同日/昨日任务），则放弃而不是给回同一个。
        Set<String> excluded = TaskSelector.dailyExclusion(season);
        TaskDefinition picked = TaskSelector.pickOne(pool.group(group), excluded, random);

        if (picked == null) {
            LOGGER.warn("{} 玩家 {} 刷新任务组 {} 失败：该组没有可替换的任务（池太小或全被排除）",
                    ModConstants.LOG_PREFIX, playerUuid, group);
            return false;
        }

        season.dailyTasks.put(group, new TaskProgress(picked.id));
        season.dailyRerollUsed++;
        dataManager.markSeasonDirty(playerUuid);
        tracker.invalidate(playerUuid);

        LOGGER.info("{} 玩家 {} 刷新任务组 {} 成功（本日已用 {}/{}）",
                ModConstants.LOG_PREFIX, playerUuid, group, season.dailyRerollUsed, config.dailyRerollLimit);
        return true;
    }

    /**
     * 玩家进服时补齐任务（阶段 7 真机反馈后补的关键修复）。
     *
     * <p>为什么必须有它：每日刷新只处理"刷新那一刻在线"与"已有存档文件"的玩家。
     * 于是有两类人会拿到**空任务列表**，而且直到第二天 06:00 都不会变：
     * <ul>
     *   <li>首次进服的新玩家（还没有存档文件，刷新时根本看不到他）；</li>
     *   <li>当天刷新之后才上线、且存档里任务为空或 <b>业务日不是今天</b> 的玩家。</li>
     * </ul>
     * 真机日志里 {@code 首次同步通道 tasks … 50 字节} 就是这个 bug —— 50 字节正是
     * "四个空数组"的 JSON。任务为空时玩家界面与任务链路全都无从测试。
     *
     * @param playerUuid 玩家
     * @return 本次是否补抽了任务
     */
    public boolean ensureAssigned(UUID playerUuid) {
        TaskPool pool = poolManager.pool();
        SeasonConfig config = configManager.config();

        if (playerUuid == null || pool == null || config == null) {
            return false;
        }

        SeasonData season = dataManager.season(playerUuid);
        String businessDate = TimeUtil.businessDateKey(TimeUtil.now(), config.dailyRefresh());

        boolean dailyMissing = season.dailyTasks == null
                || season.dailyTasks.isEmpty()
                || !businessDate.equals(season.lastDailyRefreshDate);
        boolean weeklyMissing = season.weeklyTasks == null || season.weeklyTasks.isEmpty();

        if (!dailyMissing && !weeklyMissing) {
            return false;
        }

        if (dailyMissing) {
            rollDailyInto(season, pool, businessDate);
        }

        if (weeklyMissing) {
            rollWeeklyInto(season, pool);
        }

        dataManager.markSeasonDirty(playerUuid);
        // 任务换了，进度索引必须重建，否则会继续往旧任务上加进度。
        tracker.invalidate(playerUuid);

        LOGGER.info("{} 玩家 {} 进服时补齐任务（每日{} / 每周{}，业务日 {}）",
                ModConstants.LOG_PREFIX, playerUuid,
                dailyMissing ? "已重抽" : "无需处理", weeklyMissing ? "已重抽" : "无需处理", businessDate);
        return true;
    }

    /**
     * 强制某个玩家重抽每日任务（阶段 7 的管理面板测试用）。
     *
     * <p>与 {@link #poll} 的区别：poll 受"本业务日是否已刷新"的幂等标记保护，
     * 因此管理员没法在当天反复测试任务链路。本方法只作用于**指定玩家**、
     * 不动全服标记，代价是该玩家当天的任务进度被作废（已用文案明确提示）。
     *
     * @param playerUuid 玩家
     * @return 是否执行了重抽
     */
    public boolean forceDailyRefresh(UUID playerUuid) {
        TaskPool pool = poolManager.pool();
        SeasonConfig config = configManager.config();

        if (playerUuid == null || pool == null || config == null) {
            return false;
        }

        SeasonData season = dataManager.season(playerUuid);
        String businessDate = TimeUtil.businessDateKey(TimeUtil.now(), config.dailyRefresh());

        rollDailyInto(season, pool, businessDate);
        dataManager.markSeasonDirty(playerUuid);
        // 任务换了，进度索引必须重建，否则会继续往旧任务上加进度。
        tracker.invalidate(playerUuid);

        LOGGER.info("{} 管理员强制为玩家 {} 重抽了每日任务（业务日 {}）",
                ModConstants.LOG_PREFIX, playerUuid, businessDate);
        return true;
    }

    /** @return 某个业务日所属周的周一（yyyy-MM-dd）。需求文档 §5.4：每周挑战默认每周一 06:00 刷新。 */
    static String weekStartKey(String businessDate) {
        LocalDate date = LocalDate.parse(businessDate);
        // DayOfWeek.getValue()：周一=1 …… 周日=7，因此减到周一即可。
        return TimeUtil.dateKey(date.minusDays(date.getDayOfWeek().getValue() - 1L));
    }

    /** 对一份赛季数据执行刷新。返回是否发生了改动。 */
    boolean applyRefresh(SeasonData season, TaskPool pool, boolean needDaily, boolean needWeekly,
                         String businessDate) {
        boolean changed = false;

        if (needDaily) {
            rollDailyInto(season, pool, businessDate);
            changed = true;
        }

        if (needWeekly) {
            rollWeeklyInto(season, pool);
            changed = true;
        }

        return changed;
    }

    /** 重抽每日任务。抽完后把"今天的选择"转存为"昨日"，供明天去重（§5.1 与昨日不重复）。 */
    void rollDailyInto(SeasonData season, TaskPool pool, String businessDate) {
        Set<String> excluded = TaskSelector.dailyExclusion(season);
        Map<String, TaskDefinition> rolled = TaskSelector.rollDaily(pool, excluded, random);

        season.previousDailyTaskIds = new ArrayList<>(TaskSelector.currentDailyIds(season));

        season.dailyTasks.clear();

        for (Map.Entry<String, TaskDefinition> entry : rolled.entrySet()) {
            season.dailyTasks.put(entry.getKey(), new TaskProgress(entry.getValue().id));
        }

        season.dailyRerollUsed = 0;
        // §5.10 的每日经验上限按「业务日」计，因此随每日刷新一并清零。
        season.dailyXpEarned = 0;
        season.lastDailyRefreshDate = businessDate;

        if (rolled.size() < TaskPool.DAILY_GROUPS.size()) {
            LOGGER.warn("{} 玩家 {} 的每日任务只抽出 {}/{} 组：对应组的任务池太小或被排除集占满",
                    ModConstants.LOG_PREFIX, season.playerUuid, rolled.size(), TaskPool.DAILY_GROUPS.size());
        }
    }

    /**
     * 重抽每周挑战。
     *
     * <p>刻意不排除"上周抽中"的：需求文档 §5.4 只规定了数量与难度，
     * 没有要求每周挑战与上周不重复（而 §5.1 明确要求了每日任务与昨日不重复）。
     * 按 §0「不自行发明新机制」，这里不额外加约束。
     */
    void rollWeeklyInto(SeasonData season, TaskPool pool) {
        List<TaskDefinition> picked = TaskSelector.pickMany(pool.allWeekly(), pool.weeklyCount, Set.of(), random);

        season.weeklyTasks.clear();

        for (TaskDefinition definition : picked) {
            season.weeklyTasks.put(definition.id, new TaskProgress(definition.id));
        }
    }

    private void refreshOffline(List<UUID> offline, TaskPool pool, boolean needDaily, boolean needWeekly,
                                String businessDate, String weekStart) {
        long started = System.currentTimeMillis();
        int written = 0;
        int failed = 0;

        for (UUID uuid : offline) {
            Path file = paths.seasonFile(uuid);

            try {
                String seasonId = seasonIdOrDefault();
                SeasonData season = jsonStore.read(file, SeasonData.class, () -> new SeasonData(uuid.toString(), seasonId));
                season.playerUuid = uuid.toString();

                if (season.dailyTasks == null) {
                    season.dailyTasks = new java.util.LinkedHashMap<>();
                }

                if (season.weeklyTasks == null) {
                    season.weeklyTasks = new java.util.LinkedHashMap<>();
                }

                if (applyRefresh(season, pool, needDaily, needWeekly, businessDate)) {
                    jsonStore.write(file, season);
                    written++;
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                // 单个玩家失败不影响其他人（§12）。
                LOGGER.error("{} 刷新离线玩家 {} 的任务失败", ModConstants.LOG_PREFIX, uuid, e);
            }
        }

        // 注意一个已知的窄窗口：后台线程写某个离线玩家文件的同时，该玩家恰好上线，
        // PlayerDataManager 可能读到刷新前的旧内容。表现为该玩家当天还拿着昨天的任务，
        // 次日刷新会自动纠正。刷新发生在 dailyRefreshTime（默认 06:00），此时在线人数最少，
        // 为此引入跨线程锁并不划算，故接受该窗口并在文档中说明。
        LOGGER.info("{} 离线玩家任务刷新完成：写入 {} 个，失败 {} 个，耗时 {} ms",
                ModConstants.LOG_PREFIX, written, failed, System.currentTimeMillis() - started);

        finishRefresh(businessDate, weekStart);
    }

    private void finishRefresh(String businessDate, String weekStart) {
        try {
            // 幂等标记最后才写：中途崩溃最坏是重刷一次，而不是"一部分玩家永远没被刷到"。
            stateManager.markDailyRefreshed(businessDate);
            stateManager.markWeeklyRefreshed(weekStart);
        } finally {
            refreshing.set(false);
        }
    }

    private String seasonIdOrDefault() {
        SeasonConfig config = configManager.config();
        return config == null || config.seasonId == null || config.seasonId.isBlank() ? "S1" : config.seasonId;
    }

    @Override
    public void close() {
        worker.shutdown();

        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}
