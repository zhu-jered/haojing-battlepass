package com.haojing.battlepass.server.milestone;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.reward.RewardSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用途：全服里程碑的计数、达成判定与奖励发放（需求文档 §10、§13）。
 *
 * <p>设计要点（都是"避免把服务器拖垮"的取舍）：
 * <ol>
 *   <li><b>计数在内存、定时合并落盘</b>：破坏方块这种高频动作每发生一次就写一次 JSON
 *       是不可接受的。内存里用 {@code ConcurrentHashMap} 累加，后台线程每 60 秒写一次，
 *       关服时同步再写一次。最坏情况（进程被杀）丢 1 分钟的计数，
 *       而这些计数器是"全服累计成就"，丢 1 分钟远比每次都写盘划算。</li>
 *   <li><b>人均口径的分母缓存 5 分钟</b>：分母是"协会玩家总数"，
 *       要么遍历玩家目录（IO），要么在内存里维护。5 分钟刷新一次足够 ——
 *       里程碑是长期目标，分母的瞬时波动不影响判定。</li>
 *   <li><b>达成的瞬间才写盘</b>：达成是稀有事件且必须持久化（奖励只发一次），
 *       因此 {@code markReached} 会立刻落盘。</li>
 * </ol>
 *
 * <p>{@link MetricSink} 由本类自己实现：埋点方只依赖接口，里程碑不可用时业务照常。
 */
public final class MilestoneService implements MetricSink, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 计数器落盘间隔（秒）。 */
    private static final long PERSIST_INTERVAL_SECONDS = 60L;

    /** 人均口径分母的缓存时长（毫秒）。 */
    private static final long PLAYER_COUNT_CACHE_MILLIS = 5L * 60L * 1000L;

    /**
     * 达成公告出口。做成接口是为了保持本类 MC-free（公告要拿 MinecraftServer）。
     */
    @FunctionalInterface
    public interface Announcer {
        /**
         * @param milestone 达成的里程碑
         * @param value     达成时的实际数值
         * @param awarded   实际收到奖励的玩家数
         */
        void reached(MilestoneDefinition milestone, long value, int awarded);
    }

    private final PlayerDataManager dataManager;
    private final MilestoneManager milestoneManager;
    private final ServerStateManager stateManager;

    private volatile RewardSink rewardSink;
    private volatile Announcer announcer;

    /** 内存计数器（指标 → 累计值）。 */
    private final Map<MilestoneMetric, Long> counters = new ConcurrentHashMap<>();

    /** 已达成里程碑 ID。 */
    private final Set<String> reached = ConcurrentHashMap.newKeySet();

    /** 是否有未落盘的改动。 */
    private volatile boolean dirty;

    private volatile int cachedPlayerCount;
    private volatile long playerCountCachedAt;

    private ScheduledExecutorService persister;

    public MilestoneService(PlayerDataManager dataManager, MilestoneManager milestoneManager,
                            ServerStateManager stateManager) {
        this.dataManager = dataManager;
        this.milestoneManager = milestoneManager;
        this.stateManager = stateManager;
    }

    /** 注入奖励出口（原因与 BattlePassService 相同：出口发放经验时要回调业务）。 */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /** 注入达成公告出口。 */
    public void setAnnouncer(Announcer value) {
        this.announcer = value;
    }

    /** 从全服状态文件恢复计数器与已达成集合。 */
    public void load() {
        Map<String, Long> stored = stateManager.metrics();
        counters.clear();

        for (Map.Entry<String, Long> entry : stored.entrySet()) {
            MilestoneMetric metric = MilestoneMetric.fromName(entry.getKey());

            if (metric == null || entry.getValue() == null) {
                // 指标名认不出来（可能是旧版本留下的）时跳过，而不是让整份状态失效。
                LOGGER.warn("{} 忽略无法识别的里程碑指标：{}", ModConstants.LOG_PREFIX, entry.getKey());
                continue;
            }

            counters.put(metric, entry.getValue());
        }

        reached.clear();
        reached.addAll(stateManager.reachedMilestones());

        LOGGER.info("{} 里程碑计数已恢复：{} 项指标，已达成 {} 条",
                ModConstants.LOG_PREFIX, counters.size(), reached.size());
    }

    /** 启动定时落盘线程。重复调用是安全的。 */
    public void start() {
        if (persister != null) {
            return;
        }

        persister = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HaoJingBP-MilestonePersist");
            thread.setDaemon(true);
            return thread;
        });
        persister.scheduleWithFixedDelay(this::persistQuietly,
                PERSIST_INTERVAL_SECONDS, PERSIST_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOGGER.info("{} 里程碑计数器落盘线程已启动（间隔 {} 秒）", ModConstants.LOG_PREFIX, PERSIST_INTERVAL_SECONDS);
    }

    // ------------------------------------------------------------------
    // 计数埋点
    // ------------------------------------------------------------------

    @Override
    public void add(MilestoneMetric metric, long amount) {
        if (metric == null || amount == 0L) {
            return;
        }

        counters.merge(metric, amount, Long::sum);
        dirty = true;
    }

    /**
     * @param metric 指标
     * @return 该指标的当前累计值。
     */
    public long value(MilestoneMetric metric) {
        Long current = counters.get(metric);
        return current == null ? 0L : current;
    }

    /** @return 已达成里程碑的 ID 集合（只读快照）。 */
    public Set<String> reachedMilestones() {
        return new HashSet<>(reached);
    }

    // ------------------------------------------------------------------
    // 达成判定
    // ------------------------------------------------------------------

    /**
     * 检查所有未达成的里程碑。由服务端每秒维护调用。
     *
     * @param onlinePlayerIds 当前在线玩家
     * @return 本次达成的里程碑数量
     */
    public int check(Collection<UUID> onlinePlayerIds) {
        MilestoneTable table = milestoneManager == null ? null : milestoneManager.table();

        if (table == null || table.size() == 0) {
            return 0;
        }

        List<UUID> online = onlinePlayerIds == null ? List.of() : new ArrayList<>(onlinePlayerIds);
        int reachedNow = 0;

        for (MilestoneDefinition milestone : table.all()) {
            if (reached.contains(milestone.id)) {
                continue;
            }

            MilestoneMetric metric = milestone.metricOrNull();

            if (metric == null) {
                continue;
            }

            long raw = value(metric);
            long actual = milestone.scopeOrDefault() == MilestoneDefinition.Scope.PER_CAPITA
                    ? raw / Math.max(1, playerCount())
                    : raw;

            if (actual < milestone.threshold) {
                continue;
            }

            if (markReached(milestone, actual, online)) {
                reachedNow++;
            }
        }

        return reachedNow;
    }

    /**
     * 标记达成并发放奖励。
     *
     * @param milestone 里程碑
     * @param value     达成时的实际数值
     * @param online    当前在线玩家
     * @return 本次是否真的由本调用完成了达成
     */
    boolean markReached(MilestoneDefinition milestone, long value, List<UUID> online) {
        if (!reached.add(milestone.id)) {
            // 幂等：并发/重复检查时只有一次能进来。
            return false;
        }

        // 达成必须立刻落盘：奖励只发一次，重启后再判定一次就会重复发。
        persistNow();

        MilestoneDefinition.Recipients recipients = milestone.recipientsOrDefault();
        List<UUID> targets = recipients == MilestoneDefinition.Recipients.PARTICIPANTS
                ? new ArrayList<>(online)
                : dataManager.allStoredPlayerIds();

        int awarded = 0;

        if (rewardSink != null) {
            for (UUID uuid : targets) {
                if (uuid == null) {
                    continue;
                }

                boolean any = false;

                for (Reward reward : milestone.rewards) {
                    if (rewardSink.grant(uuid, reward, "里程碑 " + milestone.id, XpSource.MILESTONE)) {
                        any = true;
                    }
                }

                if (any) {
                    awarded++;
                }
            }
        }

        // 需求文档 §10：关键操作与达成必须写日志。
        LOGGER.info("{} 全服里程碑达成：{}（指标 {} 实际 {} 阈值 {} 口径 {} 发放对象 {} 发放 {} 人）",
                ModConstants.LOG_PREFIX, milestone.id, milestone.metric, value, milestone.threshold,
                milestone.scopeOrDefault(), recipients, awarded);

        Announcer current = announcer;

        if (milestone.announce && current != null) {
            try {
                current.reached(milestone, value, awarded);
            } catch (RuntimeException e) {
                LOGGER.warn("{} 里程碑 {} 的公告发送失败：{}", ModConstants.LOG_PREFIX, milestone.id, e.toString());
            }
        }

        return true;
    }

    /**
     * @return 协会玩家总数（人均口径的分母）。带 5 分钟缓存，避免每秒遍历玩家目录。
     */
    int playerCount() {
        long now = System.currentTimeMillis();

        if (now - playerCountCachedAt < PLAYER_COUNT_CACHE_MILLIS && cachedPlayerCount > 0) {
            return cachedPlayerCount;
        }

        int count = dataManager.allStoredPlayerIds().size();
        cachedPlayerCount = count;
        playerCountCachedAt = now;
        return count;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 立即落盘（仅用于达成瞬间与关服）。 */
    public void persistNow() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();

        for (Map.Entry<MilestoneMetric, Long> entry : new EnumMap<>(counters).entrySet()) {
            snapshot.put(entry.getKey().name(), entry.getValue());
        }

        stateManager.saveMetrics(snapshot, new HashSet<>(reached));
        dirty = false;
    }

    private void persistQuietly() {
        try {
            if (dirty) {
                persistNow();
            }
        } catch (Throwable t) {
            // 定时任务里让异常逃出去会被框架取消掉后续调度 —— 那会让计数永久停止落盘。
            LOGGER.error("{} 里程碑计数器落盘出现未预期异常（线程继续运行）", ModConstants.LOG_PREFIX, t);
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService current = persister;
        persister = null;

        if (current != null) {
            current.shutdown();

            try {
                current.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            persistNow();
        } catch (RuntimeException e) {
            LOGGER.error("{} 关服时写入里程碑计数失败：{}", ModConstants.LOG_PREFIX, e.toString());
        }
    }
}
