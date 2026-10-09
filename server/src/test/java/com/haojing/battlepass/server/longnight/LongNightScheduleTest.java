package com.haojing.battlepass.server.longnight;

import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖需求文档 §7 的长夜时段判定与预告时机。
 *
 * <p>为什么这些边界必须自动化验证：长夜默认 00:00~06:00，要人工验证"开始前 10 分钟预告"
 * 就得凌晨 23:50 守在服务器前；要验证跨零点配置得等到第二天。用纯函数 + 固定时刻，
 * 这些场景可以在毫秒内跑完，而且每次改动都会重新验证。
 */
class LongNightScheduleTest {

    private static ZonedDateTime at(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(2026, 1, dayOfMonth, hour, minute, 0, 0, TimeUtil.ZONE);
    }

    private static SeasonConfig config(String start, String end) {
        SeasonConfig config = new SeasonConfig();
        config.longNight.start = start;
        config.longNight.end = end;
        return config;
    }

    @Test
    void 同日长夜时段判定() {
        SeasonConfig config = config("00:00", "06:00");

        assertTrue(LongNightSchedule.isActive(at(2, 0, 0), config), "00:00 应进入长夜");
        assertTrue(LongNightSchedule.isActive(at(2, 5, 59), config));
        assertFalse(LongNightSchedule.isActive(at(2, 6, 0), config), "06:00 应已结束（右开）");
        assertFalse(LongNightSchedule.isActive(at(2, 22, 0), config));
    }

    @Test
    void 跨零点长夜时段判定() {
        SeasonConfig config = config("22:00", "06:00");

        assertTrue(LongNightSchedule.isActive(at(2, 23, 0), config));
        assertTrue(LongNightSchedule.isActive(at(2, 2, 0), config));
        assertFalse(LongNightSchedule.isActive(at(2, 12, 0), config));
    }

    @Test
    void 任一开关关闭时长夜都不生效() {
        SeasonConfig config = config("00:00", "06:00");

        config.longNight.enabled = false;
        assertFalse(LongNightSchedule.isActive(at(2, 3, 0), config), "长夜子开关关闭");

        config.longNight.enabled = true;
        config.enabled = false;
        assertFalse(LongNightSchedule.isActive(at(2, 3, 0), config), "赛季总开关关闭");
    }

    @Test
    void 开始预告只发一次() {
        SeasonConfig config = config("00:00", "06:00");
        Set<String> fired = new HashSet<>();

        // 23:50 距次日 00:00 正好 10 分钟
        assertEquals(List.of(LongNightSchedule.Notice.PRE_START),
                LongNightSchedule.collectDueNotices(at(1, 23, 50), config, false, fired, 10));

        // 轮询是每秒一次，"距开始 10 分钟"会连续成立约 60 次，必须去重
        assertTrue(LongNightSchedule.collectDueNotices(at(1, 23, 50), config, false, fired, 10).isEmpty(),
                "同一晚的开始预告只能发一次");

        // 已进入长夜后不应再提醒"即将开始"
        assertTrue(LongNightSchedule.collectDueNotices(at(2, 0, 30), config, true, fired, 10).isEmpty());
    }

    @Test
    void 结束预告只在长夜中发出() {
        SeasonConfig config = config("00:00", "06:00");
        Set<String> fired = new HashSet<>();

        assertTrue(LongNightSchedule.collectDueNotices(at(2, 5, 50), config, false, fired, 10).isEmpty(),
                "不在长夜中时不该预告结束");

        assertEquals(List.of(LongNightSchedule.Notice.PRE_END),
                LongNightSchedule.collectDueNotices(at(2, 5, 50), config, true, fired, 10));
    }

    @Test
    void 长夜关闭时不发任何预告也不写去重键() {
        SeasonConfig config = config("00:00", "06:00");
        config.longNight.enabled = false;
        Set<String> fired = new HashSet<>();

        assertTrue(LongNightSchedule.collectDueNotices(at(1, 23, 50), config, false, fired, 10).isEmpty());
        assertTrue(fired.isEmpty(), "开关关闭时不应留下去重键，否则重新开启后当晚预告会丢");
    }

    @Test
    void 白名单管理员连经验减半也豁免() {
        // 需求文档 §7 说白名单"不受长夜全部效果影响"，§5.11 的经验减半
        // 字面上是"战令联动规则"而非玩家身上的状态效果，存在解释空间。
        // 用户已确认：管理员负责服务器管理事务，长夜不应对其产生任何影响，经验也不减半。
        SeasonConfig config = config("00:00", "06:00");

        assertEquals(0.5D, LongNightSchedule.resolveXpMultiplier(true, config, false), 1e-9D,
                "长夜中普通玩家应吃 0.5 倍率");
        assertEquals(1.0D, LongNightSchedule.resolveXpMultiplier(true, config, true), 1e-9D,
                "长夜中白名单管理员应保持 1.0");

        assertEquals(1.0D, LongNightSchedule.resolveXpMultiplier(false, config, false), 1e-9D,
                "非长夜所有人都是 1.0");
        assertEquals(1.0D, LongNightSchedule.resolveXpMultiplier(false, config, true), 1e-9D);

        assertEquals(1.0D, LongNightSchedule.resolveXpMultiplier(true, null, false), 1e-9D,
                "配置缺失时不应抛异常");
    }

    @Test
    void 下一次出现时刻按日期正确推算() {
        // 23:50 的下一次 00:00 属于"次日"，跨零点长夜的预告去重键必须靠这个才对
        assertEquals("2026-01-02",
                TimeUtil.dateKey(LongNightSchedule.nextOccurrence(at(1, 23, 50), LocalTime.of(0, 0))));
        // 05:00 的下一次 06:00 仍是"当天"
        assertEquals("2026-01-01",
                TimeUtil.dateKey(LongNightSchedule.nextOccurrence(at(1, 5, 0), LocalTime.of(6, 0))));
        // 正好等于该时刻时，下一次应算到明天，避免原地反复触发
        assertEquals("2026-01-02",
                TimeUtil.dateKey(LongNightSchedule.nextOccurrence(at(1, 0, 0), LocalTime.of(0, 0))));
    }
}
