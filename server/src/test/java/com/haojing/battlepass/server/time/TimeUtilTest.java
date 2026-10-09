package com.haojing.battlepass.server.time;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §2/§5.2 对时间判定的硬性要求 —— 固定北京时间、跨零点时段、
 * 业务日边界。这些逻辑如果出错，表现是"半夜三点长夜不生效"或"任务一天刷两次"，
 * 靠人工测几乎测不全，必须用确定性用例锁死。
 */
class TimeUtilTest {

    /** 用固定时刻构造，避免测试依赖"当前几点"。 */
    private static ZonedDateTime at(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(2026, 1, dayOfMonth, hour, minute, 0, 0, TimeUtil.ZONE);
    }

    @Test
    void 时区固定为东八区而不是跟随宿主机() {
        assertEquals(ZoneId.of("Asia/Shanghai"), TimeUtil.ZONE);
        // 中国全年不实行夏令时，任何时刻的偏移都应是 +08:00。
        assertEquals(8 * 3600, TimeUtil.now().getOffset().getTotalSeconds());
    }

    @Test
    void 同日窗口左闭右开() {
        LocalTime start = LocalTime.of(0, 0);
        LocalTime end = LocalTime.of(6, 0);

        assertTrue(TimeUtil.isWithin(at(2, 0, 0), start, end), "00:00 应命中（左闭）");
        assertTrue(TimeUtil.isWithin(at(2, 3, 0), start, end), "03:00 应命中");
        assertFalse(TimeUtil.isWithin(at(2, 6, 0), start, end), "06:00 不应命中（右开，属于新的一天）");
        assertFalse(TimeUtil.isWithin(at(2, 23, 0), start, end), "23:00 不应命中");
    }

    @Test
    void 跨零点窗口() {
        LocalTime start = LocalTime.of(22, 0);
        LocalTime end = LocalTime.of(6, 0);

        assertTrue(TimeUtil.isWithin(at(2, 22, 0), start, end), "22:00 应命中");
        assertTrue(TimeUtil.isWithin(at(2, 23, 59), start, end), "23:59 应命中");
        assertTrue(TimeUtil.isWithin(at(2, 0, 30), start, end), "00:30 应命中");
        assertTrue(TimeUtil.isWithin(at(2, 5, 59), start, end), "05:59 应命中");
        assertFalse(TimeUtil.isWithin(at(2, 6, 0), start, end), "06:00 不应命中");
        assertFalse(TimeUtil.isWithin(at(2, 12, 0), start, end), "12:00 不应命中");
        assertFalse(TimeUtil.isWithin(at(2, 21, 59), start, end), "21:59 不应命中");
    }

    @Test
    void 起止相同视为空时段而不是全天生效() {
        LocalTime same = LocalTime.of(6, 0);
        // 手误写成 06:00~06:00 时，应当表现为"功能没生效"，而不是"长夜永远开着"。
        assertFalse(TimeUtil.isWithin(at(2, 6, 0), same, same));
        assertFalse(TimeUtil.isWithin(at(2, 12, 0), same, same));
    }

    @Test
    void 业务日边界按每日刷新时刻切分() {
        LocalTime boundary = LocalTime.of(6, 0);

        assertEquals("2026-01-01", TimeUtil.businessDateKey(at(2, 3, 0), boundary), "03:00 仍属于前一天");
        assertEquals("2026-01-01", TimeUtil.businessDateKey(at(2, 5, 59), boundary), "05:59 仍属于前一天");
        assertEquals("2026-01-02", TimeUtil.businessDateKey(at(2, 6, 0), boundary), "06:00 起算新的一天");
        assertEquals("2026-01-02", TimeUtil.businessDateKey(at(2, 23, 59), boundary), "23:59 属于当天");

        // 边界为 null（配置缺失）时退化为自然日，不应该抛异常。
        assertEquals("2026-01-02", TimeUtil.businessDateKey(at(2, 3, 0), null));
    }

    @Test
    void 距下一时刻的分钟数会正确环绕() {
        assertEquals(10, TimeUtil.minutesUntil(at(2, 23, 50), LocalTime.of(0, 0)), "23:50 距次日 00:00 应为 10 分钟");
        assertEquals(10, TimeUtil.minutesUntil(at(2, 5, 50), LocalTime.of(6, 0)), "05:50 距 06:00 应为 10 分钟");
        assertEquals(0, TimeUtil.minutesUntil(at(2, 6, 0), LocalTime.of(6, 0)), "正好到点应为 0");
        // 秒会被抹掉，避免 05:59:59 算成 0 分钟
        ZonedDateTime almost = ZonedDateTime.of(2026, 1, 2, 5, 59, 59, 0, TimeUtil.ZONE);
        assertEquals(1, TimeUtil.minutesUntil(almost, LocalTime.of(6, 0)), "05:59:59 距 06:00 应为 1 分钟");
    }

    @Test
    void 时间配置宽容解析并且失败时有兜底() {
        LocalTime fallback = LocalTime.of(6, 0);

        assertEquals(LocalTime.of(6, 0), TimeUtil.parseTime("06:00", fallback));
        assertEquals(LocalTime.of(6, 0), TimeUtil.parseTime("6:00", fallback), "少写前导零应能解析");
        assertEquals(LocalTime.of(0, 0), TimeUtil.parseTime(" 00:00 ", fallback), "首尾空白应被忽略");
        assertEquals(LocalTime.of(23, 59), TimeUtil.parseTime("23:59", fallback));

        // 非法输入一律回退，绝不抛异常
        assertEquals(fallback, TimeUtil.parseTime(null, fallback));
        assertEquals(fallback, TimeUtil.parseTime("abc", fallback));
        assertEquals(fallback, TimeUtil.parseTime("24:00", fallback), "24:00 超出范围");
        assertEquals(fallback, TimeUtil.parseTime("06:60", fallback), "分钟越界");
        assertEquals(fallback, TimeUtil.parseTime("0600", fallback), "缺少冒号");
    }
}
