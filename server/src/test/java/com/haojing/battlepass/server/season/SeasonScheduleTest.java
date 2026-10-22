package com.haojing.battlepass.server.season;

import com.haojing.battlepass.server.config.SeasonConfig;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证赛季日程计算的边界（需求文档 §4 的赛季时长，2026-10 全模组自查新增的
 * "临近结束/已到期提醒"与首页倒计时的数据源）。
 *
 * <p>为什么单独测这些边界：倒计时文案的"今天到期算 0 天 / 已到期算负 / 未配置算 -1"
 * 最容易在实现时混在一起，而这些数值会直接渲染到玩家界面上。所有日期运算都落在
 * 本类，用确定性日期断言一次钉死，比真机跨赛季实测便宜得多。
 */
class SeasonScheduleTest {

    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");

    /** 构造一个配置了指定开始日期与时长的 SeasonConfig。 */
    private static SeasonConfig config(String startDate, int durationDays, int noticeDays) {
        SeasonConfig config = new SeasonConfig();
        config.seasonStartDate = startDate;
        config.durationDays = durationDays;
        config.rolloverNoticeDays = noticeDays;
        return config;
    }

    private static ZonedDateTime day(String date) {
        return java.time.LocalDate.parse(date).atStartOfDay(BJ);
    }

    @Test
    void 未配置开始日期时视为无日程() {
        SeasonConfig config = new SeasonConfig();
        config.seasonStartDate = "";

        assertEquals(-1, SeasonSchedule.daysUntilEnd(day("2026-10-22"), config));
        assertFalse(SeasonSchedule.isDue(day("2026-10-22"), config), "未配置日程不应判定为到期");
        assertFalse(SeasonSchedule.shouldRemind(day("2026-10-22"), config));
    }

    @Test
    void 开始日当天剩余整段时长() {
        SeasonConfig config = config("2026-10-01", 30, 3);

        assertEquals(30, SeasonSchedule.daysUntilEnd(day("2026-10-01"), config),
                "开始日当天应显示整段时长（30 天）");
        assertFalse(SeasonSchedule.isDue(day("2026-10-01"), config));
    }

    @Test
    void 结束日当天剩余一天() {
        SeasonConfig config = config("2026-10-01", 30, 3);

        assertEquals(1, SeasonSchedule.daysUntilEnd(day("2026-10-30"), config),
                "结束日（第 30 天）当天剩余 1 天");
        assertFalse(SeasonSchedule.isDue(day("2026-10-30"), config));
    }

    @Test
    void 到期日当天剩余零天() {
        SeasonConfig config = config("2026-10-01", 30, 3);

        assertEquals(0, SeasonSchedule.daysUntilEnd(day("2026-10-31"), config),
                "到期日（第 31 天）当天剩余 0 天");
        assertFalse(SeasonSchedule.isDue(day("2026-10-31"), config), "到期日当天还不算已到期");
    }

    @Test
    void 过到期日后进入负数且判定到期() {
        SeasonConfig config = config("2026-10-01", 30, 3);

        assertEquals(-1, SeasonSchedule.daysUntilEnd(day("2026-11-01"), config));
        assertTrue(SeasonSchedule.isDue(day("2026-11-01"), config));

        assertEquals(-5, SeasonSchedule.daysUntilEnd(day("2026-11-05"), config));
        assertTrue(SeasonSchedule.isDue(day("2026-11-05"), config));
    }

    @Test
    void 提醒窗口覆盖临近与到期() {
        // 提前 3 天提醒：10-28（剩 3 天）开始进入窗口，10-30（剩 1 天）仍在窗口
        SeasonConfig config = config("2026-10-01", 30, 3);

        assertFalse(SeasonSchedule.shouldRemind(day("2026-10-27"), config), "剩 4 天，还不到窗口");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-10-28"), config), "剩 3 天，进入窗口");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-10-29"), config));
        assertTrue(SeasonSchedule.shouldRemind(day("2026-10-30"), config), "剩 1 天，仍在窗口");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-10-31"), config), "到期日当天应提醒");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-11-03"), config), "已到期持续提醒，直到赛季被推进");
    }

    @Test
    void 只提醒到期当天时窗口仅覆盖当天() {
        SeasonConfig config = config("2026-10-01", 30, 0);

        assertFalse(SeasonSchedule.shouldRemind(day("2026-10-30"), config), "剩 1 天但窗口为 0，不提醒");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-10-31"), config), "到期日当天提醒");
        assertTrue(SeasonSchedule.shouldRemind(day("2026-11-02"), config), "到期后持续提醒");
    }

    @Test
    void 零时长配置按一天计算() {
        SeasonConfig config = config("2026-10-01", 0, 0);

        // 时长下限为 1 天：结束日就是开始日，次日即到期
        assertEquals(1, SeasonSchedule.daysUntilEnd(day("2026-10-01"), config));
        assertEquals(0, SeasonSchedule.daysUntilEnd(day("2026-10-02"), config));
        assertTrue(SeasonSchedule.isDue(day("2026-10-03"), config));
    }

    @Test
    void 非法日期字符串兜底为无日程() {
        SeasonConfig config = config("2026-13-99", 30, 3);

        assertEquals(-1, SeasonSchedule.daysUntilEnd(day("2026-10-22"), config),
                "配置校验之外的第二道防线：解析失败按无日程处理");
        assertFalse(SeasonSchedule.isDue(day("2026-10-22"), config));
    }

    @Test
    void 空配置不抛异常() {
        assertEquals(-1, SeasonSchedule.daysUntilEnd(null, null));
        assertFalse(SeasonSchedule.isDue(null, null));
        assertFalse(SeasonSchedule.shouldRemind(null, null));
    }
}
