package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.server.config.SeasonConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证需求文档 §4（等级范围 1~30）下的经验曲线换算。
 *
 * <p>为什么这些用例值得单独存在：文档没有给任何经验数值（见 D-14），
 * 曲线是"可调默认值"。既然是可调的，就必须有一组测试把"当前这条曲线的形状"钉住 ——
 * 管理员改了 base/step 之后，升级所需经验是否还按预期增长，靠这几条断言就能看出来。
 */
class LevelCurveTest {

    private SeasonConfig defaultConfig() {
        return new SeasonConfig();
    }

    @Test
    void 默认曲线为每级递增10点() {
        SeasonConfig config = defaultConfig();

        assertEquals(50, LevelCurve.xpForLevel(config, 1), "1→2 级应为 50");
        assertEquals(60, LevelCurve.xpForLevel(config, 2), "2→3 级应为 60");
        assertEquals(80, LevelCurve.xpForLevel(config, 4), "4→5 级应为 80");
        assertEquals(330, LevelCurve.xpForLevel(config, 29), "29→30 级应为 330");
    }

    @Test
    void 满级与越界等级不再需要经验() {
        SeasonConfig config = defaultConfig();

        assertEquals(0, LevelCurve.xpForLevel(config, 30), "已满级不应再有升级需求");
        assertEquals(0, LevelCurve.xpForLevel(config, 31), "超出上限不应返回正数");
        assertEquals(0, LevelCurve.xpForLevel(config, 0), "0 级不是合法等级");
        assertEquals(0, LevelCurve.xpForLevel(config, -5), "负数等级不是合法等级");
    }

    @Test
    void 练满30级共需5510点经验() {
        SeasonConfig config = defaultConfig();

        assertEquals(0L, LevelCurve.totalXpToReach(config, 1), "1 级不需要经验");
        assertEquals(50L, LevelCurve.totalXpToReach(config, 2));
        // 50+60+…+330 = 5510：这条断言是"曲线总量"的回归保护，
        // 改成别的曲线（或改了 base/step）时它会立刻失败并提醒同步更新文档。
        assertEquals(5510L, LevelCurve.totalXpToReach(config, 30));
    }

    @Test
    void 曲线参数可调() {
        SeasonConfig config = defaultConfig();
        config.xpPerLevelBase = 100;
        config.xpPerLevelStep = 0;

        assertEquals(100, LevelCurve.xpForLevel(config, 1));
        assertEquals(100, LevelCurve.xpForLevel(config, 15), "step 为 0 时应为固定值");
    }

    @Test
    void 等级上限缩短后按新上限封顶() {
        SeasonConfig config = defaultConfig();
        config.maxLevel = 3;

        assertEquals(50, LevelCurve.xpForLevel(config, 1));
        assertEquals(0, LevelCurve.xpForLevel(config, 3), "3 级封顶后不应再需要经验");
    }

    @Test
    void 参数非法时不返回负数() {
        SeasonConfig config = defaultConfig();
        config.xpPerLevelBase = -100;
        config.xpPerLevelStep = -50;

        assertTrue(LevelCurve.xpForLevel(config, 5) >= 0, "参数被改坏时应走兜底曲线而不是给出负经验");
    }

    @Test
    void 极大递增量不会溢出成负数() {
        SeasonConfig config = defaultConfig();
        config.xpPerLevelStep = Integer.MAX_VALUE / 2;

        assertTrue(LevelCurve.xpForLevel(config, 29) > 0, "步长很大时应夹紧到正数，而不是溢出成负数");
    }

    @Test
    void 配置为null时使用兜底曲线() {
        assertEquals(50, LevelCurve.xpForLevel(null, 1));
        assertEquals(60, LevelCurve.xpForLevel(null, 2));
    }
}
