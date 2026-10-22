package com.haojing.battlepass.server.ranking;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.net.ModSnapshots;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.data.PlayerDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用途：赛季排行榜的运行时聚合服务 —— 定时遍历存档、交给纯函数 {@link RankingTable} 排序、
 * 在内存缓存一份全量排名结果，供 RANKING 通道按玩家视角组装快照。
 *
 * <p>为什么定时重建而不是每次有玩家打开页面才现算：排行榜要读全体存档（含离线玩家），
 * 如果每次打开 GUI 都遍历一遍磁盘，几百人规模下会在玩家点击瞬间制造一次 IO 尖峰。
 * 60 秒重建一次、内存里缓存排序结果，打开页面只是一次对象组装，成本很低。
 *
 * <p>为什么离线玩家也参与排名：排行榜是"本赛季战令成果榜"，离线玩家的等级/经验已经落盘，
 * 不参与就会出现"我比在线的人都高却上不了榜"的疑问；服务端直接读存档聚合，不依赖在线状态。
 *
 * <p>已知性能边界（与里程碑全服口径同一遗留，见 docs/需求偏差记录.md）：重建瞬间会逐个读取
 * 离线玩家存档。当前社团服几百人规模可接受；若未来上千人，应改为后台分批扫描并缓存。
 */
public final class RankingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 排名重建间隔（秒）。60 秒足够新升级的玩家在一分钟内进入榜单，又不会频繁读盘。 */
    private static final int REBUILD_INTERVAL_SECONDS = 60;

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;

    /** 当前缓存的全量排名结果；volatile 保证重建线程写、同步线程读时可见性。 */
    private volatile RankingTable.Ranked ranked;

    private ScheduledExecutorService executor;

    public RankingService(PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    /** 启动后台重建线程（daemon，不阻止关服）。 */
    public void start() {
        if (executor != null) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "haojing-ranking-rebuild");
            thread.setDaemon(true);
            return thread;
        });

        // 启动后先等 5 秒再跑第一轮：此时数据层已完成进服加载，避免与启动 IO 抢磁盘；
        // 之后按固定间隔重建（scheduleWithFixedDelay：上一轮跑完才计下一轮间隔，防止读盘堆积）。
        executor.scheduleWithFixedDelay(this::rebuildQuietly, 5, REBUILD_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("{} 赛季排行榜重建线程已启动（{} 秒一轮）", ModConstants.LOG_PREFIX, REBUILD_INTERVAL_SECONDS);
    }

    /**
     * 组装指定玩家视角的排行榜快照。
     *
     * <p>为什么按玩家视角现组：排名结果是全服共享的，但"谁是本人、本人第几名"因人而异；
     * 服务端缓存全量有序列表，这里只做本人标注与名次回填，不重复排序。
     *
     * @param selfUuid 查看页面的玩家 UUID
     * @return 排行榜快照
     */
    public ModSnapshots.Ranking snapshotFor(UUID selfUuid) {
        var config = configManager == null ? null : configManager.config();
        boolean enabled = config != null && config.rankingEnabled;
        return RankingTable.toSnapshot(ranked, selfUuid, enabled);
    }

    /** 立即重新聚合全服排名（测试与管理重载时可手动调用）。 */
    public void rebuild() {
        List<RankingTable.Entry> records = new ArrayList<>();

        // 磁盘存档（离线玩家）∪ 内存缓存（在线玩家与未落盘新玩家），去重后逐个读取；
        // 只扫磁盘会漏掉 60 秒重建窗口内刚升级、尚未写盘的在线玩家。
        Set<UUID> ids = new LinkedHashSet<>(dataManager.allStoredPlayerIds());
        ids.addAll(dataManager.loadedPlayerIds());

        for (UUID uuid : ids) {
            SeasonData season = dataManager.season(uuid);

            if (season == null) {
                continue;
            }

            GlobalData global = dataManager.global(uuid);
            String name = global == null || global.lastKnownName == null ? "" : global.lastKnownName;
            records.add(new RankingTable.Entry(uuid, name, season.level, season.xp));
        }

        var config = configManager.config();
        int topN = config == null ? 10 : Math.max(1, Math.min(100, config.rankingTopN));
        ranked = RankingTable.buildRanked(records, topN);
    }

    /** 一轮重建抛异常时只记 WARN，不能让定时线程被异常取消（与里程碑 persistQuietly 同一取舍）。 */
    private void rebuildQuietly() {
        try {
            rebuild();
        } catch (RuntimeException e) {
            LOGGER.warn("{} 排行榜本轮重建失败（保留上一轮结果）：{}", ModConstants.LOG_PREFIX, e.toString());
        }
    }

    /** 关停后台线程；关服流程先停排行榜再落盘数据，避免重建线程在关服时还在读存档。 */
    public void close() {
        if (executor != null) {
            executor.shutdown();

            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            executor = null;
        }
    }
}
