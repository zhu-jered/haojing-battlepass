package com.haojing.battlepass.client.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.ModSnapshots;
import com.haojing.battlepass.common.net.SyncChannels;
import com.haojing.battlepass.common.net.SyncFragments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用途：客户端保存的"服务端状态快照"（需求文档 §8 六个分页的数据来源）。
 *
 * <p>为什么要有这一层缓存：§8 的界面是**服务端权威**的 —— 所有数字都来自服务端推送，
 * 客户端只负责显示与发起动作（§2：客户端 Mod 只接收数据包 + 渲染 GUI）。
 * 界面每次重绘都去问服务端是不可能的（也不可能同步），因此必须有一份本地快照。
 *
 * <p>为什么用 Gson 直接反序列化成 :common 里的 DTO：契约类两端共用（阶段 1 的设计），
 * 因此客户端解析出来的结构与服务端发出的结构**在编译期就是同一个类**，
 * 字段改名会立刻编译失败，而不是在运行时静默变成 null。
 */
public final class ClientBattlePassState {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_CLIENT);

    private final SyncFragments.Assembler assembler = new SyncFragments.Assembler();

    private volatile ModSnapshots.Player player = new ModSnapshots.Player();
    private volatile ModSnapshots.Tasks tasks = new ModSnapshots.Tasks();
    private volatile ModSnapshots.Shop shop = new ModSnapshots.Shop();

    private volatile ModSnapshots.Titles titles = new ModSnapshots.Titles();
    private volatile ModSnapshots.Collection collection = new ModSnapshots.Collection();
    private volatile ModSnapshots.Admin admin;

    /** 每个通道最近一次收到的修订号（诊断用：界面能显示"数据到哪一步了"）。 */
    private final java.util.Map<String, Integer> revisions = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每个通道最近一次收到数据的时间。 */
    private final java.util.Map<String, Long> updatedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 解析失败的次数（收到数据但结构对不上时会增长）。 */
    private volatile int parseFailures;

    /** 最近一次服务端回执的文案键（例如"需要管理员权限"），用于界面自诊断。 */
    private volatile String lastResultKey = "";

    /** 最近一次回执是否成功。 */
    private volatile boolean lastResultSuccess;

    /**
     * 接收一个同步分片。
     *
     * @param channel   通道名
     * @param revision  修订号
     * @param partIndex 分片序号
     * @param partCount 分片总数
     * @param data      分片数据
     * @return 是否有通道被更新（界面据此决定要不要重绘）
     */
    public boolean applyFragment(String channel, int revision, int partIndex, int partCount, byte[] data) {
        String json = assembler.accept(channel, revision, partIndex, partCount, data);

        if (json == null) {
            return false;
        }

        // 先记录"收到过这个通道的哪个修订"：即使后面解析失败，界面也能显示数据到达过。
        revisions.put(channel, revision);
        updatedAt.put(channel, System.currentTimeMillis());

        Object parsed = null;

        if (SyncChannels.PLAYER.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Player.class);

            if (parsed != null) {
                player = (ModSnapshots.Player) parsed;
            }
        } else if (SyncChannels.TASKS.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Tasks.class);

            if (parsed != null) {
                tasks = (ModSnapshots.Tasks) parsed;
            }
        } else if (SyncChannels.SHOP.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Shop.class);

            if (parsed != null) {
                shop = (ModSnapshots.Shop) parsed;
            }
        } else if (SyncChannels.TITLES.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Titles.class);

            if (parsed != null) {
                titles = (ModSnapshots.Titles) parsed;
            }
        } else if (SyncChannels.COLLECTION.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Collection.class);

            if (parsed != null) {
                collection = (ModSnapshots.Collection) parsed;
            }
        } else if (SyncChannels.ADMIN.equals(channel)) {
            parsed = SyncFragments.fromJson(json, ModSnapshots.Admin.class);

            if (parsed != null) {
                admin = (ModSnapshots.Admin) parsed;
            }
        } else {
            // 未知通道：不解析、不算失败（服务端将来新增通道时老客户端应当安静忽略）。
            return false;
        }

        if (parsed == null) {
            parseFailures++;
            LOGGER.warn("{} 通道 {} 的快照解析失败（{} 字节），已忽略",
                    ModConstants.LOG_PREFIX, channel, json.length());
            return false;
        }

        return true;
    }

    /** @param channel 通道 @return 该通道最近收到的修订号；从未收到时为 0。 */
    public int revisionOf(String channel) {
        Integer value = revisions.get(channel);
        return value == null ? 0 : value;
    }

    /** @return 已经收到过数据的通道数量。 */
    public int receivedChannels() {
        return revisions.size();
    }

    /** @return 最近一次收到任意通道数据的毫秒时间戳；从未收到时为 0。 */
    public long lastUpdateMillis() {
        long latest = 0L;

        for (Long value : updatedAt.values()) {
            if (value != null && value > latest) {
                latest = value;
            }
        }

        return latest;
    }

    /** @return 解析失败的次数。 */
    public int parseFailures() {
        return parseFailures;
    }

    /** 记录一次服务端回执（供界面自诊断显示原因）。 */
    public void recordResult(boolean success, String messageKey) {
        this.lastResultSuccess = success;
        this.lastResultKey = messageKey == null ? "" : messageKey;
    }

    /** @return 最近一次回执的文案键。 */
    public String lastResultKey() {
        return lastResultKey;
    }

    /** @return 最近一次回执是否成功。 */
    public boolean lastResultSuccess() {
        return lastResultSuccess;
    }

    /** @return 首页快照。 */
    public ModSnapshots.Player player() {
        return player;
    }

    /** @return 任务快照。 */
    public ModSnapshots.Tasks tasks() {
        return tasks;
    }

    /** @return 商店快照。 */
    public ModSnapshots.Shop shop() {
        return shop;
    }

    /** @return 称号快照。 */
    public ModSnapshots.Titles titles() {
        return titles;
    }

    /** @return 收藏册快照。 */
    public ModSnapshots.Collection collection() {
        return collection;
    }

    /** @return 管理面板快照；没收到过时为 null。 */
    public ModSnapshots.Admin admin() {
        return admin;
    }

    /** @return 是否已经收到过至少一份首页快照（用于"等待服务端数据"提示）。 */
    public boolean hasPlayerData() {
        return player != null && !player.seasonId.isEmpty();
    }

    /** 断线时清空，避免把上一个服务器的数据显示出来。 */
    public void reset() {
        assembler.clear();
        player = new ModSnapshots.Player();
        tasks = new ModSnapshots.Tasks();
        shop = new ModSnapshots.Shop();
        titles = new ModSnapshots.Titles();
        collection = new ModSnapshots.Collection();
        admin = null;
        revisions.clear();
        updatedAt.clear();
        parseFailures = 0;
        lastResultKey = "";
        lastResultSuccess = false;
    }

    /** @return 仍在等待分片的通道数（诊断用）。 */
    public int pendingChannels() {
        return assembler.pendingChannels();
    }
}
