package com.haojing.battlepass.server.season;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerState;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.task.TaskProgressTracker;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用途：赛季结束时的归档与重置（需求文档 §4：「赛季结束 → 全服结算公告 →
 * 归档 data/history/season_&lt;id&gt;.json → 重置等级/经验/任务/分支，保留星币与收藏册」）。
 *
 * <p>为什么判断依据是「配置里的 seasonId 与玩家存档里的 seasonId 不一致」：
 * 需求文档没有规定"赛季何时结束"的判据（只给了 durationDays 这个时长）。
 * 玩家存档里本来就记录了 seasonId（阶段 2 就已经写在 {@link SeasonData} 里，
 * 注释也点明"阶段 5 处理"），把它当作唯一判据既不需要新的时间基准，
 * 也天然幂等：重置过的玩家 seasonId 已经是新的，再跑一次不会重复归档。
 * 管理员推进赛季的动作就是把 season.json 里的 seasonId 改成 S2。
 *
 * <p>为什么"在线玩家在主线程改内存、离线玩家交给后台线程改文件"：
 * 与每日刷新（{@code TaskAssignmentService}）完全同一个理由 ——
 * §5.3 要求在线+离线统一处理，而 §12 禁止主线程同步 IO。
 * 归档文件与全服状态的写入（都是 IO）也一并放在后台线程完成。
 *
 * <p>为什么"重置任务"时才清全服刷新标记：清了标记会让所有玩家重抽当日任务（进度作废）。
 * 如果这次滚动其实没重置任何人（例如升级模组后首次启动，玩家的 seasonId 恰好已经等于配置值），
 * 就不该顺手把大家今天的任务清掉 —— 这个分支由一个布尔参数显式控制。
 */
public final class SeasonResetService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /**
     * 公告出口。做成接口是为了让本类不引用 Minecraft 类型
     * （播报要拿 MinecraftServer，而本类需要单元测试）。
     */
    @FunctionalInterface
    public interface Announcer {
        /**
         * @param fromSeasonId 被结算的赛季 ID
         * @param toSeasonId   新赛季 ID
         */
        void announce(String fromSeasonId, String toSeasonId);
    }

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final ServerStateManager stateManager;
    private final StoragePaths paths;
    private final JsonStore jsonStore;
    private final TaskProgressTracker tracker;
    private final Announcer announcer;

    /** 滚动防重入：后台归档还没跑完时，不要因为主线程下一轮轮询又启动一次。 */
    private final AtomicBoolean rolling = new AtomicBoolean(false);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HaoJingBP-SeasonRoll");
        thread.setDaemon(true);
        return thread;
    });

    public SeasonResetService(PlayerDataManager dataManager, ConfigManager configManager,
                              ServerStateManager stateManager, StoragePaths paths, JsonStore jsonStore,
                              TaskProgressTracker tracker, Announcer announcer) {
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.stateManager = stateManager;
        this.paths = paths;
        this.jsonStore = jsonStore;
        this.tracker = tracker;
        this.announcer = announcer;
    }

    /**
     * 把一个赛季存档重置为新赛季（需求文档 §4）。
     *
     * <p>刻意保留的字段：仅永久数据文件里的东西（星币、称号库、收藏册、口令记录、生日、购买记录），
     * 它们根本不在这个对象里 —— 「保留星币与收藏册」是由存储结构保证的，
     * 而不是靠这里记得别删。本方法清掉的正是 §4 点名要重置的：等级/经验/任务/分支。
     *
     * @param season      赛季存档
     * @param newSeasonId 新赛季 ID
     */
    public static void resetSeasonData(SeasonData season, String newSeasonId) {
        if (season == null) {
            return;
        }

        season.seasonId = newSeasonId;
        season.level = 1;
        season.xp = 0;
        season.setBranch(Branch.NONE);
        season.dailyXpEarned = 0;
        season.dailyRerollUsed = 0;
        season.previousDailyTaskIds = new ArrayList<>();
        season.exemptCards = 0;
        season.dailyTasks = new LinkedHashMap<>();
        season.weeklyTasks = new LinkedHashMap<>();
        season.claimedLevelRewards = new LinkedHashSet<>();
        // §6 的"大地勘探者"要求"本赛季内"、赛季限定彩蛋更是必须随赛季失效，
        // 因此彩蛋**进度**一并清空；已经解锁的记录在永久数据的收藏册里，不受影响。
        season.eggProgress = new LinkedHashMap<>();
        // 清掉每日刷新标记：让该玩家在下一轮刷新里重新抽到本日的任务。
        season.lastDailyRefreshDate = "";
    }

    /**
     * 检查并执行赛季滚动。由服务端主线程（轮询）调用，内部只做在线玩家的内存修改，
     * 文件 IO 全部交给后台线程。
     *
     * @param onlinePlayerIds 当前在线玩家
     * @return 本次是否启动了滚动流程
     */
    public boolean checkAndRoll(List<UUID> onlinePlayerIds) {
        SeasonConfig config = configManager.config();
        ServerState state = stateManager.state();

        if (config == null || state == null) {
            return false;
        }

        String target = config.seasonId;

        if (target == null || target.isBlank() || target.equals(state.lastSeasonId)) {
            // O(1) 快速路径：本轮已经核对过这个赛季，不必去读任何玩家文件。
            return false;
        }

        if (!rolling.compareAndSet(false, true)) {
            return false;
        }

        Set<UUID> online = new LinkedHashSet<>(onlinePlayerIds == null ? List.of() : onlinePlayerIds);
        List<SeasonArchive.ArchivePlayer> entries = new ArrayList<>();

        try {
            for (UUID uuid : online) {
                SeasonData season = dataManager.season(uuid);

                if (target.equals(season.seasonId)) {
                    continue;
                }

                entries.add(SeasonArchive.ArchivePlayer.of(season, playerName(uuid)));
                resetSeasonData(season, target);
                dataManager.markSeasonDirty(uuid);
                tracker.invalidate(uuid);
            }
        } catch (RuntimeException e) {
            // 在线玩家处理失败就不启动后台流程：宁可整轮作废（标记没写，下一轮会重试），
            // 也不要出现"一半人重置了、标记却已经写上"的撕裂状态。
            LOGGER.error("{} 赛季滚动处理在线玩家时失败，本轮作废（下一轮将重试）", ModConstants.LOG_PREFIX, e);
            rolling.set(false);
            return false;
        }

        List<UUID> offline = new ArrayList<>();

        for (UUID uuid : dataManager.allStoredPlayerIds()) {
            if (!online.contains(uuid)) {
                offline.add(uuid);
            }
        }

        ZonedDateTime now = TimeUtil.now();
        worker.submit(() -> rollOfflineAndFinish(offline, target, entries, now));
        return true;
    }

    /**
     * 若还有"赛季已滚动"的公告没有播报，则播报并清除标记。
     *
     * <p>由调用方保证当前有玩家在线 —— 没有人在线时播报等于没播，
     * 却会把标记清掉，公告就永远丢了。
     *
     * @return 本次是否播报了
     */
    public boolean announcePendingIfAny() {
        ServerState state = stateManager.state();
        SeasonConfig config = configManager.config();

        if (state == null || config == null || announcer == null) {
            return false;
        }

        String from = state.pendingSeasonRolledFrom;

        if (from == null || from.isBlank()) {
            return false;
        }

        announcer.announce(from, config.seasonId);
        stateManager.clearPendingSeasonAnnouncement();

        LOGGER.info("{} 已播报赛季结算公告：{} → {}", ModConstants.LOG_PREFIX, from, config.seasonId);
        return true;
    }

    /**
     * 等待后台归档线程把手上的活干完。
     *
     * <p>为什么要暴露这个方法：滚动是"主线程改在线玩家 + 后台线程写归档与全服标记"，
     * 调用方（以及测试）若在后台还没收尾时就去读状态文件，会看到"滚动似乎没发生"。
     * 往同一个单线程执行器里丢一个空任务并等它完成，就与前面所有任务建立了
     * happens-before 关系，是一种不依赖 sleep 的确定性等待方式。
     *
     * @param timeoutMillis 超时（毫秒）
     * @return 是否在超时内排空
     */
    public boolean awaitIdle(long timeoutMillis) {
        try {
            worker.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOGGER.warn("{} 等待赛季归档线程排空失败：{}", ModConstants.LOG_PREFIX, e.toString());
            return false;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 已经关掉了执行器：此时没有在跑的任务，视为已排空。
            return true;
        }
    }

    /** 后台阶段：离线玩家逐个读改写，随后写归档文件与全服标记。 */    private void rollOfflineAndFinish(List<UUID> offline, String target,
                                      List<SeasonArchive.ArchivePlayer> onlineEntries, ZonedDateTime now) {
        List<SeasonArchive.ArchivePlayer> entries = new ArrayList<>(onlineEntries);
        int reset = onlineEntries.size();
        int failed = 0;
        int archived = 0;

        for (UUID uuid : offline) {
            Path file = paths.seasonFile(uuid);

            try {
                SeasonData season = jsonStore.read(file, SeasonData.class,
                        () -> new SeasonData(uuid.toString(), target));
                season.playerUuid = uuid.toString();

                if (target.equals(season.seasonId)) {
                    continue;
                }

                entries.add(SeasonArchive.ArchivePlayer.of(season, playerName(uuid)));
                resetSeasonData(season, target);
                jsonStore.write(file, season);
                reset++;
            } catch (IOException | RuntimeException e) {
                failed++;
                // 单个玩家失败不影响其他人（§12）：他的存档仍是旧赛季，下一轮还会被重试。
                LOGGER.error("{} 赛季滚动处理离线玩家 {} 失败（下一轮将重试）", ModConstants.LOG_PREFIX, uuid, e);
            }
        }

        // 按"来源赛季"分组归档：正常情况下只有一种，但中途加入过的玩家可能带着更早的赛季 ID。
        Map<String, List<SeasonArchive.ArchivePlayer>> bySeason = groupBySeason(entries);

        for (Map.Entry<String, List<SeasonArchive.ArchivePlayer>> group : bySeason.entrySet()) {
            if (writeArchive(group.getKey(), group.getValue(), now)) {
                archived++;
            }
        }

        String rolledFrom = String.join("/", new TreeSet<>(bySeason.keySet()));

        // 只有真的重置过玩家，才清空全服刷新标记（否则会平白作废所有人当天的任务进度）。
        stateManager.markSeasonRolled(target, reset > 0 ? rolledFrom : "", reset > 0);
        dataManager.setSeasonId(target);

        rolling.set(false);

        if (reset > 0 || archived > 0) {
            LOGGER.info("{} 赛季滚动完成：归档 {} 个赛季文件，重置 {} 名玩家（失败 {} 名），目标赛季 {}",
                    ModConstants.LOG_PREFIX, archived, reset, failed, target);
        } else {
            LOGGER.info("{} 赛季 ID 已确认为 {}（没有需要归档或重置的玩家）", ModConstants.LOG_PREFIX, target);
        }
    }

    private Map<String, List<SeasonArchive.ArchivePlayer>> groupBySeason(List<SeasonArchive.ArchivePlayer> entries) {
        Map<String, List<SeasonArchive.ArchivePlayer>> bySeason = new LinkedHashMap<>();

        for (SeasonArchive.ArchivePlayer entry : entries) {
            if (entry == null) {
                continue;
            }

            // 用条目自带的 seasonId 分组，而不是回读玩家存档：重置完成后
            // 玩家文件里的 seasonId 已经是新赛季了，回读只会把所有人归到新赛季。
            String seasonId = entry.seasonId == null || entry.seasonId.isBlank() ? "unknown" : entry.seasonId;

            bySeason.computeIfAbsent(seasonId, key -> new ArrayList<>()).add(entry);
        }

        return bySeason;
    }

    /** 写入（或合并进）某个赛季的归档文件。 */
    private boolean writeArchive(String seasonId, List<SeasonArchive.ArchivePlayer> entries, ZonedDateTime now) {
        Path file = paths.historyFile(sanitize(seasonId));

        try {
            SeasonArchive archive = jsonStore.read(file, SeasonArchive.class, SeasonArchive::new);
            archive.seasonId = seasonId;
            archive.archivedAt = TimeUtil.stamp(now);
            archive.merge(entries);

            jsonStore.write(file, archive);

            LOGGER.info("{} 赛季 {} 已归档 {} 名玩家到 {}（累计 {} 名）",
                    ModConstants.LOG_PREFIX, seasonId, entries.size(), file, archive.playerCount());
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 写入赛季归档失败（赛季 {}，玩家数据不受影响）：{}",
                    ModConstants.LOG_PREFIX, seasonId, file, e);
            return false;
        }
    }

    /** 把赛季 ID 里不适合做文件名的字符换掉，避免管理员写个 "S2/beta" 就写到别的目录去。 */
    static String sanitize(String seasonId) {
        if (seasonId == null || seasonId.isBlank()) {
            return "unknown";
        }

        return seasonId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String playerName(UUID uuid) {
        try {
            return dataManager.global(uuid).lastKnownName;
        } catch (RuntimeException e) {
            return "";
        }
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
