package com.haojing.battlepass.common.net;

import java.util.List;

/**
 * 用途：同步的"逻辑通道"名与面板名（需求文档 §3 的"增量同步"，§8 的六个分页，§9 的管理面板）。
 *
 * <p>为什么把同步拆成通道而不是一个大快照：§3 要求"配置下发需支持增量同步"。
 * 拆成通道之后，"今天任务换了"只需要重发 {@link #TASKS} 这一个通道，
 * 而不是把商店、称号、收藏册、管理配置全部重传一遍 —— 后者在 30 人服上
 * 会变成每秒几千字节的常态流量。
 *
 * <p>通道名是**线上协议的一部分**：改名等于改协议，必须同步递增协议版本（见
 * {@code ModConstants#PROTOCOL_VERSION}）。
 */
public final class SyncChannels {

    /** 玩家自身状态：等级、经验、星币、豁免卡、分支、长夜倒计时。 */
    public static final String PLAYER = "player";

    /** 每日任务与每周挑战。 */
    public static final String TASKS = "tasks";

    /** 战令商店。 */
    public static final String SHOP = "shop";

    /** 称号库。 */
    public static final String TITLES = "titles";

    /** 收藏册（含已达成里程碑）。 */
    public static final String COLLECTION = "collection";

    /** 管理面板配置快照（只有 OP 会收到）。 */
    public static final String ADMIN = "admin";

    /** 全部通道，供"进服全量同步"使用。 */
    public static final List<String> ALL = List.of(PLAYER, TASKS, SHOP, TITLES, COLLECTION);

    private SyncChannels() {
    }

    /** 面板名（{@link ModPayloads.OpenPanel} 用）。 */
    public static final class Panel {

        /** 玩家战令界面。 */
        public static final String PLAYER = "player";

        /** 管理面板。 */
        public static final String ADMIN = "admin";

        private Panel() {
        }
    }
}
