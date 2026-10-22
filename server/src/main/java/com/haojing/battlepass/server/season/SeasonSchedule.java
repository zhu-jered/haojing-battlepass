package com.haojing.battlepass.server.season;

import com.haojing.battlepass.server.config.SeasonConfig;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 用途：赛季"日程"的纯计算（剩余天数 / 是否已到期 / 是否该提醒）。
 *
 * <p>为什么单独抽一个 MC-free 类：到期提醒与首页倒计时都是"日期差"的纯算术，
 * 但边界（今天到期算 0 天、已到期算负、未配置算 -1）最容易写错。
 * 不引用任何 Minecraft 类型，就能用单元测试确定性覆盖这些边界，
 * 不必等到真的跨赛季去实测。与 {@code SeasonResetService} 的滚动判据（配置 seasonId
 * 与玩家存档不一致）互补：那个判据回答"滚没滚"，本类回答"按日期该不该提醒"。
 *
 * <p>语义约定：赛季从 {@code seasonStartDate} 当日 0 点开始，持续 {@code durationDays}
 * 天，结束日为 {@code start + durationDays - 1}；{@code start + durationDays} 起视为已到期。
 * 因此：开始日当天剩余 {@code durationDays} 天；结束日当天剩余 1 天；
 * 到期日当天剩余 0 天；之后再过一天剩余 -1 天（超期 1 天）。
 */
public final class SeasonSchedule {

    private SeasonSchedule() {
    }

    /**
     * 计算距离赛季结束还剩多少天（按自然日）。
     *
     * <ul>
     *   <li>未配置 {@code seasonStartDate}（空串）→ {@code -1}（无日程，不提醒）；</li>
     *   <li>未到期 → 正数（剩余天数）；</li>
     *   <li>到期日当天 → {@code 0}；</li>
     *   <li>已过到期日 → 负数（超期天数，绝对值即超期多少天）。</li>
     * </ul>
     *
     * @param now    北京时间当前时刻
     * @param config 赛季配置
     * @return 剩余天数；无日程为 -1
     */
    public static int daysUntilEnd(ZonedDateTime now, SeasonConfig config) {
        if (now == null || config == null) {
            return -1;
        }

        LocalDate start = parseStartDate(config);

        if (start == null) {
            return -1;
        }

        return daysBetween(now.toLocalDate(), start, config.durationDays);
    }

    /**
     * @param now    北京时间当前时刻
     * @param config 赛季配置
     * @return 是否已经到期（配置了开始日期，且今天已过结束日）。未配置日程时恒为 false。
     */
    public static boolean isDue(ZonedDateTime now, SeasonConfig config) {
        if (now == null || config == null || parseStartDate(config) == null) {
            return false;
        }

        return daysBetween(now.toLocalDate(), parseStartDate(config), config.durationDays) < 0;
    }

    /**
     * 是否应该在今天发起一次"临近结束/已到期"的全服提醒。
     *
     * <p>为什么把"是否该提醒"和"剩余天数"分开：到期提醒只在 {@code rolloverNoticeDays}
     * 的窗口内（含到期日）触发即可；首页倒计时则每天都要显示。
     *
     * @param now    北京时间当前时刻
     * @param config 赛季配置
     * @return 是否该提醒
     */
    public static boolean shouldRemind(ZonedDateTime now, SeasonConfig config) {
        if (now == null || config == null) {
            return false;
        }

        LocalDate start = parseStartDate(config);

        if (start == null) {
            return false;
        }

        int days = daysBetween(now.toLocalDate(), start, config.durationDays);

        // 已到期：直到管理员推进新赛季（lastSeasonId 追上配置）前，每天都提醒。
        if (days < 0) {
            return true;
        }

        return days <= Math.max(0, config.rolloverNoticeDays);
    }

    /** 把配置里的开始日期解析成 LocalDate；空串或非法值返回 null（配置校验已拦过一次，这里兜底）。 */
    private static LocalDate parseStartDate(SeasonConfig config) {
        if (config.seasonStartDate == null || config.seasonStartDate.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(config.seasonStartDate.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 核心日期差：结束日（含）与今天的间隔，结束日即 start + durationDays - 1。 */
    private static int daysBetween(LocalDate today, LocalDate start, int durationDays) {
        LocalDate endExclusive = start.plusDays(Math.max(1, durationDays));
        return (int) ChronoUnit.DAYS.between(today, endExclusive);
    }
}
