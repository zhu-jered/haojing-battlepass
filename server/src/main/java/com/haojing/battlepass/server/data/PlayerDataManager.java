package com.haojing.battlepass.server.data;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 用途：玩家数据的内存态与持久化调度。需求文档 §12 要求：内存态使用 ConcurrentHashMap；
 * 写盘异步 + 节流合并（默认 5 秒），禁止主线程同步 IO；捕获全部 IO 异常，
 * 单玩家读写失败不得影响其他玩家。
 *
 * <p>为什么"标记脏 + 定时合并写"而不是每次改动立刻写盘：任务进度会随玩家行为高频变化，
 * 每次都写文件会在主线程产生大量小 IO。这里把落盘推迟到独立的写盘线程，并按 5 秒合并，
 * 同一玩家的多次改动只产生一次写入。
 *
 * <p>为什么读取不放进 ConcurrentHashMap#computeIfAbsent：该方法会在哈希桶上加锁，
 * 若在映射函数里做文件 IO，会把同桶其他 UUID 的操作一起阻塞。这里改为
 * "先查 → 缺失才读盘 → putIfAbsent 兜住并发"，把 IO 挪出锁外。
 *
 * <p>为什么关服时要同步写：进程即将退出，没有"稍后再写"的机会。这是全流程中唯一一处
 * 有意为之的同步 IO，已在代码里注明。
 */
public final class PlayerDataManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 节流合并间隔，需求文档 §12 默认 5 秒。 */
    public static final long DEFAULT_FLUSH_INTERVAL_SECONDS = 5L;

    private final StoragePaths paths;
    private final JsonStore jsonStore;

    /**
     * 当前赛季 ID。用 volatile 而不是 final：赛季滚动（阶段 5）之后，
     * 新玩家的默认存档必须带上新赛季 ID，否则他们会被下一次启动的滚动流程
     * 当成"上个赛季的存档"再重置一次，凭空丢掉一个赛季的进度。
     */
    private volatile String seasonId;

    private final long flushIntervalSeconds;

    private final Map<UUID, SeasonData> seasonCache = new ConcurrentHashMap<>();
    private final Map<UUID, GlobalData> globalCache = new ConcurrentHashMap<>();

    /** 待落盘的玩家（赛季数据 / 永久数据分开记，避免一方写失败牵连另一方重试）。 */
    private final Set<UUID> dirtySeason = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyGlobal = ConcurrentHashMap.newKeySet();

    /** 已离线、等落盘完成后可以从内存里释放的玩家。 */
    private final Set<UUID> pendingEvict = ConcurrentHashMap.newKeySet();

    /** 累计写盘次数，供需求文档 §13 的性能观测使用（自增计数器）。 */
    private final AtomicLong writeCount = new AtomicLong();

    private ScheduledExecutorService writer;

    public PlayerDataManager(StoragePaths paths, JsonStore jsonStore, String seasonId) {
        this(paths, jsonStore, seasonId, DEFAULT_FLUSH_INTERVAL_SECONDS);
    }

    public PlayerDataManager(StoragePaths paths, JsonStore jsonStore, String seasonId, long flushIntervalSeconds) {
        this.paths = paths;
        this.jsonStore = jsonStore;
        this.seasonId = seasonId;
        this.flushIntervalSeconds = flushIntervalSeconds;
    }

    /** 启动写盘线程。重复调用是安全的。 */
    public void start() {
        if (writer != null) {
            return;
        }

        writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HaoJingBP-DataWriter");
            // 设为守护线程：即使服务端异常退出，写盘线程也不会把 JVM 拖住不退出。
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(this::flushDirtyQuietly,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("{} 数据写入线程已启动（节流合并间隔 {} 秒）", ModConstants.LOG_PREFIX, flushIntervalSeconds);
    }

    /** @return 当前赛季 ID。 */
    public String seasonId() {
        return seasonId;
    }

    /**
     * 更新当前赛季 ID（赛季滚动后调用）。
     *
     * @param newSeasonId 新赛季 ID
     */
    public void setSeasonId(String newSeasonId) {
        if (newSeasonId != null && !newSeasonId.isBlank()) {
            this.seasonId = newSeasonId;
        }
    }

    /**
     * 取某玩家的赛季数据，缺失时从磁盘加载。
     *
     * @param uuid 玩家 UUID
     * @return 赛季数据（永不为 null）
     */
    public SeasonData season(UUID uuid) {
        SeasonData existing = seasonCache.get(uuid);

        if (existing != null) {
            return existing;
        }

        SeasonData loaded = loadSeason(uuid);
        SeasonData raced = seasonCache.putIfAbsent(uuid, loaded);
        return raced != null ? raced : loaded;
    }

    /**
     * 取某玩家的永久数据，缺失时从磁盘加载。
     *
     * @param uuid 玩家 UUID
     * @return 永久数据（永不为 null）
     */
    public GlobalData global(UUID uuid) {
        GlobalData existing = globalCache.get(uuid);

        if (existing != null) {
            return existing;
        }

        GlobalData loaded = loadGlobal(uuid);
        GlobalData raced = globalCache.putIfAbsent(uuid, loaded);
        return raced != null ? raced : loaded;
    }

    /** 记录玩家名，便于需求文档 §9 的"查询任意玩家"能认得出人。 */
    public void rememberPlayerName(UUID uuid, String playerName) {
        GlobalData data = global(uuid);

        if (playerName != null && !playerName.isEmpty() && !playerName.equals(data.lastKnownName)) {
            data.lastKnownName = playerName;
            markGlobalDirty(uuid);
        }
    }

    /** 标记赛季数据待落盘（供服务端主线程调用，本身不做 IO）。 */
    public void markSeasonDirty(UUID uuid) {
        dirtySeason.add(uuid);
    }

    /** 标记永久数据待落盘（供服务端主线程调用，本身不做 IO）。 */
    public void markGlobalDirty(UUID uuid) {
        dirtyGlobal.add(uuid);
    }

    /**
     * 玩家退出：标记待落盘，并登记"落盘完成后可从内存释放"。
     *
     * <p>刻意不在这里直接写盘 —— 需求文档 §12 禁止主线程同步 IO。数据先留在内存里，
     * 由写盘线程负责写入，成功后再释放，因此即使写盘暂时失败也不会丢数据。
     */
    public void onPlayerQuit(UUID uuid) {
        markSeasonDirty(uuid);
        markGlobalDirty(uuid);
        pendingEvict.add(uuid);
    }

    /**
     * 写入所有待落盘数据，并释放已完成落盘的离线玩家。
     * 可被写盘线程周期调用，也可在需要时手动调用。
     */
    public void flushDirty() {
        // 先取快照再遍历：写盘过程中集合可能被主线程改动，直接遍历并发集合的弱一致迭代器
        // 会漏掉新增项，快照能保证本次该写的都会写到，新增项留给下一轮。
        for (UUID uuid : Set.copyOf(dirtySeason)) {
            saveSeason(uuid);
        }

        for (UUID uuid : Set.copyOf(dirtyGlobal)) {
            saveGlobal(uuid);
        }

        evictFlushedOfflinePlayers();
    }

    /** @return 累计写盘成功次数。 */
    public long writeCount() {
        return writeCount.get();
    }

    /**
     * @return 当前驻留在内存中的玩家数量。
     * 供需求文档 §9 的数据维护诊断与 §13 的性能观测使用（判断离线玩家是否被正确释放）。
     */
    public int cachedPlayers() {
        return Math.max(seasonCache.size(), globalCache.size());
    }

    /**
     * 列出磁盘上存在赛季数据的全部玩家 UUID（含离线玩家）。
     * 需求文档 §5.3 要求每日刷新覆盖"在线 + 离线玩家"，需要据此枚举。
     *
     * @return 玩家 UUID 列表；目录不存在时返回空列表
     */
    public List<UUID> allStoredPlayerIds() {
        List<UUID> ids = new ArrayList<>();
        Path dir = paths.playersDir();

        if (!Files.isDirectory(dir)) {
            return ids;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();

                if (!name.endsWith(".json")) {
                    return;
                }

                try {
                    ids.add(UUID.fromString(name.substring(0, name.length() - ".json".length())));
                } catch (IllegalArgumentException e) {
                    // 文件名不是 UUID，可能是人工放进来的东西，跳过而不是让整批枚举失败。
                    LOGGER.warn("{} 跳过无法识别的玩家数据文件名：{}", ModConstants.LOG_PREFIX, name);
                }
            });
        } catch (IOException e) {
            LOGGER.error("{} 枚举玩家数据目录失败：{}", ModConstants.LOG_PREFIX, dir, e);
        }

        return ids;
    }

    /**
     * 关服流程：停止写盘线程，并把内存中全部数据同步落盘。
     *
     * <p>这里是有意为之的同步 IO（见类注释）：进程即将退出，必须确保写完。
     */
    @Override
    public void close() {
        ScheduledExecutorService current = writer;
        writer = null;

        if (current != null) {
            current.shutdown();

            try {
                if (!current.awaitTermination(flushIntervalSeconds + 5L, TimeUnit.SECONDS)) {
                    LOGGER.warn("{} 写盘线程未能在超时内结束，转为强制中断", ModConstants.LOG_PREFIX);
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int seasonCount = seasonCache.size();
        int globalCount = globalCache.size();

        for (UUID uuid : List.copyOf(seasonCache.keySet())) {
            saveSeason(uuid);
        }

        for (UUID uuid : List.copyOf(globalCache.keySet())) {
            saveGlobal(uuid);
        }

        int remain = dirtySeason.size() + dirtyGlobal.size();

        if (remain > 0) {
            LOGGER.error("{} 关服时仍有 {} 项数据未能写入，请检查磁盘权限与剩余空间",
                    ModConstants.LOG_PREFIX, remain);
        } else {
            LOGGER.info("{} 关服数据已全部落盘（赛季 {} 份 / 永久 {} 份 / 累计写入 {} 次）",
                    ModConstants.LOG_PREFIX, seasonCount, globalCount, writeCount.get());
        }
    }

    private SeasonData loadSeason(UUID uuid) {
        SeasonData data = jsonStore.read(paths.seasonFile(uuid), SeasonData.class,
                () -> new SeasonData(uuid.toString(), seasonId));

        // 存档里的身份字段可能因为人工编辑而与文件名不符，以文件名为准。
        data.playerUuid = uuid.toString();

        if (data.seasonId == null || data.seasonId.isEmpty()) {
            data.seasonId = seasonId;
        }

        // 旧存档可能缺字段，补齐以避免后续 NPE —— 需求文档要求的不兼容变更走 schemaVersion 迁移，
        // 这里只做"字段缺失"这种向后兼容的兜底。
        if (data.dailyTasks == null) {
            data.dailyTasks = new LinkedHashMap<>();
        }

        if (data.weeklyTasks == null) {
            data.weeklyTasks = new LinkedHashMap<>();
        }

        if (data.claimedLevelRewards == null) {
            data.claimedLevelRewards = new java.util.LinkedHashSet<>();
        }

        return data;
    }

    private GlobalData loadGlobal(UUID uuid) {
        GlobalData data = jsonStore.read(paths.globalFile(uuid), GlobalData.class,
                () -> new GlobalData(uuid.toString()));

        data.playerUuid = uuid.toString();

        if (data.unlockedTitles == null) {
            data.unlockedTitles = new java.util.LinkedHashSet<>();
        }

        if (data.collection == null) {
            data.collection = new java.util.LinkedHashSet<>();
        }

        if (data.claimedRedeemCodes == null) {
            data.claimedRedeemCodes = new java.util.LinkedHashSet<>();
        }

        if (data.shopPurchases == null) {
            data.shopPurchases = new java.util.LinkedHashMap<>();
        }

        return data;
    }

    private void saveSeason(UUID uuid) {
        SeasonData data = seasonCache.get(uuid);

        if (data == null) {
            dirtySeason.remove(uuid);
            return;
        }

        try {
            jsonStore.write(paths.seasonFile(uuid), data);
            dirtySeason.remove(uuid);
            writeCount.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            // 保留 dirty 标记，下一轮自动重试；单个玩家失败绝不能影响其他人。
            LOGGER.error("{} 保存玩家赛季数据失败（将自动重试）：{}", ModConstants.LOG_PREFIX, uuid, e);
        }
    }

    private void saveGlobal(UUID uuid) {
        GlobalData data = globalCache.get(uuid);

        if (data == null) {
            dirtyGlobal.remove(uuid);
            return;
        }

        try {
            jsonStore.write(paths.globalFile(uuid), data);
            dirtyGlobal.remove(uuid);
            writeCount.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("{} 保存玩家永久数据失败（将自动重试）：{}", ModConstants.LOG_PREFIX, uuid, e);
        }
    }

    private void evictFlushedOfflinePlayers() {
        for (UUID uuid : Set.copyOf(pendingEvict)) {
            if (!dirtySeason.contains(uuid) && !dirtyGlobal.contains(uuid)) {
                seasonCache.remove(uuid);
                globalCache.remove(uuid);
                pendingEvict.remove(uuid);
            }
        }
    }

    /**
     * 定时任务的入口。必须吞掉所有异常：scheduleWithFixedDelay 一旦让异常逃出去，
     * 后续调度会被框架直接取消，写盘会永久静默停止 —— 这类故障最难排查。
     */
    private void flushDirtyQuietly() {
        try {
            flushDirty();
        } catch (Throwable t) {
            LOGGER.error("{} 数据写入轮询出现未预期异常（线程继续运行）", ModConstants.LOG_PREFIX, t);
        }
    }
}
