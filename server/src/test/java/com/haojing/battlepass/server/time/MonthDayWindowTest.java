package com.haojing.battlepass.server.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证按 {@code MM-dd} 的每年循环时间窗口（节日问候与节日口令共用）。
 *
 * <p>为什么值得单独测：跨年窗口（例如 12-30 ~ 01-02）用朴素的字符串比较会判成空集，
 * 而"春节跨年""元旦跨年"恰恰是最常见的配置。这类边界一旦写错，
 * 表现是"节日当天什么都没发生"，很难被发现。
 */
class MonthDayWindowTest {

    @Test
    void 取月日与归一化() {
        assertEquals("10-08", MonthDayWindow.monthDayOf("2026-10-08"));
        assertEquals("", MonthDayWindow.monthDayOf("不是日期"));
        assertEquals("", MonthDayWindow.monthDayOf(null));
        assertEquals("", MonthDayWindow.monthDayOf(""));

        assertEquals("10-01", MonthDayWindow.normalize("2026-10-01"), "完整日期应取月日");
        assertEquals("10-01", MonthDayWindow.normalize(" 10-01 "));
        assertEquals("", MonthDayWindow.normalize("10/01"));
        assertEquals("", MonthDayWindow.normalize(null));
    }

    @Test
    void 普通窗口判定() {
        assertTrue(MonthDayWindow.contains("10-03", "10-01", "10-07"));
        assertTrue(MonthDayWindow.contains("10-01", "10-01", "10-07"), "起点含");
        assertTrue(MonthDayWindow.contains("10-07", "10-01", "10-07"), "终点含");
        assertFalse(MonthDayWindow.contains("09-30", "10-01", "10-07"));
        assertFalse(MonthDayWindow.contains("10-08", "10-01", "10-07"));
    }

    @Test
    void 跨年窗口判定() {
        assertTrue(MonthDayWindow.contains("12-31", "12-30", "01-02"));
        assertTrue(MonthDayWindow.contains("01-01", "12-30", "01-02"));
        assertTrue(MonthDayWindow.contains("12-30", "12-30", "01-02"), "起点含");
        assertTrue(MonthDayWindow.contains("01-02", "12-30", "01-02"), "终点含");
        assertFalse(MonthDayWindow.contains("12-29", "12-30", "01-02"));
        assertFalse(MonthDayWindow.contains("01-03", "12-30", "01-02"));
    }

    @Test
    void 只设一端时按开区间处理() {
        assertTrue(MonthDayWindow.contains("10-01", "10-01", null));
        assertTrue(MonthDayWindow.contains("12-31", "10-01", null));
        assertFalse(MonthDayWindow.contains("09-30", "10-01", null));

        assertTrue(MonthDayWindow.contains("01-01", null, "02-28"));
        assertFalse(MonthDayWindow.contains("03-01", null, "02-28"));
    }

    @Test
    void 两端都空表示全年生效() {
        assertTrue(MonthDayWindow.contains("01-01", null, null));
        assertTrue(MonthDayWindow.contains("12-31", "", ""));
    }

    @Test
    void 非法输入安全返回false() {
        assertFalse(MonthDayWindow.contains(null, "10-01", "10-07"));
        assertFalse(MonthDayWindow.contains("", "10-01", "10-07"));
        assertFalse(MonthDayWindow.contains("2026-10-01", "10-01", "10-07"));
        assertFalse(MonthDayWindow.contains("10/01", "10-01", "10-07"));
    }
}
