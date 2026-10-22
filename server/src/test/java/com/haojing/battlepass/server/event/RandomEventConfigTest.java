package com.haojing.battlepass.server.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用途：钉住怪物潮刷怪策略的默认值与夹紧规则。
 *
 * <p>为什么单独测这四个字段：2026-10 玩家反馈旧刷怪逻辑无视亮度、会把怪刷进点亮的基地与生电机房。
 * 修复后亮度阈值、半径、尝试次数、垂直高差全部走 events.json 可调；管理员手改出界时必须被夹紧到安全区间，
 * 而不是保留非法值导致刷怪半径失控或亮度闸失效。这些都是纯逻辑，不依赖真实世界，直接构造配置校验即可。
 */
class RandomEventConfigTest {

    @Test
    void 默认刷怪策略与原版夜间口径一致() {
        RandomEventConfig config = new RandomEventConfig();

        assertEquals(7, config.spawnMaxLightLevel, "默认亮度阈值 7 与原版敌对怪夜间口径一致");
        assertEquals(8, config.spawnRadius, "默认水平半径 8 格");
        assertEquals(12, config.spawnMaxAttempts, "默认每只怪找 12 个候选点");
        assertEquals(10, config.spawnVerticalRange, "默认垂直高差上限 10 格");
    }

    @Test
    void 校验会把越界亮度夹紧到0到15() {
        RandomEventConfig config = new RandomEventConfig();
        config.spawnMaxLightLevel = -3;
        config.validate();
        assertEquals(0, config.spawnMaxLightLevel);

        config.spawnMaxLightLevel = 99;
        config.validate();
        assertEquals(15, config.spawnMaxLightLevel);
    }

    @Test
    void 校验会把越界半径与尝试次数夹紧到合法区间() {
        RandomEventConfig config = new RandomEventConfig();

        config.spawnRadius = 0;
        config.spawnMaxAttempts = 0;
        config.validate();
        assertEquals(1, config.spawnRadius);
        assertEquals(1, config.spawnMaxAttempts);

        config.spawnRadius = 99;
        config.spawnMaxAttempts = 40;
        config.validate();
        assertEquals(32, config.spawnRadius);
        assertEquals(32, config.spawnMaxAttempts);
    }

    @Test
    void 校验会把越界垂直高差夹紧到1到32() {
        RandomEventConfig config = new RandomEventConfig();

        config.spawnVerticalRange = 0;
        config.validate();
        assertEquals(1, config.spawnVerticalRange);

        config.spawnVerticalRange = -5;
        config.validate();
        assertEquals(1, config.spawnVerticalRange);

        config.spawnVerticalRange = 99;
        config.validate();
        assertEquals(32, config.spawnVerticalRange);
    }

    @Test
    void 绕过校验直读字段时有效访问器仍然夹紧() {
        RandomEventConfig config = new RandomEventConfig();

        config.spawnMaxLightLevel = 20;
        config.spawnRadius = -2;
        config.spawnMaxAttempts = 99;
        config.spawnVerticalRange = -9;

        assertEquals(15, config.effectiveSpawnMaxLightLevel());
        assertEquals(1, config.effectiveSpawnRadius());
        assertEquals(32, config.effectiveSpawnMaxAttempts());
        assertEquals(1, config.effectiveSpawnVerticalRange());
    }

    @Test
    void 校验会把越界权重夹紧到1到100() {
        RandomEventConfig config = new RandomEventConfig();

        RandomEventDefinition zero = new RandomEventDefinition();
        zero.id = "zero";
        zero.type = "DOUBLE_XP";
        zero.weight = 0;
        config.events.add(zero);

        RandomEventDefinition huge = new RandomEventDefinition();
        huge.id = "huge";
        huge.type = "DOUBLE_XP";
        huge.weight = Integer.MAX_VALUE;
        config.events.add(huge);

        config.validate();

        assertEquals(1, config.event("zero").weight, "权重 0 夹紧到下限 1");
        assertEquals(100, config.event("huge").weight,
                "权重上限 100：两条 2^31 级的权重相加会让 int 溢出，抽奖时 random.nextInt(负数) 直接崩");
    }
}
