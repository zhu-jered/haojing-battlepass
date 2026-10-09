package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.data.EggCategory;
import com.haojing.battlepass.common.data.EggProgress;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：逐条验证需求文档 §6 的 10 个量化定义（外加跨零点、中断重置、跨窗口归零等边界）。
 *
 * <p>这些用例的价值在于"文档说得越死，越需要可执行的版本"：§6 明确要求
 * "必须按此实现，不得自行解释"，那么每一个数值（08:00±5、Y≥90、100 方块、半径 16、
 * 半径 32 与 3 人、5 分钟、10 秒、3 个长夜窗口、60 秒、5 分钟、0.01 位移）
 * 都应该有一条测试直接对着它写，改坏了立刻红灯。
 */
class EggEvaluatorTest {

    private EggDefinition egg(String type) {
        EggDefinition definition = new EggDefinition();
        definition.id = "egg_" + type.toLowerCase(java.util.Locale.ROOT);
        definition.category = EggCategory.GLOBAL.name();
        definition.condition = new EggCondition();
        definition.condition.type = type;
        return definition;
    }

    private EggContext context() {
        EggContext context = new EggContext();
        context.time = LocalTime.of(12, 0);
        context.dateKey = "2026-10-08";
        context.nowMillis = 1_000_000L;
        context.biome = "minecraft:plains";
        context.dimension = "minecraft:overworld";
        return context;
    }

    // ---------------- 长安拂晓 ----------------

    @Test
    void 长安拂晓在时间高度与户外都满足时触发() {
        EggDefinition definition = egg("TIME_ALTITUDE");
        EggContext context = context();
        context.time = LocalTime.of(8, 3);
        context.y = 90;
        context.outdoor = true;

        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.POLL, context, new EggProgress()).triggered());
    }

    @Test
    void 长安拂晓的时间容差为五分钟() {
        EggDefinition definition = egg("TIME_ALTITUDE");
        EggProgress progress = new EggProgress();

        EggContext within = context();
        within.time = LocalTime.of(8, 5);
        within.y = 95;
        within.outdoor = true;
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.POLL, within, progress).triggered());

        EggContext outside = context();
        outside.time = LocalTime.of(8, 6);
        outside.y = 95;
        outside.outdoor = true;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, outside, progress).triggered());
    }

    @Test
    void 长安拂晓要求高度与户外() {
        EggDefinition definition = egg("TIME_ALTITUDE");

        EggContext tooLow = context();
        tooLow.time = LocalTime.of(8, 0);
        tooLow.y = 89;
        tooLow.outdoor = true;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, tooLow, new EggProgress()).triggered());

        EggContext indoor = context();
        indoor.time = LocalTime.of(8, 0);
        indoor.y = 120;
        indoor.outdoor = false;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, indoor, new EggProgress()).triggered());
    }

    @Test
    void 长安拂晓只在轮询时机判定() {
        EggDefinition definition = egg("TIME_ALTITUDE");
        EggContext context = context();
        context.time = LocalTime.of(8, 0);
        context.y = 100;
        context.outdoor = true;

        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.FISH, context, new EggProgress()).triggered());
    }

    // ---------------- 月下筑者 ----------------

    @Test
    void 月下筑者只在满月之夜累计放置数() {
        EggDefinition definition = egg("MOON_FULL_PLACE");
        definition.condition.placeTarget = 3;
        EggProgress progress = new EggProgress();

        EggContext fullMoonNight = context();
        fullMoonNight.moonPhase = "FULL_MOON";
        fullMoonNight.night = true;

        assertEquals(0, progress.counter);
        EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, fullMoonNight, progress);
        EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, fullMoonNight, progress);
        assertEquals(2, progress.counter);

        EggContext daytime = context();
        daytime.moonPhase = "FULL_MOON";
        daytime.night = false;
        EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, daytime, progress);
        assertEquals(2, progress.counter, "白天放置不该计入");

        EggContext otherPhase = context();
        otherPhase.moonPhase = "NEW_MOON";
        otherPhase.night = true;
        EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, otherPhase, progress);
        assertEquals(2, progress.counter, "非满月不该计入");
    }

    @Test
    void 月下筑者达标后需要周边无敌对生物才触发() {
        EggDefinition definition = egg("MOON_FULL_PLACE");
        definition.condition.placeTarget = 1;
        EggProgress progress = new EggProgress();

        EggContext context = context();
        context.moonPhase = "FULL_MOON";
        context.night = true;
        context.nearbyHostile = 1;

        EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, context, progress);
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, context, progress).triggered(),
                "§6：触发瞬间半径 16 格内必须无敌对生物");

        context.nearbyHostile = 0;
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.POLL, context, progress).triggered());
    }

    // ---------------- 鱼信 ----------------

    @Test
    void 鱼信要求雷雨河流与钓鱼成功() {
        EggDefinition definition = egg("RAIN_RIVER_FISH");
        definition.condition.biome = "minecraft:river";

        EggContext perfect = context();
        perfect.biome = "minecraft:river";
        perfect.thundering = true;
        perfect.raining = true;
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.FISH, perfect, new EggProgress()).triggered());

        EggContext wrongBiome = context();
        wrongBiome.biome = "minecraft:ocean";
        wrongBiome.thundering = true;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.FISH, wrongBiome, new EggProgress()).triggered());

        EggContext noThunder = context();
        noThunder.biome = "minecraft:river";
        noThunder.raining = true;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.FISH, noThunder, new EggProgress()).triggered(),
                "§6 写的是雷雨天气");

        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, perfect, new EggProgress()).triggered(),
                "非钓鱼时机不该触发");
    }

    @Test
    void 鱼信可以放宽为下雨即可() {
        EggDefinition definition = egg("RAIN_RIVER_FISH");
        definition.condition.biome = "minecraft:river";
        definition.condition.requireThunder = false;

        EggContext rainOnly = context();
        rainOnly.biome = "minecraft:river";
        rainOnly.raining = true;

        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.FISH, rainOnly, new EggProgress()).triggered());
    }

    // ---------------- 大地勘探者 ----------------

    @Test
    void 大地勘探者集齐三种群系才触发() {
        EggDefinition definition = egg("BIOME_SET");
        definition.condition.biomes = List.of("minecraft:deep_dark", "minecraft:lush_caves", "minecraft:dripstone_caves");
        EggProgress progress = new EggProgress();

        assertTrue(collect(definition, progress, "minecraft:deep_dark"));
        assertTrue(collect(definition, progress, "minecraft:lush_caves"));

        EggContext last = context();
        last.biome = "minecraft:dripstone_caves";
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.BIOME_ENTER, last, progress).triggered());
    }

    @Test
    void 大地勘探者不统计无关群系且不重复计数() {
        EggDefinition definition = egg("BIOME_SET");
        definition.condition.biomes = List.of("minecraft:deep_dark");
        EggProgress progress = new EggProgress();

        assertFalse(collect(definition, progress, "minecraft:plains"), "无关群系不应产生进度");
        assertTrue(collect(definition, progress, "minecraft:deep_dark"));
        assertFalse(collect(definition, progress, "minecraft:deep_dark"), "同一群系只算一次");
    }

    private boolean collect(EggDefinition definition, EggProgress progress, String biome) {
        EggContext context = context();
        context.biome = biome;
        return EggEvaluator.evaluate(definition, EggTrigger.BIOME_ENTER, context, progress).changed();
    }

    // ---------------- 社团同游 ----------------

    @Test
    void 社团同游连续满足三百秒才触发() {
        EggDefinition definition = egg("GROUP_STAY");
        definition.condition.minPlayers = 3;
        definition.condition.staySeconds = 300;
        EggProgress progress = new EggProgress();

        EggContext context = context();
        context.nearbyPlayers = 3;

        boolean triggered = false;

        // 第一个采样只建立"上次满足时刻"的锚点（此时还不知道已经连续待了多久），
        // 因此 5 分钟 = 300 秒的连续停留需要第 301 次采样才能判定（t=1s 到 t=301s 恰好 300 秒）。
        for (int second = 1; second <= 301 && !triggered; second++) {
            context.nowMillis = second * 1000L;
            triggered = EggEvaluator.evaluate(definition, EggTrigger.POLL, context, progress).triggered();

            if (second < 301) {
                assertFalse(triggered, "第 " + second + " 秒不该触发");
            }
        }

        assertTrue(triggered, "§6：同群系连续停留 5 分钟应触发");
    }

    @Test
    void 社团同游分离超过十秒会重置() {
        EggDefinition definition = egg("GROUP_STAY");
        definition.condition.staySeconds = 60;
        EggProgress progress = new EggProgress();

        EggContext together = context();
        together.nearbyPlayers = 3;

        for (int second = 1; second <= 30; second++) {
            together.nowMillis = second * 1000L;
            EggEvaluator.evaluate(definition, EggTrigger.POLL, together, progress);
        }

        assertTrue(progress.counter > 0);

        // 分离 11 秒后再回来：按 §6 应当重置
        EggContext alone = context();
        alone.nearbyPlayers = 1;
        alone.nowMillis = 30_000L + 11_000L;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, alone, progress);

        assertEquals(0, progress.counter, "§6：中途分离超 10 秒则重置");
    }

    @Test
    void 社团同游短暂分离不清零() {
        EggDefinition definition = egg("GROUP_STAY");
        definition.condition.staySeconds = 60;
        EggProgress progress = new EggProgress();

        EggContext together = context();
        together.nearbyPlayers = 4;
        together.nowMillis = 1_000L;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, together, progress);

        together.nowMillis = 2_000L;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, together, progress);
        int before = progress.counter;

        together.nowMillis = 5_000L;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, together, progress);

        assertTrue(progress.counter > before, "采样间隔内的正常连续应继续累加");
    }

    // ---------------- 樱落归镐 ----------------

    @Test
    void 樱落归镐只认樱花林的树苗() {
        EggDefinition definition = egg("SAPLING_GROW");
        definition.condition.biome = "minecraft:cherry_grove";
        EggProgress progress = new EggProgress();

        EggContext wrongBiome = context();
        wrongBiome.biome = "minecraft:plains";
        wrongBiome.placedBlockId = "minecraft:cherry_sapling";
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, wrongBiome, progress).changed());

        EggContext wrongBlock = context();
        wrongBlock.biome = "minecraft:cherry_grove";
        wrongBlock.placedBlockId = "minecraft:stone";
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, wrongBlock, progress).changed());

        EggContext correct = context();
        correct.biome = "minecraft:cherry_grove";
        correct.placedBlockId = "minecraft:cherry_sapling";
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.PLACE_BLOCK, correct, progress).changed());
    }

    @Test
    void 樱落归镐在树苗长成时触发() {
        EggDefinition definition = egg("SAPLING_GROW");
        definition.condition.biome = "minecraft:cherry_grove";
        EggContext context = context();
        context.saplingGrown = true;

        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.SAPLING_GROWN, context, new EggProgress()).triggered());
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.SAPLING_GROWN, context(), new EggProgress()).triggered());
    }

    // ---------------- 守夜人 ----------------

    @Test
    void 守夜人需要连续三个达标的长夜窗口() {
        EggDefinition definition = egg("NIGHT_STREAK");
        definition.condition.nights = 3;
        definition.condition.minOnlineSeconds = 10;
        EggProgress progress = new EggProgress();

        boolean triggered = false;

        for (int night = 1; night <= 3 && !triggered; night++) {
            String period = "2026-10-0" + night + "|00:00-06:00";

            // 每个窗口在线 11 秒（够 10 秒门槛）
            for (int tick = 0; tick < 11 * 20; tick++) {
                EggContext poll = context();
                poll.longNight = true;
                poll.periodKey = period;
                poll.deltaTicks = 1;
                EggEvaluator.evaluate(definition, EggTrigger.POLL, poll, progress);
            }

            EggContext windowEnd = context();
            windowEnd.periodKey = period;
            windowEnd.onlineAtWindowEnd = true;
            triggered = EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, windowEnd, progress).triggered();
        }

        assertTrue(triggered, "§6：连续 3 个长夜窗口都达标应触发");
        assertEquals(3, progress.streak);
    }

    @Test
    void 守夜人在线不足的窗口会中断连续记录() {
        EggDefinition definition = egg("NIGHT_STREAK");
        definition.condition.nights = 3;
        definition.condition.minOnlineSeconds = 10;
        EggProgress progress = new EggProgress();

        // 第一个窗口达标
        EggContext poll = context();
        poll.longNight = true;
        poll.periodKey = "p1";
        poll.deltaTicks = 200;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, poll, progress);

        EggContext end1 = context();
        end1.periodKey = "p1";
        end1.onlineAtWindowEnd = true;
        EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, end1, progress);
        assertEquals(1, progress.streak);

        // 第二个窗口只在线 1 秒 → 连续中断
        EggContext shortPoll = context();
        shortPoll.longNight = true;
        shortPoll.periodKey = "p2";
        shortPoll.deltaTicks = 20;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, shortPoll, progress);

        EggContext end2 = context();
        end2.periodKey = "p2";
        end2.onlineAtWindowEnd = true;
        EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, end2, progress);

        assertEquals(0, progress.streak, "§6 要求「连续」，一个窗口不达标就要清零");
    }

    @Test
    void 守夜人换窗口时在线时长归零() {
        EggDefinition definition = egg("NIGHT_STREAK");
        EggProgress progress = new EggProgress();

        EggContext first = context();
        first.longNight = true;
        first.periodKey = "p1";
        first.deltaTicks = 200;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, first, progress);
        int afterFirst = progress.counter;

        EggContext second = context();
        second.longNight = true;
        second.periodKey = "p2";
        second.deltaTicks = 20;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, second, progress);

        assertTrue(afterFirst > 20);
        assertEquals(20, progress.counter, "新窗口的累计应从 0 重新开始");
    }

    // ---------------- 长夜微光 ----------------

    @Test
    void 长夜微光要求长夜空手火把与户外() {
        EggDefinition definition = egg("NIGHT_TORCH_OUTDOOR");
        definition.condition.holdSeconds = 2;
        EggProgress progress = new EggProgress();

        EggContext perfect = context();
        perfect.longNight = true;
        perfect.armorEmpty = true;
        perfect.holdingTorch = true;
        perfect.outdoor = true;
        perfect.deltaTicks = 20;

        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.POLL, perfect, progress).triggered());
        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.POLL, perfect, progress).triggered());
    }

    @Test
    void 长夜微光条件中断会清零() {
        EggDefinition definition = egg("NIGHT_TORCH_OUTDOOR");
        definition.condition.holdSeconds = 60;
        EggProgress progress = new EggProgress();

        EggContext ok = context();
        ok.longNight = true;
        ok.armorEmpty = true;
        ok.holdingTorch = true;
        ok.outdoor = true;
        ok.deltaTicks = 200;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, ok, progress);
        assertTrue(progress.counter > 0);

        EggContext wearingArmor = context();
        wearingArmor.longNight = true;
        wearingArmor.armorEmpty = false;
        wearingArmor.holdingTorch = true;
        wearingArmor.outdoor = true;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, wearingArmor, progress);

        assertEquals(0, progress.counter, "§6 要求「连续停留」，条件断了就要重来");
    }

    // ---------------- 晨归 ----------------

    @Test
    void 晨归在长夜结束且户外时触发() {
        EggDefinition definition = egg("NIGHT_SURVIVE_DAWN");
        EggContext context = context();
        context.periodKey = "p1";
        context.onlineAtWindowEnd = true;
        context.outdoor = true;

        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, context, new EggProgress()).triggered());
    }

    @Test
    void 晨归在室内或死亡时不触发() {
        EggDefinition definition = egg("NIGHT_SURVIVE_DAWN");

        EggContext indoor = context();
        indoor.periodKey = "p1";
        indoor.onlineAtWindowEnd = true;
        indoor.outdoor = false;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, indoor, new EggProgress()).triggered());

        EggProgress died = new EggProgress();
        died.addFlag("died:p1");

        EggContext outdoor = context();
        outdoor.periodKey = "p1";
        outdoor.onlineAtWindowEnd = true;
        outdoor.outdoor = true;
        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, outdoor, died).triggered(),
                "§6 要求「保持存活至 06:00」");
    }

    @Test
    void 晨归不会对未在窗口结束时刻在线的玩家触发() {
        EggDefinition definition = egg("NIGHT_SURVIVE_DAWN");
        EggContext context = context();
        context.periodKey = "p1";
        context.onlineAtWindowEnd = false;
        context.outdoor = true;

        assertFalse(EggEvaluator.evaluate(definition, EggTrigger.NIGHT_WINDOW_END, context, new EggProgress()).triggered());
    }

    // ---------------- 静听 ----------------

    @Test
    void 静听连续静止五秒触发() {
        EggDefinition definition = egg("NIGHT_STILL");
        definition.condition.stillSeconds = 5;
        definition.condition.maxMovePerTick = 0.01D;
        EggProgress progress = new EggProgress();

        boolean triggered = false;

        for (int tick = 1; tick <= 100 && !triggered; tick++) {
            EggContext context = context();
            context.longNight = true;
            context.movedDistance = 0.0D;
            context.acted = false;
            context.deltaTicks = 1;
            triggered = EggEvaluator.evaluate(definition, EggTrigger.POLL, context, progress).triggered();
        }

        assertTrue(triggered, "§6：静止 5 分钟（300 秒 = 6000 tick）应触发");
        assertEquals(100, progress.counter);
    }

    @Test
    void 静听移动或行为会清零() {
        EggDefinition definition = egg("NIGHT_STILL");
        EggProgress progress = new EggProgress();

        EggContext still = context();
        still.longNight = true;
        still.deltaTicks = 100;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, still, progress);
        assertTrue(progress.counter > 0);

        EggContext moved = context();
        moved.longNight = true;
        moved.movedDistance = 0.5D;
        moved.deltaTicks = 1;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, moved, progress);
        assertEquals(0, progress.counter, "位移超阈值应清零");

        EggContext acted = context();
        acted.longNight = true;
        acted.movedDistance = 0.0D;
        acted.acted = true;
        acted.deltaTicks = 1;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, still, progress);
        EggEvaluator.evaluate(definition, EggTrigger.POLL, acted, progress);
        assertEquals(0, progress.counter, "有破坏/放置/攻击行为应清零");
    }

    @Test
    void 静听位移阈值按采样跨度折算() {
        EggDefinition definition = egg("NIGHT_STILL");
        definition.condition.maxMovePerTick = 0.01D;
        EggProgress progress = new EggProgress();

        // 每 20 tick 采样一次：位移 0.15 格（平均每 tick 0.0075）应算"静止"
        EggContext sampled = context();
        sampled.longNight = true;
        sampled.movedDistance = 0.15D;
        sampled.deltaTicks = 20;

        assertTrue(EggEvaluator.evaluate(definition, EggTrigger.POLL, sampled, progress).changed(),
                "采样跨度折算后仍应算静止");

        // 位移 0.4 格（平均每 tick 0.02）不算静止
        EggContext fast = context();
        fast.longNight = true;
        fast.movedDistance = 0.4D;
        fast.deltaTicks = 20;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, fast, progress);

        assertEquals(0, progress.counter);
    }

    @Test
    void 非长夜时静听不累计() {
        EggDefinition definition = egg("NIGHT_STILL");
        EggProgress progress = new EggProgress();

        EggContext day = context();
        day.longNight = false;
        day.deltaTicks = 200;
        EggEvaluator.evaluate(definition, EggTrigger.POLL, day, progress);

        assertEquals(0, progress.counter);
    }

    @Test
    void 参数缺失或类型未知时安全返回() {
        EggProgress progress = new EggProgress();

        assertFalse(EggEvaluator.evaluate(null, EggTrigger.POLL, context(), progress).changed());
        assertFalse(EggEvaluator.evaluate(egg("TIME_ALTITUDE"), EggTrigger.POLL, null, progress).changed());
        assertFalse(EggEvaluator.evaluate(egg("TIME_ALTITUDE"), EggTrigger.POLL, context(), null).changed());

        EggDefinition unknown = new EggDefinition();
        unknown.condition = new EggCondition();
        unknown.condition.type = "NOT_A_TYPE";
        assertFalse(EggEvaluator.evaluate(unknown, EggTrigger.POLL, context(), progress).changed());
    }
}
