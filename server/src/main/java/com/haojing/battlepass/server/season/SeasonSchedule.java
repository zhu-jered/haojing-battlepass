package com.haojing.battlepass.server.season;

import com.haojing.battlepass.server.config.SeasonConfig;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 用途：赛季"日程"的纯计算（剩余天数 / 是否已到期 / 是否该提醒 / 周期口径）。
 *
 * <p>为什么单独抽一个 MC-free 类：到期提醒与首页倒计时都是"日期差"的纯算术，
 * 但边界（今天到期算 0 天、已到期算负、未配置算 -1）最容易写错。
 * 不引用任何 Minecraft 类型，就能用单元测试确定性覆盖这些边界，
 * 不必等到真的跨赛季去实测。与 {@code SeasonResetService} 的滚动判据（配置 seasonId
 * 与玩家存档不一致）互补：那个判据回答"滚没滚"，本类回答"按日期该不该提醒"。
 *
 * <p>两种周期口径（{@link SeasonConfig#cycleMode}）：
 * <ul>
 *   <li>{@code "natural"} 自然月：开始日所在月份的下月初即到期，赛季长度跟随日历
 *       （28/30/31 天不等），开始日当天算整月；</li>
 *   <li>{@code "fixed"} 固定时长：从开始日起持续 {@code durationDays} 天，
 *       结束日为 {@code start + durationDays - 1}，{@code start + durationDays} 起视为已到期。</li>
 * </ul>
 * 因此：开始日当天剩余整段时长；结束日当天剩余 1 天；到期日当天剩余 0 天；
 * 之后再过一天剩余 -1 天（超期 1 天）。
 */
public final class SeasonSchedule {

    private SeasonSchedule() {
    }

    /** 自然月模式标识（与 SeasonConfig.cycleMode 的取值对应）。 */
    public static final String CYCLE_NATURAL = "natural";

    /** 固定时长模式标识。 */
    public static final String CYCLE_FIXED = "fixed";

    /**
     * @param config 赛季配置
     * @return 是否为自然月模式；配置缺失或取值非法时按固定时长处理（与 ConfigManager 校验口径一致）。
     */
    public static boolean isNatural(SeasonConfig config) {
        return config != null && CYCLE_NATURAL.equalsIgnoreCase(config.cycleMode == null ? "" : config.cycleMode.trim());
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

        return (int) ChronoUnit.DAYS.between(now.toLocalDate(), endExclusive(start, config));
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

        return daysUntilEnd(now, config) < 0;
    }

    /**
     * 是否到达自动轮换时点：配置了开始日期，且今天已到到期日（剩余天数 ≤ 0）。
     *
     * <p>与 {@link #isDue} 的差别：{@code isDue} 是"已超期"（days &lt; 0），服务于到期提醒的
     * 文案口径；自动轮换要求在到期日当天就执行（自然月即每月 1 号切换），因此取 {@code days <= 0}，
     * 避免赛季在"到期日当天"白白多挂一天。
     */
    public static boolean isRolloverTime(ZonedDateTime now, SeasonConfig config) {
        if (now == null || config == null || parseStartDate(config) == null) {
            return false;
        }

        return daysUntilEnd(now, config) <= 0;
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

        int days = daysUntilEnd(now, config);

        // 已到期：直到新赛季开始（lastSeasonId 追上配置）前，每天都提醒。
        if (days < 0) {
            return true;
        }

        return days <= Math.max(0, config.rolloverNoticeDays);
    }

    /**
     * 自动轮换后新赛季的开始日：当前日期所在月份的 1 号。
     *
     * <p>为什么补回月初而不是用今天：服务器可能宕机几天才开机（例如 12-03 才启动，
     * 12-01 就已到期）。若新赛季从 12-03 起算，12 月剩余天数会被截短；补回本月 1 号后，
     * 自然月口径下新赛季始终对齐日历月初，下一轮到期日仍是下个月初。
     *
     * @param today 当前北京时间日期
     * @return 本月 1 号
     */
    public static LocalDate nextNaturalStart(LocalDate today) {
        return today == null ? LocalDate.now() : today.withDayOfMonth(1);
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

    /**
     * 赛季结束日（不含）：即"到期日"。今天等于这一天时剩余 0 天，过了这一天即超期。
     *
     * <ul>
     *   <li>自然月：开始日所在月份的下一个 1 号；</li>
     *   <li>固定时长：开始日 + max(1, durationDays) 天。</li>
     * </ul>
     */
    private static LocalDate endExclusive(LocalDate start, SeasonConfig config) {
        if (isNatural(config)) {
            return start.withDayOfMonth(1).plusMonths(1);
        }

        return start.plusDays(Math.max(1, config.durationDays));
    }
}
