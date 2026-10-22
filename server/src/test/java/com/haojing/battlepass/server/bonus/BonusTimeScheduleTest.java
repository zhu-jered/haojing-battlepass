package com.haojing.battlepass.server.bonus;

import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖经验加成时段的窗口判定、倍率回退与剩余分钟计算。
 *
 * <p>为什么独立成一套测试：加成时段与长夜共享 {@code TimeUtil.isWithin} 的跨零点规则，
 * 但开关口径、倍率取值、是否白名单豁免都不同（见 {@code BonusTimeSchedule} 注释），
 * 必须单独锁住，避免以后改长夜时顺带把加成的判定改坏。
 */
class BonusTimeScheduleTest {

    private static ZonedDateTime at(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(2026, 1, dayOfMonth, hour, minute, 0, 0, TimeUtil.ZONE);
    }

    private static SeasonConfig config(String start, String end, double multiplier) {
        SeasonConfig config = new SeasonConfig();
        config.bonusTime.enabled = true;
        config.bonusTime.start = start;
        config.bonusTime.end = end;
        config.bonusTime.xpMultiplier = multiplier;
        return config;
    }

    @Test
    void 同日加成窗口判定右开区间() {
        SeasonConfig config = config("19:00", "22:00", 2.0D);

        assertTrue(BonusTimeSchedule.isActive(at(1, 19, 0), config), "19:00 整进入加成");
        assertTrue(BonusTimeSchedule.isActive(at(1, 21, 59), config));
        assertFalse(BonusTimeSchedule.isActive(at(1, 22, 0), config), "22:00 整已结束（右端开区间）");
        assertFalse(BonusTimeSchedule.isActive(at(1, 12, 0), config), "白天不在加成窗口内");
    }

    @Test
    void 跨零点加成窗口判定() {
        SeasonConfig config = config("23:00", "02:00", 2.0D);

        assertTrue(BonusTimeSchedule.isActive(at(1, 23, 30), config));
        assertTrue(BonusTimeSchedule.isActive(at(2, 1, 30), config));
        assertFalse(BonusTimeSchedule.isActive(at(2, 12, 0), config));
    }

    @Test
    void 任一开关关闭时加成不生效() {
        SeasonConfig config = config("19:00", "22:00", 2.0D);

        config.bonusTime.enabled = false;
        assertFalse(BonusTimeSchedule.isActive(at(1, 20, 0), config), "加成子开关关闭");

        config.bonusTime.enabled = true;
        config.enabled = false;
        assertFalse(BonusTimeSchedule.isActive(at(1, 20, 0), config), "赛季总开关关闭");
    }

    @Test
    void 倍率仅在加成时段内取配置值() {
        SeasonConfig config = config("19:00", "22:00", 2.5D);

        assertEquals(2.5D, BonusTimeSchedule.resolveXpMultiplier(true, config), 1e-9D,
                "加成时段内返回配置倍率");
        assertEquals(1.0D, BonusTimeSchedule.resolveXpMultiplier(false, config), 1e-9D,
                "非加成时段恒为 1.0");
        assertEquals(1.0D, BonusTimeSchedule.resolveXpMultiplier(true, null), 1e-9D,
                "配置缺失不应抛异常");
        assertEquals(1.0D, BonusTimeSchedule.resolveXpMultiplier(false, null), 1e-9D);
    }

    @Test
    void 剩余分钟只在加成时段内计算() {
        SeasonConfig config = config("19:00", "22:00", 2.0D);

        assertEquals(30L, BonusTimeSchedule.minutesRemaining(at(1, 21, 30), config, true));
        assertEquals(0L, BonusTimeSchedule.minutesRemaining(at(1, 21, 30), config, false),
                "非加成时段不计算剩余分钟");
        assertEquals(0L, BonusTimeSchedule.minutesRemaining(at(1, 21, 30), null, true),
                "配置缺失不应抛异常");
    }
}
