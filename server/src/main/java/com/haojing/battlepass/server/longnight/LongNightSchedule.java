package com.haojing.battlepass.server.longnight;

import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用途：长夜的纯时间排程逻辑 —— 判断"此刻是否处于长夜"、"该不该发出预告"。
 *
 * <p>为什么把这段逻辑从 {@link LongNightManager} 里抽出来：真正的时间判断一旦和
 * Minecraft 的 tick、发标题、加状态效果混在一起，就只能靠"半夜三点上服务器看"来验证。
 * 抽成不依赖任何 Minecraft 类型的纯函数后，跨零点窗口、预告只发一次、配置关闭等
 * 边界情况都能用单元测试秒级覆盖。
 *
 * <p>需求文档 §7 要求"提示时刻由配置推算，不硬编码"：这里**只**把
 * 「提前多少分钟提醒」写成常量（文档给的就是 10 分钟），而具体时刻全部由
 * 配置里的 start / end 推算出来，改配置即刻生效。
 */
public final class LongNightSchedule {

    /** 预告提前量（分钟）。需求文档 §7：开始前 10 分钟预告，结束前 10 分钟预告结束。 */
    public static final int WARN_MINUTES = 10;

    /** 预告类型。开始/结束这两个"跃变"事件不在此列，由 LongNightManager 比较状态得出。 */
    public enum Notice {
        /** 长夜即将开始。 */
        PRE_START,
        /** 长夜即将结束。 */
        PRE_END
    }

    private LongNightSchedule() {
    }

    /**
     * @param now 当前时刻
     * @param config 赛季配置
     * @return 此刻是否处于长夜时段（已同时考虑赛季总开关与长夜子开关）
     */
    public static boolean isActive(ZonedDateTime now, SeasonConfig config) {
        if (config == null || !config.longNightEnabled()) {
            return false;
        }

        return TimeUtil.isWithin(now, config.longNightStart(), config.longNightEnd());
    }

    /**
     * 计算某玩家的长夜经验倍率。
     *
     * <p>需求文档 §5.11：长夜时段内进度正常累计，经验 ×0.5（倍率可配）。
     * 需求文档 §7：管理员白名单（UUID 列表）不受长夜**全部**效果影响 ——
     * 经用户确认，这里**包括经验减半**：管理员负责服务器管理事务，长夜不应影响其收益。
     *
     * <p>为什么做成"传入是否白名单"的纯函数：白名单是按 UUID 判定的，同一时刻
     * 普通玩家与管理员的倍率不同（0.5 vs 1.0）。把判定收成一个纯函数，
     * 就能用单元测试锁住这条规则，而不是等到半夜上线去比对两个玩家的经验条。
     *
     * @param active      此刻是否处于长夜
     * @param config      赛季配置
     * @param whitelisted 该玩家是否在白名单中
     * @return 经验倍率；非长夜或白名单玩家恒为 1.0
     */
    public static double resolveXpMultiplier(boolean active, SeasonConfig config, boolean whitelisted) {
        if (!active || config == null || config.longNight == null) {
            return 1.0D;
        }

        if (whitelisted) {
            return 1.0D;
        }

        return config.longNight.xpMultiplier;
    }

    /**
     * 计算某个每日时刻的"下一次出现"的绝对时刻。
     *
     * <p>为什么需要它：预告的去重键必须能唯一标识"这一次"长夜。
     * 若用 today's date，跨零点长夜（如 22:00~06:00）的开始预告会被算到错误的日期上，
     * 导致同一晚重复提醒或漏提醒。
     *
     * @param now  当前时刻
     * @param time 每日时刻
     * @return 严格晚于 now 的下一次该时刻
     */
    public static ZonedDateTime nextOccurrence(ZonedDateTime now, LocalTime time) {
        ZonedDateTime today = now.toLocalDate().atTime(time).atZone(now.getZone());
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    /**
     * 计算某个每日时刻的"上一次出现"的绝对时刻。
     *
     * <p>为什么需要它：长夜彩蛋（守夜人、晨归）的进度必须按"长夜窗口"归零，
     * 而窗口键要能唯一标识"这一晚"。用当天的 start 时刻会出错 ——
     * 凌晨 02:00 属于前一天 22:00 开始的那一晚，日期应当算前一天。
     *
     * @param now  当前时刻
     * @param time 每日时刻
     * @return 不晚于 now 的上一次该时刻
     */
    public static ZonedDateTime previousOccurrence(ZonedDateTime now, LocalTime time) {
        ZonedDateTime today = now.toLocalDate().atTime(time).atZone(now.getZone());
        return today.isAfter(now) ? today.minusDays(1) : today;
    }

    /**
     * 计算当前所处长夜窗口的键（形如 {@code 2026-10-08|00:00-06:00}）。
     *
     * <p>用途见 {@code EggProgress#period}：连续类彩蛋的累计量必须在换窗口时归零，
     * 否则"昨晚在线 20 分钟 + 今晚在线 1 分钟"会被误判成一个满足条件的窗口。
     *
     * @param now    当前时刻
     * @param config 赛季配置
     * @return 窗口键；长夜未启用时返回空串
     */
    public static String windowKey(ZonedDateTime now, SeasonConfig config) {
        if (config == null || !config.longNightEnabled()) {
            return "";
        }

        LocalTime start = config.longNightStart();
        LocalTime end = config.longNightEnd();

        // 以"本次长夜的开始时刻所在日期"作为窗口的标识日期，跨零点窗口也能唯一区分。
        return TimeUtil.dateKey(previousOccurrence(now, start)) + "|" + start + "-" + end;
    }

    /**
     * 计算此刻应发出的预告列表，并把本次预告登记进 {@code firedKeys} 以保证只发一次。
     *
     * <p>为什么必须带去重键：本方法每 20 tick 被调用一次，而"距开始正好 10 分钟"
     * 这个条件会连续成立约 60 秒（即被调用 3 次）。没有去重的话，玩家会收到三条一模一样的预告。
     *
     * @param now        当前时刻
     * @param config     赛季配置
     * @param active     当前是否已处于长夜
     * @param firedKeys  已发出过的预告键（会被就地修改）
     * @param warnMinutes 提前量（分钟）
     * @return 本次应发出的预告
     */
    public static List<Notice> collectDueNotices(ZonedDateTime now, SeasonConfig config, boolean active,
                                                 Set<String> firedKeys, int warnMinutes) {
        List<Notice> due = new ArrayList<>();

        if (config == null || !config.longNightEnabled() || warnMinutes <= 0) {
            return due;
        }

        LocalTime start = config.longNightStart();
        LocalTime end = config.longNightEnd();

        // 还没进入长夜时，才提醒"即将开始"
        if (!active && TimeUtil.minutesUntil(now, start) == warnMinutes) {
            String key = "PRE_START@" + TimeUtil.dateKey(nextOccurrence(now, start));

            if (firedKeys.add(key)) {
                due.add(Notice.PRE_START);
            }
        }

        // 只有真正处于长夜中，才提醒"即将结束"
        if (active && TimeUtil.minutesUntil(now, end) == warnMinutes) {
            String key = "PRE_END@" + TimeUtil.dateKey(nextOccurrence(now, end));

            if (firedKeys.add(key)) {
                due.add(Notice.PRE_END);
            }
        }

        return due;
    }
}
