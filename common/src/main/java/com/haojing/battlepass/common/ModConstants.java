package com.haojing.battlepass.common;

/**
 * 用途：存放服务端与客户端必须共用、且绝不允许分叉的常量。
 *
 * <p>为什么放在 :common 而不是各写一份：下面这些标识会被双方的自定义数据包通道、
 * 配置文件目录名引用。若两端各写一份字符串常量，任何一次拼写不一致都会表现为
 * 运行时数据包注册失败，而且很难定位。放在共享源码里，由编译期保证一致。
 *
 * <p>为什么是 haojing 而不是需求文档里写的 gaojing：「镐京」的「镐」是破读音，
 * 读 hào（镐京，西周都城），不读 gǎo。需求文档的 gaojing 是拼音笔误，
 * 已经用户确认统一更正为 haojing，连带包名与目录名一并更正。
 *
 * <p>为什么服务端与客户端各有一个 Mod ID：需求文档要求交付两个 jar、两份
 * fabric.mod.json（服务端 environment: "server"，客户端 environment: "client"）。
 * Fabric 要求 Mod ID 全局唯一，因此两端必须分开命名。
 */
public final class ModConstants {

    /** 服务端 Mod ID。 */
    public static final String MOD_ID_SERVER = "haojing_battlepass_server";

    /** 客户端 Mod ID。Fabric 要求 Mod ID 唯一，因此不能与服务端复用。 */
    public static final String MOD_ID_CLIENT = "haojing_battlepass_client";

    /** 日志统一前缀。需求文档 §10 要求所有关键日志带此前缀，便于在服务端日志里筛选。 */
    public static final String LOG_PREFIX = "[HaoJingBP]";

    /**
     * 目录名。需求文档 §12 规定了目录结构：
     * config/&lt;模组名&gt;/ 放配置，data/&lt;模组名&gt;/players 与 data/&lt;模组名&gt;/global 放玩家数据，
     * data/history/ 放赛季归档。
     *
     * <p>这里只定义"名字"而不写死完整相对路径，是因为 Fabric 的 config 目录与游戏目录是两个
     * 不同的根：config 要用 FabricLoader.getConfigDir()，data 要用 getGameDir()。
     * 拼装交给服务端的 StoragePaths，避免在共享常量里埋下只对某一种启动方式成立的假设。
     */
    public static final String CONFIG_SUBDIR = "haojing_battlepass";
    public static final String DATA_SUBDIR = "haojing_battlepass";
    public static final String HISTORY_SUBDIR = "history";
    public static final String PLAYERS_SUBDIR = "players";
    public static final String GLOBAL_SUBDIR = "global";

    /** 工具类不允许实例化。 */
    private ModConstants() {
    }

    /**
     * 自定义数据包使用的命名空间（阶段 7）。
     *
     * <p>为什么不直接用服务端/客户端的 Mod ID 当命名空间：两端是两个不同的 Mod ID，
     * 而自定义包通道必须是**同一个标识**才能互通。用第三个中立命名空间，
     * 任何人都不会误以为"通道属于某一端"，也就不会出现有人只改一端的情况。
     */
    public static final String NETWORK_NAMESPACE = "haojing_battlepass";

    /**
     * 协议版本号（阶段 7）。两端必须一致才允许进入游戏。
     *
     * <p>什么时候该递增它：只要数据包的**字段含义或数量**发生变化就必须递增 ——
     * 否则老客户端会按旧结构解析新包，得到的是一堆错位的数值而不是一个明确的拒绝。
     * 每次递增同时要在这里写清变更原因。
     *
     * <p>版本 1：阶段 7 首次定义（握手 + 玩家状态/任务/商店/称号/收藏册同步 + 客户端动作 + 管理面板）。
     */
    public static final int PROTOCOL_VERSION = 1;
}
