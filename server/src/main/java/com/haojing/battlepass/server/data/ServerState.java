package com.haojing.battlepass.server.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用途：全服共用状态，持久化到 data/haojing_battlepass/state.json。
 *
 * <p>为什么需要这个文件：需求文档 §5.3 要求每日刷新时「写入全局标记 lastDailyRefreshDate」，
 * 用于保证幂等 —— 停服跨过刷新时刻多天后重启，只补刷一次而不是按天数刷多次。
 * 这个标记是**全服**的，不属于任何一个玩家，因此不能塞进 players/&lt;uuid&gt;.json。
 *
 * <p>为什么同时记每周的刷新周键：§5.4 每周挑战默认每周一 06:00 刷新，
 * 同样存在"停服跨周"的补刷问题，判重方式与每日一致。
 */
public class ServerState {

    /** 当前结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /**
     * 上一次每日刷新所属的「业务日」键（yyyy-MM-dd，以 dailyRefreshTime 为界）。
     * 与当前业务日不同即需要刷新；相同则说明本日已刷过（幂等依据）。
     */
    public String lastDailyRefreshDate = "";

    /** 上一次每周挑战刷新所属周的起始日键（yyyy-MM-dd，周一）。 */
    public String lastWeeklyRefreshDate = "";

    /**
     * 已经完成滚动核对/归档的赛季 ID（阶段 5 新增）。
     *
     * <p>为什么需要它：判断"该不该做赛季滚动"如果每次都要扫一遍所有玩家的存档，
     * 那么在 30 人服上就是每秒读 30 个文件。有了这个标记，
     * 只要它等于配置里的 seasonId，就直接跳过（O(1) 快速路径）；
     * 管理员把 season.json 里的 seasonId 改成 S2 时，两者不等，才触发一次完整滚动。
     *
     * <p>为什么它属于全服状态：赛季是全局概念，不属于任何一个玩家。
     * 这条扩展（以及下面两条）已登记在 docs/需求偏差记录.md（D-16）。
     */
    public String lastSeasonId = "";

    /**
     * 待公告的"赛季已滚动"来源赛季 ID。非空表示还有一条结算公告没有播出去。
     *
     * <p>为什么需要它：赛季滚动最常见的时间点是开服初始化阶段（此时在线人数为 0），
     * 公告发出去也没人看得见。把"还没播报"这件事持久化下来，
     * 等有玩家在线时再播，才能真正满足 §4 的"全服结算公告"。
     */
    public String pendingSeasonRolledFrom = "";

    /**
     * 全服里程碑的累计计数器：指标名 → 累计值（阶段 6 新增，需求文档 §10、§13）。
     *
     * <p>为什么放这里而不是再开一个文件：它同样是"不属于任何玩家的全服状态"，
     * 与刷新标记、赛季标记同源。放在同一个文件里，三者的一致性由一次原子写保证。
     *
     * <p>写入时机：内存里累加（每次破坏方块都写盘显然不行），
     * 由后台线程每 60 秒合并落盘一次，关服时再同步写一次。
     */
    public Map<String, Long> metrics = new LinkedHashMap<>();

    /**
     * 已经达成过的里程碑 ID（阶段 6）。§10 要求"达成判定"，
     * 达成本身是**一次性**事件（奖励只发一次），因此必须持久化。
     */
    public Set<String> reachedMilestones = new LinkedHashSet<>();
}
