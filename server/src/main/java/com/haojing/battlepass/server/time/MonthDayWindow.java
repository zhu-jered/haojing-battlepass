package com.haojing.battlepass.server.time;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 用途：按 {@code MM-dd}（每年循环）判定的时间窗口，供节日问候与节日口令共用。
 *
 * <p>为什么用"月-日"而不是完整日期：节日与口令的生效时间段在需求文档里都是
 * "可配的时间段"（§10 口令、§6 节日问候），而社团活动是**每年重复**的
 * （春节、周年庆…）。让管理员每年改一次配置是没必要的负担，
 * 写 {@code 01-20 ~ 02-20} 就永远生效。
 *
 * <p>为什么支持跨年窗口：元旦、春节常跨年（例如 {@code 12-30 ~ 01-02}）。
 * 朴素地比较字符串会把跨年窗口判成空集，所以这里显式处理回绕。
 *
 * <p>本类不引用任何 Minecraft 类型，可被单元测试直接覆盖。
 */
public final class MonthDayWindow {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private MonthDayWindow() {
    }

    /**
     * 把日期键转成 {@code MM-dd}。
     *
     * @param dateKey {@code yyyy-MM-dd}
     * @return {@code MM-dd}；解析失败时返回空串
     */
    public static String monthDayOf(String dateKey) {
        if (dateKey == null || dateKey.isBlank()) {
            return "";
        }

        try {
            return LocalDate.parse(dateKey, DateTimeFormatter.ISO_LOCAL_DATE).format(MONTH_DAY);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 判断某个 {@code MM-dd} 是否落在窗口内（两端均含）。
     *
     * @param monthDay  当前 {@code MM-dd}
     * @param startDate 起始 {@code MM-dd}（空表示不设下界）
     * @param endDate   结束 {@code MM-dd}（空表示不设上界）
     * @return 是否命中；当前日期非法时返回 false
     */
    public static boolean contains(String monthDay, String startDate, String endDate) {
        if (monthDay == null || monthDay.length() != 5) {
            return false;
        }

        boolean hasStart = startDate != null && startDate.length() == 5;
        boolean hasEnd = endDate != null && endDate.length() == 5;

        if (!hasStart && !hasEnd) {
            return true;
        }

        if (hasStart && !hasEnd) {
            return monthDay.compareTo(startDate) >= 0;
        }

        if (!hasStart) {
            return monthDay.compareTo(endDate) <= 0;
        }

        if (startDate.compareTo(endDate) <= 0) {
            return monthDay.compareTo(startDate) >= 0 && monthDay.compareTo(endDate) <= 0;
        }

        // 跨年窗口：命中 [start, 12-31] 与 [01-01, end]
        return monthDay.compareTo(startDate) >= 0 || monthDay.compareTo(endDate) <= 0;
    }

    /**
     * 把管理员可能写成完整日期的值归一化成 {@code MM-dd}。
     *
     * <p>会校验形状（{@code 月-日} 且全为数字），而不是"长度是 5 就收下"：
     * 否则 {@code 10/01} 这种写错的分隔符会被原样存进配置，
     * 之后所有窗口判定都会把它当成一个永远不命中的日期，表现为"节日功能静默失效"。
     *
     * @param value {@code 2026-10-01} 或 {@code 10-01}
     * @return {@code MM-dd}；空/非法时返回空串
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();

        // 容忍完整日期 2026-10-01：取后 5 位（月-日）。
        if (trimmed.length() >= 10 && trimmed.charAt(4) == '-') {
            trimmed = trimmed.substring(5, 10);
        }

        return isMonthDay(trimmed) ? trimmed : "";
    }

    /** @return 是否是合法的 {@code MM-dd} 形状。 */
    private static boolean isMonthDay(String value) {
        if (value == null || value.length() != 5 || value.charAt(2) != '-') {
            return false;
        }

        return Character.isDigit(value.charAt(0)) && Character.isDigit(value.charAt(1))
                && Character.isDigit(value.charAt(3)) && Character.isDigit(value.charAt(4));
    }
}
