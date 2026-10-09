package com.haojing.battlepass.server.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.ModPayloads;
import com.haojing.battlepass.common.net.SyncChannels;
import com.haojing.battlepass.common.net.SyncFragments;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：把服务端状态按**通道**推给客户端（需求文档 §3："配置下发需支持增量同步，
 * 并对超大 payload 分片"；§8 的六个分页）。
 *
 * <p>增量同步怎么做的（三件事）：
 * <ol>
 *   <li><b>按通道划分</b>：`player`/`tasks`/`shop`/`titles`/`collection` 各自独立。
 *       任务变了只重发 tasks，不动商店与收藏册。</li>
 *   <li><b>脏标记 + 内容比较</b>：业务侧调用 {@link #markDirty} 标脏；
 *       每秒维护时只重建"脏了"的通道。即使某个通道被反复标脏，
 *       重建后的 JSON 与上次发送的内容一致时也不会真的发包（避免无意义流量）。</li>
 *   <li><b>修订号</b>：每次真正发送递增该通道的修订号，客户端据此丢弃迟到的旧分片。</li>
 * </ol>
 *
 * <p>为什么"每秒重建一次脏通道"不会成为性能问题：脏标记为空时一次重建都不会发生；
 * 而有了内容比较兜底，即使有人把每个动作都标成全脏，
 * 也只在数据真的变化时才产生网络流量与 JSON 序列化开销。
 *
 * <p>为什么发送前要问 {@code canSend}：客户端可能没装 Mod（devMode 下允许进服），
 * 这时发送会静默失败并刷屏日志。先问一次能力，既省事又能把"这个客户端收不到"写进日志。
 */
public final class PlayerSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final SnapshotFactory snapshots;

    /** 玩家 → 已发送的修订号（每通道）。 */
    private final Map<UUID, Map<String, Integer>> revisions = new ConcurrentHashMap<>();

    /** 玩家 → 脏通道集合。 */
    private final Map<UUID, Set<String>> dirty = new ConcurrentHashMap<>();

    /** 玩家 → 每通道最近一次发送的内容（用于"内容没变就不发"）。 */
    private final Map<UUID, Map<String, String>> lastSent = new ConcurrentHashMap<>();

    /** 已经打开过界面的玩家（只为这些人推送 `player` 通道的长夜倒计时，省流量）。 */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /**
     * 已经通过 OP 校验、正在看管理面板的玩家。
     *
     * <p>为什么单独记一份而不是复用 {@link #viewers}：管理快照含配置与玩家统计，
     * **绝不能推给普通玩家**。只有走过 {@code ModNetworking} 的 OP 校验的人才会进这个集合，
     * 于是"面板能自动刷新"与"不泄露管理数据"两件事同时成立。
     */
    private final Set<UUID> adminViewers = ConcurrentHashMap.newKeySet();

    public PlayerSyncService(SnapshotFactory snapshots) {
        this.snapshots = snapshots;
    }

    /** 标记某玩家的某通道需要重发。 */
    public void markDirty(UUID playerUuid, String channel) {
        if (playerUuid == null || channel == null) {
            return;
        }

        dirty.computeIfAbsent(playerUuid, key -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    /** 标记某玩家的全部通道需要重发（例如管理员改了他的数据）。 */
    public void markAllDirty(UUID playerUuid) {
        for (String channel : SyncChannels.ALL) {
            markDirty(playerUuid, channel);
        }
    }

    /** 标记全服所有在线玩家的全部通道（例如配置热重载后）。 */
    public void markAllPlayersDirty(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            markAllDirty(player.getUuid());
        }
    }

    /** 玩家退出：清理全部按玩家保存的状态。 */
    public void forget(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        revisions.remove(playerUuid);
        dirty.remove(playerUuid);
        lastSent.remove(playerUuid);
        viewers.remove(playerUuid);
        adminViewers.remove(playerUuid);
        firstPushLogged.removeIf(key -> key.startsWith(playerUuid + "|"));
    }

    /** 玩家打开过界面（收到 RESYNC 时登记）。 */
    public void markViewer(UUID playerUuid) {
        if (playerUuid != null) {
            viewers.add(playerUuid);
        }
    }

    /** 登记"这名玩家正在看管理面板"（只有通过 OP 校验后才会调用）。 */
    public void markAdminViewer(UUID playerUuid) {
        if (playerUuid != null) {
            adminViewers.add(playerUuid);
        }
    }

    /**
     * 要求客户端打开某个面板（§9、§11）。
     *
     * @param player 玩家
     * @param panel  面板名，见 {@link SyncChannels.Panel}
     */
    public void openPanel(ServerPlayerEntity player, String panel) {
        if (player == null || panel == null) {
            return;
        }

        if (!ServerPlayNetworking.canSend(player, ModPayloads.OpenPanel.ID)) {
            LOGGER.info("{} 玩家 {} 的客户端不支持打开面板（可能未安装客户端 Mod）",
                    ModConstants.LOG_PREFIX, player.getName().getString());
            return;
        }

        ServerPlayNetworking.send(player, new ModPayloads.OpenPanel(panel));
        markViewer(player.getUuid());
    }

    /**
     * 每 20 tick 由服务端维护调用：把脏通道推给对应玩家，并给已打开界面的玩家刷新首页倒计时。
     *
     * <p>为什么要给"已经打开界面的玩家"一个周期性全脏的兜底：业务侧的埋点很难覆盖全部变化路径
     * （任务进度是玩家行为驱动的、彩蛋进度是低频的、里程碑是全服的）。
     * 与其在每个埋点里记得调用同步，不如承认"界面可见性优先" ——
     * 每 2 秒把界面上那五个通道标脏一次，而 {@code 内容比较} 保证"真的没变就不发包"。
     * 这样即使漏了某个埋点，玩家最多 2 秒后看到正确数据，而不是永远看到旧数字。
     *
     * @param server 服务端
     */
    public void tick(MinecraftServer server) {
        if (server == null || snapshots == null) {
            return;
        }

        List<UUID> online = new ArrayList<>();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            online.add(player.getUuid());
            pushPlayer(player);
        }

        // 界面兜底刷新：每隔一次维护（约 2 秒）把已打开界面的玩家的五个通道标脏；
        // 管理面板玩家额外把 admin 通道标脏（否则面板只能靠客户端主动请求才刷新）。
        if (++viewerRefreshCounter >= VIEWER_REFRESH_TICKS) {
            viewerRefreshCounter = 0;

            for (UUID viewer : viewers) {
                for (String channel : SyncChannels.ALL) {
                    markDirty(viewer, channel);
                }
            }

            for (UUID admin : adminViewers) {
                markDirty(admin, SyncChannels.ADMIN);
            }
        }

        // 只保留在线玩家的状态，避免内存随"见过的玩家数"增长。
        revisions.keySet().retainAll(online);
        dirty.keySet().retainAll(online);
        lastSent.keySet().retainAll(online);
    }

    /** 界面兜底刷新的间隔（按维护次数计，1 次 ≈ 1 秒）。 */
    private static final int VIEWER_REFRESH_TICKS = 2;

    private int viewerRefreshCounter;

    /** 推一名玩家的全部内容（进服/重连/请求全量同步时使用）。 */
    public void syncNow(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }

        markViewer(player.getUuid());
        markAllDirty(player.getUuid());

        for (String channel : SyncChannels.ALL) {
            push(player, channel);
        }
    }

    /** 只推管理面板快照（§9：管理面板需要它自己的通道）。 */
    public void pushAdminNow(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }

        markViewer(player.getUuid());
        push(player, SyncChannels.ADMIN);
    }

    /** 推一名玩家的 `player` 通道（每秒调用，内容未变时不会发包）。 */
    private void pushPlayer(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUuid();

        if (dirty.getOrDefault(uuid, Set.of()).contains(SyncChannels.PLAYER) || viewers.contains(uuid)) {
            push(player, SyncChannels.PLAYER);
        }
    }

    /**
     * 推送一个通道。
     *
     * @param player  玩家
     * @param channel 通道
     * @return 是否真的发送了
     */
    public boolean push(ServerPlayerEntity player, String channel) {
        if (player == null || snapshots == null) {
            return false;
        }

        if (!ServerPlayNetworking.canSend(player, ModPayloads.SyncFragment.ID)) {
            // 未安装客户端 Mod 的玩家（devMode 放行）：把脏标记清掉，免得每秒重试。
            clearDirty(player.getUuid(), channel);
            return false;
        }

        String json;

        try {
            json = buildJson(player.getUuid(), channel);
        } catch (RuntimeException e) {
            LOGGER.warn("{} 构建通道 {} 的快照失败（玩家 {}）：{}",
                    ModConstants.LOG_PREFIX, channel, player.getName().getString(), e.toString());
            return false;
        }

        if (json == null) {
            return false;
        }

        UUID uuid = player.getUuid();
        Map<String, String> sent = lastSent.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());

        if (json.equals(sent.get(channel))) {
            // 内容没变：清掉脏标记，但不发包（这是"增量同步"的关键一环）。
            clearDirty(uuid, channel);
            return false;
        }

        sent.put(channel, json);
        clearDirty(uuid, channel);

        int revision = revisions.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>())
                .merge(channel, 1, Integer::sum);

        byte[] data = SyncFragments.bytes(json);
        List<byte[]> parts = SyncFragments.split(data, SyncFragments.MAX_PART_BYTES);

        for (int index = 0; index < parts.size(); index++) {
            ServerPlayNetworking.send(player, new ModPayloads.SyncFragment(
                    channel, revision, index, parts.size(), parts.get(index)));
        }

        // 每个通道对每个玩家的**首次**推送打 INFO：
        // 排查"界面空白"时，这是判断"服务端到底发没发"的唯一直接证据（后续推送太频繁，只留 DEBUG）。
        if (firstPushLogged.add(player.getUuid() + "|" + channel)) {
            LOGGER.info("{} 首次同步通道 {} 给玩家 {}（修订 {}，{} 字节，{} 片）",
                    ModConstants.LOG_PREFIX, channel, player.getName().getString(), revision, data.length, parts.size());
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 已同步通道 {} 给玩家 {}（修订 {}，{} 字节，{} 片）",
                    ModConstants.LOG_PREFIX, channel, player.getName().getString(), revision, data.length, parts.size());
        }

        return true;
    }

    /** 记录"该玩家该通道已打过首次日志"，避免每秒刷屏。 */
    private final Set<String> firstPushLogged = ConcurrentHashMap.newKeySet();

    /** 按通道构建 JSON。 */
    private String buildJson(UUID playerUuid, String channel) {
        switch (channel) {
            case SyncChannels.PLAYER:
                return SyncFragments.toJson(snapshots.player(playerUuid));
            case SyncChannels.TASKS:
                return SyncFragments.toJson(snapshots.tasks(playerUuid));
            case SyncChannels.SHOP:
                return SyncFragments.toJson(snapshots.shop(playerUuid));
            case SyncChannels.TITLES:
                return SyncFragments.toJson(snapshots.titles(playerUuid));
            case SyncChannels.COLLECTION:
                return SyncFragments.toJson(snapshots.collection(playerUuid));
            case SyncChannels.ADMIN:
                return SyncFragments.toJson(snapshots.admin(onlineCount, storedCount, lastQuery));
            default:
                return null;
        }
    }

    /** 最近一次管理员查询的可读结果（由 {@code ServerAdminActions} 写入）。 */
    private volatile String lastQuery = "";

    /** 在线人数（由每秒维护更新，供管理面板概览显示）。 */
    private volatile int onlineCount;

    /** 有存档的玩家数（由每秒维护更新）。 */
    private volatile int storedCount;

    /** 记录管理员查询结果，下一次推送管理面板时带上。 */
    public void setLastQuery(String text) {
        this.lastQuery = text == null ? "" : text;
    }

    /** @return 最近一次管理查询结果。 */
    public String lastQuery() {
        return lastQuery;
    }

    /**
     * 更新人口统计（在线/有存档），供管理面板概览显示。
     *
     * @param online 在线人数
     * @param stored 有存档的玩家数
     */
    public void setPopulation(int online, int stored) {
        this.onlineCount = Math.max(0, online);
        this.storedCount = Math.max(0, stored);
    }

    /** @return 便于日志的一行摘要。 */
    public String describe() {
        return "同步：跟踪 " + revisions.size() + " 名玩家，界面已打开 " + viewers.size() + " 人";
    }

    /** 诊断用：某玩家当前的修订号快照。 */
    public Map<String, Integer> revisionsOf(UUID playerUuid) {
        Map<String, Integer> current = revisions.get(playerUuid);
        return current == null ? Map.of() : new java.util.LinkedHashMap<>(current);
    }

    /** 清掉某个脏标记（不创建新集合，避免"清空时反而分配内存"）。 */
    private void clearDirty(UUID playerUuid, String channel) {
        Set<String> channels = dirty.get(playerUuid);

        if (channels != null) {
            channels.remove(channel);
        }
    }

    /** 广播一条文本给全服（管理动作回执用）。 */
    public static void broadcast(MinecraftServer server, Text message) {
        if (server != null && message != null) {
            server.getPlayerManager().broadcast(message, false);
        }
    }
}
